package com.voidterm.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Audio capture component for Push-to-Talk voice input.
 * Records microphone input as PCM float32 at 16kHz mono.
 * Thread-safe start/stop.
 *
 * Audio is accumulated as a list of 100ms read chunks and concatenated on stop —
 * this removes the old fixed-size pre-allocation and the hard 30s cap. Only a high
 * safety ceiling remains to bound runaway memory. Splitting long audio into
 * inference-sized windows is a separate, engine-specific concern (see AudioChunker).
 */
public class AudioCapture {

    private static final String TAG = "AudioCapture";
    public static final int SAMPLE_RATE = 16000;
    // Safety ceiling only (not a transcription cap): bounds memory if PTT is held forever.
    private static final int SAFETY_MAX_DURATION_SECONDS = 120;
    private static final int SAFETY_MAX_SAMPLES = SAMPLE_RATE * SAFETY_MAX_DURATION_SECONDS;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int READ_CHUNK_SAMPLES = SAMPLE_RATE / 10; // 100ms chunks at 16kHz = 1600
    // RMS threshold below which audio is considered silence (empirical: ~-60 dBFS)
    private static final float SILENCE_RMS_THRESHOLD = 0.001f;

    private AudioRecord audioRecord;
    // Accumulated 100ms read chunks (float32). Written only by the recording thread,
    // read in stopRecording() after the thread is joined — no concurrent access.
    private final List<float[]> recordedChunks = new ArrayList<>();
    private volatile int samplesRecorded = 0;
    private volatile boolean isRecording = false;
    private volatile boolean recordingStopped = false;
    private Thread recordingThread;
    private volatile float currentVolumeLevel = 0f;
    private final Object lock = new Object();
    private int activeAudioFormat; // actual format used (PCM_FLOAT or PCM_16BIT)

    // Pauses other apps' media for the duration of a capture (see AudioFocus).
    // Held from a successful startRecording() until stopRecording()/release().
    private final AudioFocus audioFocus;

    public AudioCapture(AudioFocus audioFocus) {
        this.audioFocus = audioFocus;
    }

