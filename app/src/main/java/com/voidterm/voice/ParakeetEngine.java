package com.voidterm.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.voidterm.app.SettingsDialog;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * TranscriptionEngine implementation using NVIDIA Parakeet TDT v3 via ONNX Runtime.
 *
 * Pipeline:
 *   float[] audio (16kHz mono)
 *     -> OrtSession[preprocessor] (nemo128.onnx) -> mel-spectrogram [B, T, 128]
 *     -> OrtSession[encoder] (encoder-model.int8.onnx) -> encoder states [B, T/8, 1024]
 *     -> RNN-T greedy decode loop via OrtSession[decoder_joint] -> token IDs
 *     -> ParakeetTokenizer -> text
 *
 * Thread safety:
 * - isTranscribing AtomicBoolean prevents concurrent calls
 * - abortFlag AtomicBoolean enables cooperative cancellation in decode loop
 * - OrtSession calls are thread-safe per ONNX Runtime spec
 */
public class ParakeetEngine implements TranscriptionEngine {

    private static final String TAG = "ParakeetEngine";

    // Model constants
    private static final int ENCODER_DIM = 1024;
    private static final int DECODER_STATE_DIM = 640;
    private static final long TRANSCRIPTION_TIMEOUT_MS = 120_000;

    private final SharedPreferences prefs;

    // Cached config — avoids SharedPreferences reads per transcription (mirrors WhisperEngine).
    private volatile ParakeetConfig cachedConfig;

    private static final Set<String> CONFIG_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_PARAKEET_THREAD_OVERRIDE, SettingsDialog.KEY_PARAKEET_MAX_WINDOW_SEC,
            SettingsDialog.KEY_PARAKEET_OVERLAP_SEC, SettingsDialog.KEY_PARAKEET_SILENCE_THRESHOLD,
            SettingsDialog.KEY_PARAKEET_MAX_TOKENS_STEP
    ));

    private final SharedPreferences.OnSharedPreferenceChangeListener configInvalidator =
            (p, key) -> {
                if (CONFIG_KEYS.contains(key)) {
                    cachedConfig = null;
                }
            };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isTranscribing = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private volatile boolean isDestroyed = false;
    private volatile Thread transcribeThread;

    private OrtEnvironment env;
    private OrtSession preprocessorSession;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private final ParakeetTokenizer tokenizer = new ParakeetTokenizer();

    private final List<String> logBuffer = new ArrayList<>();

    public ParakeetEngine(SharedPreferences prefs) {
        this.prefs = prefs;
        prefs.registerOnSharedPreferenceChangeListener(configInvalidator);
        cachedConfig = readConfig(prefs);
    }

    private ParakeetConfig buildConfig() {
        ParakeetConfig config = cachedConfig;
        if (config != null) return config;
        config = readConfig(prefs);
        cachedConfig = config;
        return config;
    }

    private static ParakeetConfig readConfig(SharedPreferences prefs) {
        int windowSec = clamp(
                prefs.getInt(SettingsDialog.KEY_PARAKEET_MAX_WINDOW_SEC,
                        ParakeetConfig.DEFAULT.maxWindowSamples / AudioCapture.SAMPLE_RATE),
                ParakeetConfig.MAX_WINDOW_SEC_FLOOR, ParakeetConfig.MAX_WINDOW_SEC_CEILING);
        float overlapSec = prefs.getFloat(SettingsDialog.KEY_PARAKEET_OVERLAP_SEC,
                ParakeetConfig.DEFAULT.overlapSamples / (float) AudioCapture.SAMPLE_RATE);
        return new ParakeetConfig(
                prefs.getInt(SettingsDialog.KEY_PARAKEET_THREAD_OVERRIDE, ParakeetConfig.DEFAULT.threadCount),
                windowSec * AudioCapture.SAMPLE_RATE,
                Math.round(overlapSec * AudioCapture.SAMPLE_RATE),
                prefs.getFloat(SettingsDialog.KEY_PARAKEET_SILENCE_THRESHOLD, ParakeetConfig.DEFAULT.silenceThreshold),
                ParakeetConfig.DEFAULT.searchBandSamples, // testability seam, not user-facing
                prefs.getInt(SettingsDialog.KEY_PARAKEET_MAX_TOKENS_STEP, ParakeetConfig.DEFAULT.maxTokensPerStep)
        );
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private void bufLog(String msg) {
        Log.i(TAG, msg);
        synchronized (logBuffer) {
            logBuffer.add(System.currentTimeMillis() + " " + msg);
        }
    }

    private void bufErr(String msg) {
        Log.e(TAG, msg);
        synchronized (logBuffer) {
            logBuffer.add(System.currentTimeMillis() + " ERROR: " + msg);
        }
    }

    @Override
    public void loadModel(Context context, Callback callback) {
        if (!isLoading.compareAndSet(false, true)) {
            mainHandler.post(() -> callback.onError("Model load already in progress"));
            return;
        }

        new Thread(() -> {
            try {
                if (!ParakeetModelManager.isModelComplete(context)) {
                    isLoading.set(false);
                    mainHandler.post(() -> callback.onError(
                            "Parakeet models not downloaded. Download them in Settings."));
                    return;
                }

                File modelDir = ParakeetModelManager.getModelDir(context);

                mainHandler.post(() -> callback.onProgress("Loading Parakeet preprocessor...", 10));
                env = OrtEnvironment.getEnvironment();

                // Spike: log which execution providers are actually bundled in the AAR
                // on this device (cannot be verified statically). Informs whether an
                // XNNPACK toggle is worth building later — no behavior change here.
                bufLog("Available ONNX providers: " + OrtEnvironment.getAvailableProviders());

                ParakeetConfig config = buildConfig();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                // Graph optimization is a free, always-on win (no reason to pick less).
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                // Intra-op threads help the encoder's large matmuls. 0 = auto via CpuInfo.
                int threads = config.threadCount > 0
                        ? config.threadCount : CpuInfo.getPreferredThreadCount();
                opts.setIntraOpNumThreads(threads);
                bufLog("Session options: optLevel=ALL_OPT, intraOpThreads=" + threads);

                bufLog("Loading preprocessor: nemo128.onnx");
                long start = System.currentTimeMillis();
                preprocessorSession = env.createSession(
                        new File(modelDir, "nemo128.onnx").getAbsolutePath(), opts);
                bufLog("Preprocessor loaded in " + (System.currentTimeMillis() - start) + "ms");

                mainHandler.post(() -> callback.onProgress("Loading Parakeet encoder...", 30));
                bufLog("Loading encoder: encoder-model.int8.onnx");
                start = System.currentTimeMillis();
                encoderSession = env.createSession(
                        new File(modelDir, "encoder-model.int8.onnx").getAbsolutePath(), opts);
                bufLog("Encoder loaded in " + (System.currentTimeMillis() - start) + "ms");

                mainHandler.post(() -> callback.onProgress("Loading Parakeet decoder...", 60));
                bufLog("Loading decoder: decoder_joint-model.int8.onnx");
                start = System.currentTimeMillis();
                decoderSession = env.createSession(
                        new File(modelDir, "decoder_joint-model.int8.onnx").getAbsolutePath(), opts);
                bufLog("Decoder loaded in " + (System.currentTimeMillis() - start) + "ms");

                opts.close();

                mainHandler.post(() -> callback.onProgress("Loading vocabulary...", 90));
                tokenizer.load(new File(modelDir, "vocab.txt"));
                bufLog("Vocabulary loaded: " + tokenizer.size() + " tokens");

                if (isDestroyed) {
                    isLoading.set(false);
                    closeSessionsSafe();
                    return;
                }

                isLoading.set(false);
                mainHandler.post(() -> callback.onProgress("Parakeet ready", 100));
                mainHandler.post(() -> callback.onSuccess("Parakeet TDT v3 loaded"));

            } catch (Exception e) {
                isLoading.set(false);
                bufErr("Failed to load Parakeet: " + e.getMessage());
                closeSessionsSafe();
                mainHandler.post(() -> callback.onError("Failed to load Parakeet: " + e.getMessage()));
            }
        }, "ParakeetEngine-ModelLoad").start();
    }

    @Override
    public void transcribe(float[] audio, Callback callback) {
        if (!isTranscribing.compareAndSet(false, true)) {
            mainHandler.post(() -> callback.onError("Transcription already in progress"));
            return;
        }

        if (!isModelLoaded()) {
            isTranscribing.set(false);
            mainHandler.post(() -> callback.onError("Parakeet model not loaded"));
            return;
        }

        if (audio == null || audio.length == 0) {
            isTranscribing.set(false);
            mainHandler.post(() -> callback.onError("No audio data"));
            return;
        }

        abortFlag.set(false);

        // Split once: audio that fits one encoder pass yields a single chunk referencing
        // the original array (no copy, identical to the pre-chunking path). Longer audio
        // is split at silence boundaries — see AudioChunker.
        ParakeetConfig config = buildConfig();
        List<AudioChunker.Chunk> chunks = AudioChunker.split(audio, config);

        bufLog("Transcription start: " + audio.length + " samples ("
                + String.format("%.1f", audio.length / 16000f) + "s), chunks=" + chunks.size());

        Thread thread = new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                String result = (chunks.size() == 1)
                        ? runInference(chunks.get(0).samples)
                        : runChunked(chunks, callback);
                long elapsed = System.currentTimeMillis() - startTime;

                if (!isTranscribing.compareAndSet(true, false)) {
                    return; // Timed out or aborted
                }

                if (isDestroyed) {
                    mainHandler.post(() -> callback.onError("Engine destroyed during transcription"));
                    return;
                }

                bufLog("Transcription completed in " + elapsed + "ms");

                mainHandler.post(() -> {
                    if (result == null || result.trim().isEmpty()) {
                        callback.onError("No speech detected");
                    } else {
                        bufLog("Result: " + result.trim());
                        callback.onSuccess(result.trim());
                    }
                });

            } catch (Exception e) {
                bufErr("Transcription failed: " + e.getMessage());
                isTranscribing.set(false);
                if (isDestroyed) return;
                mainHandler.post(() -> callback.onError("Transcription error: " + e.getMessage()));
            }
        }, "ParakeetEngine-Transcribe");

        transcribeThread = thread;
        thread.start();

        // Watchdog timeout — scaled by chunk count for long, multi-pass audio.
        long timeout = TRANSCRIPTION_TIMEOUT_MS * Math.max(1, chunks.size());
        mainHandler.postDelayed(() -> {
            if (isTranscribing.compareAndSet(true, false)) {
                bufErr("Transcription timed out");
                abortFlag.set(true);
                callback.onError("Transcription timed out");
            }
        }, timeout);
    }

    /**
     * Transcribe a multi-chunk recording, merging chunk texts into a rolling transcript.
     * Checks for cancellation between chunks (per-chunk cancellation is handled inside
     * runInference). Fallback (continuous-speech) chunks carry an overlap region, so
     * their leading duplicated words are de-duplicated against the previous chunk.
     */
    private String runChunked(List<AudioChunker.Chunk> chunks, Callback callback) throws OrtException {
        StringBuilder full = new StringBuilder();
        String prevText = "";
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            if (abortFlag.get()) break;

            final int idx = i;
            mainHandler.post(() -> callback.onProgress(
                    "Transcribing " + (idx + 1) + "/" + total, (int) (idx * 100.0 / total)));

            String text = runInference(chunks.get(i).samples);
            text = (text == null) ? "" : text.trim();

            if (chunks.get(i).isFallbackSplit) {
                text = dedupLeading(prevText, text);
            }
            if (!text.isEmpty()) {
                if (full.length() > 0) full.append(' ');
                full.append(text);
            }
            prevText = text;
            bufLog("Chunk " + (idx + 1) + "/" + total + " -> \"" + text + "\"");
        }
        return full.toString();
    }

    /**
     * Remove the leading words of {@code cur} that duplicate the trailing words of
     * {@code prev} (the overlap region of a fallback hard split). Word-level,
     * case-insensitive, bounded to a small window.
     */
    static String dedupLeading(String prev, String cur) {
        if (prev.isEmpty() || cur.isEmpty()) return cur;
        String[] prevWords = prev.split("\\s+");
        String[] curWords = cur.split("\\s+");
        int maxK = Math.min(Math.min(prevWords.length, curWords.length), 12);
        int bestK = 0;
        for (int k = maxK; k >= 1; k--) {
            boolean match = true;
            for (int j = 0; j < k; j++) {
                if (!prevWords[prevWords.length - k + j].equalsIgnoreCase(curWords[j])) {
                    match = false;
                    break;
                }
            }
            if (match) { bestK = k; break; }
        }
        if (bestK == 0) return cur;
        StringBuilder sb = new StringBuilder();
        for (int j = bestK; j < curWords.length; j++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(curWords[j]);
        }
        return sb.toString();
    }

    /**
     * Run the full inference pipeline: preprocess -> encode -> decode.
     * Called on the transcription thread.
     */
    // Leading silence (0.1s at 16kHz) so the model has context to detect the first word
    private static final int LEADING_PAD_SAMPLES = 1600;

    private String runInference(float[] audio) throws OrtException {
        // Prepend short silence — NeMo models need a small lead-in to avoid
        // cropping the first word (convolution warm-up in preprocessor)
        float[] padded = new float[LEADING_PAD_SAMPLES + audio.length];
        System.arraycopy(audio, 0, padded, LEADING_PAD_SAMPLES, audio.length);

        // Step 1: Preprocess — waveform to mel-spectrogram
        bufLog("Step 1: Preprocessing (" + padded.length + " samples, inc. " + LEADING_PAD_SAMPLES + " pad)");
        long stepStart = System.currentTimeMillis();

        float[][] melFeatures;
        long numFrames;

        OnnxTensor waveformTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(padded), new long[]{1, padded.length});
        OnnxTensor waveformLenTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(new long[]{padded.length}), new long[]{1});

        try (OrtSession.Result prepResult = preprocessorSession.run(
                Map.of("waveforms", waveformTensor, "waveforms_lens", waveformLenTensor))) {

            // Output: features [1, 128, T], features_lens [1] — use named lookup
            float[][][] features = (float[][][]) prepResult.get("features").get().getValue();
            long[] featuresLens = (long[]) prepResult.get("features_lens").get().getValue();
            numFrames = featuresLens[0];

            int dim1 = features[0].length;
            int dim2 = features[0][0].length;
            bufLog("Preprocessor output shape: [1, " + dim1 + ", " + dim2 + "], numFrames=" + numFrames);

            // Keep raw [128, T] layout — encoder expects the same shape
            melFeatures = features[0];
        } finally {
            waveformTensor.close();
            waveformLenTensor.close();
        }

        bufLog("Preprocessing done in " + (System.currentTimeMillis() - stepStart)
                + "ms, " + numFrames + " frames");

        if (abortFlag.get()) return null;

        // Step 2: Encode — mel-spectrogram to encoder states
        bufLog("Step 2: Encoding");
        stepStart = System.currentTimeMillis();

        float[][] encoderOutput;
        long encoderLength;

        // Flatten melFeatures [128, T] to 1D for tensor creation with shape [1, 128, T]
        float[] melFlat = new float[128 * (int) numFrames];
        for (int i = 0; i < 128; i++) {
            System.arraycopy(melFeatures[i], 0, melFlat, i * (int) numFrames, (int) numFrames);
        }

        OnnxTensor audioSignalTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(melFlat), new long[]{1, 128, numFrames});
        OnnxTensor lengthTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(new long[]{numFrames}), new long[]{1});

        try (OrtSession.Result encResult = encoderSession.run(
                Map.of("audio_signal", audioSignalTensor, "length", lengthTensor))) {

            // Encoder output: [1, 1024, T/8] — use named lookup
            float[][][] rawOutput = (float[][][]) encResult.get("outputs").get().getValue();
            long[] encLengths = (long[]) encResult.get("encoded_lengths").get().getValue();
            encoderLength = encLengths[0];

            int edim1 = rawOutput[0].length;
            int edim2 = rawOutput[0][0].length;
            bufLog("Encoder output shape: [1, " + edim1 + ", " + edim2 + "], encoderLength=" + encoderLength);

            if (edim1 == ENCODER_DIM && edim2 != ENCODER_DIM) {
                // Shape [1, 1024, T/8] — transpose to [T/8, 1024]
                int timeSteps = edim2;
                encoderOutput = new float[timeSteps][ENCODER_DIM];
                for (int d = 0; d < ENCODER_DIM; d++) {
                    for (int t = 0; t < timeSteps; t++) {
                        encoderOutput[t][d] = rawOutput[0][d][t];
                    }
                }
            } else {
                // Shape [1, T/8, 1024] — already correct
                encoderOutput = rawOutput[0];
            }
        } finally {
            audioSignalTensor.close();
            lengthTensor.close();
        }

        bufLog("Encoding done in " + (System.currentTimeMillis() - stepStart)
                + "ms, " + encoderLength + " encoder frames");

        if (abortFlag.get()) return null;

        // Step 3: RNN-T Greedy Decode
        bufLog("Step 3: Decoding (" + encoderLength + " time steps)");
        stepStart = System.currentTimeMillis();

        int[] tokenIds = greedyDecode(encoderOutput, (int) encoderLength, buildConfig().maxTokensPerStep);

        bufLog("Decoding done in " + (System.currentTimeMillis() - stepStart)
                + "ms, " + tokenIds.length + " tokens");

        if (abortFlag.get()) return null;

        // Step 4: Detokenize
        return tokenizer.decode(tokenIds);
    }

    /**
     * TDT (Token-and-Duration Transducer) greedy decode loop.
     *
     * The joint network emits, at each step, BOTH a token (over the vocab logits) AND a
     * duration (over the trailing duration bins) telling us how many encoder frames to skip.
     * The duration index maps directly to a frame count — the model's durations are
     * [0,1,2,3,4], so argmax(durationLogits) == frames to advance.
     *
     * Mirrors the reference implementation in istupakov/onnx-asr
     * (_AsrWithTransducerDecoding):
     *   - the decoder state and previous-token feedback advance ONLY on a non-blank emission;
     *   - time advances by the predicted duration when it is > 0, otherwise by 1 on a blank
     *     or when the per-step token cap is hit (guarantees forward progress).
     *
     * Ignoring the duration (treating this as plain RNN-T) breaks the time alignment: the
     * model gets re-queried far too often over silence and emits spurious punctuation that
     * self-reinforces through the token feedback (the "..." artifact).
     */
    private int[] greedyDecode(float[][] encoderOutput, int encoderLength, int maxTokensPerStep) throws OrtException {
        List<Integer> emittedTokens = new ArrayList<>();

        // Initialize RNN decoder states with zeros
        float[][][] state1 = new float[2][1][DECODER_STATE_DIM];
        float[][][] state2 = new float[2][1][DECODER_STATE_DIM];

        int lastToken = ParakeetTokenizer.BLANK_ID;
        int t = 0;
        int emittedAtStep = 0;

        while (t < encoderLength) {
            if (abortFlag.get()) break;

            // Prepare encoder frame: [1, 1024, 1] — model uses [batch, features, time] layout
            float[][][] encoderFrame = new float[1][ENCODER_DIM][1];
            for (int d = 0; d < ENCODER_DIM; d++) {
                encoderFrame[0][d][0] = encoderOutput[t][d];
            }

            // Prepare inputs
            OnnxTensor encOutTensor = OnnxTensor.createTensor(env,
                    flatten3D(encoderFrame), new long[]{1, ENCODER_DIM, 1});
            OnnxTensor targetsTensor = OnnxTensor.createTensor(env,
                    IntBuffer.wrap(new int[]{lastToken}), new long[]{1, 1});
            OnnxTensor targetLenTensor = OnnxTensor.createTensor(env,
                    IntBuffer.wrap(new int[]{1}), new long[]{1});
            OnnxTensor state1Tensor = OnnxTensor.createTensor(env,
                    flatten3D(state1), new long[]{2, 1, DECODER_STATE_DIM});
            OnnxTensor state2Tensor = OnnxTensor.createTensor(env,
                    flatten3D(state2), new long[]{2, 1, DECODER_STATE_DIM});

            try (OrtSession.Result decResult = decoderSession.run(Map.of(
                    "encoder_outputs", encOutTensor,
                    "targets", targetsTensor,
                    "target_length", targetLenTensor,
                    "input_states_1", state1Tensor,
                    "input_states_2", state2Tensor
            ))) {
                // Output: outputs [1, 1, 1, vocab_size+duration_bins] (4D) — named lookup
                float[][][][] outputs = (float[][][][]) decResult.get("outputs").get().getValue();
                float[] logits = outputs[0][0][0];

                // Token = argmax over vocab logits (first VOCAB_SIZE elements, incl. blank)
                int vocabSize = Math.min(logits.length, ParakeetTokenizer.VOCAB_SIZE);
                int predictedToken = 0;
                float maxLogit = logits[0];
                for (int i = 1; i < vocabSize; i++) {
                    if (logits[i] > maxLogit) {
                        maxLogit = logits[i];
                        predictedToken = i;
                    }
                }

                // Duration = argmax over the trailing duration bins (TDT head).
                // The index maps directly to a frame skip (durations [0,1,2,3,4]).
                // Robustness: if the model emits no duration bins (non-TDT output),
                // fall back to step=0 so the blank/cap logic below still progresses.
                int durationCount = logits.length - ParakeetTokenizer.VOCAB_SIZE;
                int step = 0;
                if (durationCount > 0) {
                    float maxDur = logits[ParakeetTokenizer.VOCAB_SIZE];
                    for (int i = 1; i < durationCount; i++) {
                        float v = logits[ParakeetTokenizer.VOCAB_SIZE + i];
                        if (v > maxDur) {
                            maxDur = v;
                            step = i;
                        }
                    }
                }

                boolean isBlank = (predictedToken == ParakeetTokenizer.BLANK_ID);

                if (!isBlank) {
                    // Emit token; advance the prediction network (state + token feedback)
                    // ONLY on a non-blank emission, per the TDT/RNN-T decoding contract.
                    emittedTokens.add(predictedToken);
                    lastToken = predictedToken;
                    state1 = (float[][][]) decResult.get("output_states_1").get().getValue();
                    state2 = (float[][][]) decResult.get("output_states_2").get().getValue();
                    emittedAtStep++;
                }

                // Advance time by the predicted duration; otherwise step by 1 on a blank
                // or when the per-step token cap is reached (guarantees forward progress).
                if (step > 0) {
                    t += step;
                    emittedAtStep = 0;
                } else if (isBlank || emittedAtStep >= maxTokensPerStep) {
                    t++;
                    emittedAtStep = 0;
                }
                // else: step==0, non-blank, under cap → stay on frame t (multiple emissions)

            } finally {
                encOutTensor.close();
                targetsTensor.close();
                targetLenTensor.close();
                state1Tensor.close();
                state2Tensor.close();
            }
        }

        // Convert to int array
        int[] result = new int[emittedTokens.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = emittedTokens.get(i);
        }
        return result;
    }

    /** Flatten a 3D float array to a FloatBuffer for OnnxTensor creation. */
    private static FloatBuffer flatten3D(float[][][] arr) {
        int d0 = arr.length, d1 = arr[0].length, d2 = arr[0][0].length;
        FloatBuffer buf = FloatBuffer.allocate(d0 * d1 * d2);
        for (int i = 0; i < d0; i++) {
            for (int j = 0; j < d1; j++) {
                buf.put(arr[i][j]);
            }
        }
        buf.rewind();
        return buf;
    }

    @Override
    public void abort() {
        abortFlag.set(true);
    }

    @Override
    public boolean isModelLoaded() {
        return preprocessorSession != null && encoderSession != null
                && decoderSession != null && tokenizer.isLoaded();
    }

    /**
     * Parakeet's pipeline is monolithic (no intermediate callbacks), so direct-send
     * means the final text is injected straight into the terminal without the
     * review overlay — there is no progressive display.
     */
    @Override
    public boolean isDirectToTerminal() {
        return SettingsDialog.isDirectSendEnabled(prefs);
    }

    @Override
    public void release() {
        isDestroyed = true;
        abortFlag.set(true);
        prefs.unregisterOnSharedPreferenceChangeListener(configInvalidator);

        Thread thread = transcribeThread;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        transcribeThread = null;

        closeSessionsSafe();
    }

    private void closeSessionsSafe() {
        try { if (decoderSession != null) decoderSession.close(); } catch (Exception e) { Log.w(TAG, "decoder close", e); }
        try { if (encoderSession != null) encoderSession.close(); } catch (Exception e) { Log.w(TAG, "encoder close", e); }
        try { if (preprocessorSession != null) preprocessorSession.close(); } catch (Exception e) { Log.w(TAG, "preprocessor close", e); }
        decoderSession = null;
        encoderSession = null;
        preprocessorSession = null;
        // OrtEnvironment is a singleton, don't close it
    }

    @Override
    public String getAndClearLogs() {
        synchronized (logBuffer) {
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) {
                sb.append(line).append('\n');
            }
            logBuffer.clear();
            return sb.toString();
        }
    }
}