    /**
     * Start recording audio on a dedicated background thread.
     * Thread-safe: guards against double-start.
     *
     * @return true if recording started successfully, false on failure
     *         (permission denied, hardware error)
     */
    public boolean startRecording() {
        synchronized (lock) {
            if (isRecording) {
                Log.w(TAG, "Already recording, ignoring startRecording()");
                return true;
            }

            // Try PCM_FLOAT first, fall back to PCM_16BIT if unsupported by HAL
            activeAudioFormat = AudioFormat.ENCODING_PCM_FLOAT;
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, activeAudioFormat);
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "PCM_FLOAT not supported, falling back to PCM_16BIT");
                activeAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
                minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, activeAudioFormat);
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid AudioRecord buffer size for PCM_16BIT: " + minBufferSize);
                    return false;
                }
            }

            // Use at least 2x minimum buffer for smoother recording
            int bytesPerSample = (activeAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) ? Float.BYTES : Short.BYTES;
            int bufferSizeBytes = Math.max(minBufferSize * 2, SAMPLE_RATE * bytesPerSample);

            try {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        activeAudioFormat,
                        bufferSizeBytes
                );
            } catch (SecurityException e) {
                Log.e(TAG, "Microphone permission not granted", e);
                return false;
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                // PCM_FLOAT initialized but HAL may still fail — try PCM_16BIT
                if (activeAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
                    Log.w(TAG, "PCM_FLOAT AudioRecord init failed, retrying with PCM_16BIT");
                    audioRecord.release();
                    audioRecord = null;
                    activeAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
                    minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, activeAudioFormat);
                    bufferSizeBytes = Math.max(minBufferSize * 2, SAMPLE_RATE * Short.BYTES);
                    try {
                        audioRecord = new AudioRecord(
                                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                SAMPLE_RATE,
                                CHANNEL_CONFIG,
                                activeAudioFormat,
                                bufferSizeBytes
                        );
                    } catch (SecurityException e) {
                        Log.e(TAG, "Microphone permission not granted", e);
                        return false;
                    }
                }
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize with both formats");
                    audioRecord.release();
                    audioRecord = null;
                    return false;
                }
            }

            Log.i(TAG, "AudioRecord using format: " +
                    (activeAudioFormat == AudioFormat.ENCODING_PCM_FLOAT ? "PCM_FLOAT" : "PCM_16BIT"));

            recordedChunks.clear();
            samplesRecorded = 0;
            currentVolumeLevel = 0f;
            recordingStopped = false;
            isRecording = true;

            audioRecord.startRecording();

            recordingThread = new Thread(this::recordLoop, "AudioCapture-Thread");
            recordingThread.start();

            // Acquire only now that the mic session is committed — never before the
            // format fallbacks above, which can still bail out with return false.
            audioFocus.acquire();

            Log.i(TAG, "Recording started");
            return true;
        }
    }

    /**
     * Recording loop running on background thread.
     * Reads audio data and computes RMS volume.
     */
    private void recordLoop() {
        if (activeAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            recordLoopFloat();
        } else {
            recordLoopShort();
        }

        if (samplesRecorded >= SAFETY_MAX_SAMPLES) {
            Log.i(TAG, "Safety max recording duration reached ("
                    + SAFETY_MAX_DURATION_SECONDS + "s)");
        }
    }

    private void recordLoopFloat() {
        float[] readBuffer = new float[READ_CHUNK_SAMPLES];

        while (isRecording && samplesRecorded < SAFETY_MAX_SAMPLES) {
            int remaining = SAFETY_MAX_SAMPLES - samplesRecorded;
            int toRead = Math.min(readBuffer.length, remaining);

            int read = audioRecord.read(readBuffer, 0, toRead, AudioRecord.READ_BLOCKING);

            if (read > 0) {
                float[] chunk = new float[read];
                System.arraycopy(readBuffer, 0, chunk, 0, read);
                recordedChunks.add(chunk);
                samplesRecorded += read;
                currentVolumeLevel = computeRms(chunk, read);
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
        }
    }

    private void recordLoopShort() {
        short[] readBuffer = new short[READ_CHUNK_SAMPLES];

        while (isRecording && samplesRecorded < SAFETY_MAX_SAMPLES) {
            int remaining = SAFETY_MAX_SAMPLES - samplesRecorded;
            int toRead = Math.min(readBuffer.length, remaining);

            int read = audioRecord.read(readBuffer, 0, toRead, AudioRecord.READ_BLOCKING);

            if (read > 0) {
                // Convert PCM_16BIT shorts to float32 for the engine
                float[] chunk = new float[read];
                for (int i = 0; i < read; i++) {
                    chunk[i] = readBuffer[i] / 32768.0f;
                }
                recordedChunks.add(chunk);
                samplesRecorded += read;
                currentVolumeLevel = computeRms(chunk, read);
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
        }
    }

    /**
     * Compute RMS (Root Mean Square) volume level from audio samples.
     * @return Normalized volume level 0.0 to 1.0
     */
    private float computeRms(float[] samples, int count) {
        return computeRms(samples, 0, count);
    }

    private float computeRms(float[] samples, int offset, int count) {
        if (count == 0) return 0f;

        float sumSquares = 0f;
        for (int i = offset; i < offset + count; i++) {
            sumSquares += samples[i] * samples[i];
        }

        float rms = (float) Math.sqrt(sumSquares / count);
        // Normalize: PCM float range is -1.0 to 1.0, RMS max is ~0.707
        // Scale to 0.0-1.0 range with some headroom
        return Math.min(1.0f, rms * 3.0f);
    }

    /**
     * Stop recording and return accumulated PCM audio data.
     * Thread-safe: guards against stop-before-start.
     * @return float array of PCM samples, or empty array if not recording
     */
    public float[] stopRecording() {
        synchronized (lock) {
            if (!isRecording) {
                Log.w(TAG, "Not recording, ignoring stopRecording()");
                return new float[0];
            }

            isRecording = false;

            // Balances the acquire() in startRecording() — other apps' media resumes.
            // Reached from every stop path (PTT release, double-tap/onPause cancel,
            // pipeline error recovery), so focus is never left dangling.
            audioFocus.abandon();

            // Stop AudioRecord BEFORE joining the thread — the record loop may be
            // blocked in READ_BLOCKING and will never see isRecording==false until
            // the HAL returns. Stopping the AudioRecord unblocks the read call.
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    recordingStopped = true;
                } catch (IllegalStateException e) {
                    Log.w(TAG, "AudioRecord stop failed", e);
                }
            }

            if (recordingThread != null) {
                try {
                    recordingThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while waiting for recording thread");
                }
                recordingThread = null;
            }

            // Concatenate the accumulated 100ms chunks into one contiguous array.
            int recorded = samplesRecorded;
            float[] result;
            if (recorded > 0 && !recordedChunks.isEmpty()) {
                float[] joined = new float[recorded];
                int offset = 0;
                for (float[] chunk : recordedChunks) {
                    int n = Math.min(chunk.length, recorded - offset);
                    if (n <= 0) break;
                    System.arraycopy(chunk, 0, joined, offset, n);
                    offset += n;
                }

                // Check if the entire recording is silence before returning
                float rms = computeRms(joined, 0, offset);
                if (rms < SILENCE_RMS_THRESHOLD) {
                    Log.w(TAG, "Recording is silence (RMS=" + String.format("%.6f", rms) +
                            " < threshold=" + SILENCE_RMS_THRESHOLD + "), returning empty");
                    result = new float[0];
                } else {
                    result = joined;
                    Log.i(TAG, "Recording stopped: " + offset + " samples (" +
                            String.format("%.1f", offset / (float) SAMPLE_RATE) + "s), RMS=" +
                            String.format("%.4f", rms));
                }
            } else {
                result = new float[0];
                Log.w(TAG, "Recording stopped: no samples captured");
            }

            // Release accumulated chunks when not recording
            recordedChunks.clear();
            currentVolumeLevel = 0f;

            return result;
        }
    }

    /**
     * Get current RMS volume level for visualization.
     * @return Volume level 0.0 to 1.0
     */
    public float getVolumeLevel() {
        return currentVolumeLevel;
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Release all audio resources. Must be called when no longer needed.
     */
    public void release() {
        synchronized (lock) {
            if (isRecording) {
                isRecording = false;
                // Stop AudioRecord before joining thread to unblock READ_BLOCKING
                if (audioRecord != null && !recordingStopped) {
                    try {
                        audioRecord.stop();
                        recordingStopped = true;
                    } catch (IllegalStateException e) {
                        // Already stopped or not initialized
                    }
                }
                if (recordingThread != null) {
                    try {
                        recordingThread.join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    recordingThread = null;
                }
            }
            if (audioRecord != null) {
                if (!recordingStopped) {
                    try {
                        audioRecord.stop();
                    } catch (IllegalStateException e) {
                        // Already stopped
                    }
                }
                recordingStopped = false;
                audioRecord.release();
                audioRecord = null;
            }
            recordedChunks.clear();
            // Safety net for the release-while-recording path (idempotent if
            // stopRecording() already abandoned).
            audioFocus.abandon();
            Log.i(TAG, "AudioCapture released");
        }
    }
}
