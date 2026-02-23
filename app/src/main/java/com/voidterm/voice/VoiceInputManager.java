package com.voidterm.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.voidterm.app.SettingsDialog;
import com.voidterm.contracts.TranscriptionListener;
import com.voidterm.contracts.VoiceInputCallback;
import com.voidterm.contracts.VoiceState;

/**
 * Central orchestrator for voice input. Implements the full state machine:
 *
 * [*] -> Idle
 * Idle -> Recording           : PTT pressed
 * Recording -> Transcribing   : PTT released
 * Recording -> Idle           : Double-tap (cancel)
 * Transcribing -> ShowingResult: Text ready
 * Transcribing -> Error       : Failure
 * ShowingResult -> Idle       : Enter (inject into terminal)
 * ShowingResult -> Editing    : User modifies text
 * ShowingResult -> Idle       : Escape (cancel)
 * Editing -> Idle             : Enter (inject modified text)
 * Error -> Idle               : Dismiss (auto 3s)
 *
 * Coordinates AudioCapture, WhisperBridge, and TranscriptionOverlay.
 *
 * Thread safety: stateLock protects only the currentState field.
 * External calls (overlay, callback) are dispatched OUTSIDE the lock
 * to prevent deadlocks from re-entrant calls.
 */
public class VoiceInputManager implements TranscriptionListener {

    private static final String TAG = "VoiceInputManager";
    private static final long VOLUME_POLL_INTERVAL_MS = 100;
    private static final long ERROR_DISMISS_DELAY_MS = 3000;
    private static final String DEFAULT_MODEL = SettingsDialog.DEFAULT_MODEL;

    private final TranscriptionOverlay overlay;
    private final VoiceInputCallback callback;
    private final AudioCapture audioCapture;
    private final Context appContext;
    private volatile WhisperBridge whisperBridge;
    private final Handler mainHandler;

    private final Object stateLock = new Object();
    private VoiceState currentState = VoiceState.IDLE;

    // Cached WhisperConfig — avoids 8 SharedPreferences reads per transcription
    private volatile WhisperConfig cachedConfig;
    private volatile boolean preprocessingEnabled;
    private volatile AudioConfig cachedAudioConfig;

    private static final Set<String> WHISPER_CONFIG_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_WHISPER_LANGUAGE, SettingsDialog.KEY_WHISPER_TRANSLATE,
            SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, SettingsDialog.KEY_WHISPER_TEMPERATURE,
            SettingsDialog.KEY_WHISPER_BEAM_SEARCH, SettingsDialog.KEY_WHISPER_BEAM_SIZE,
            SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH,
            SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, SettingsDialog.KEY_WHISPER_STREAMING
    ));

    private static final Set<String> AUDIO_CONFIG_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_AUDIO_GAIN, SettingsDialog.KEY_AUDIO_PRE_EMPHASIS,
            SettingsDialog.KEY_AUDIO_HP_CUTOFF, SettingsDialog.KEY_AUDIO_NORM_TARGET
    ));

    private final SharedPreferences.OnSharedPreferenceChangeListener configInvalidator =
            (prefs, key) -> {
                if (WHISPER_CONFIG_KEYS.contains(key)) {
                    cachedConfig = null;
                }
                if (AUDIO_CONFIG_KEYS.contains(key)) {
                    cachedAudioConfig = null;
                }
                if (SettingsDialog.KEY_AUDIO_PREPROCESSING.equals(key)) {
                    preprocessingEnabled = prefs.getBoolean(key, true);
                }
            };

    private final Runnable volumePollRunnable = new Runnable() {
        @Override
        public void run() {
            // Use volatile isRecording instead of stateLock for polling efficiency
            if (!audioCapture.isRecording()) {
                return;
            }
            overlay.setVolumeLevel(audioCapture.getVolumeLevel());
            mainHandler.postDelayed(this, VOLUME_POLL_INTERVAL_MS);
        }
    };

    private final Runnable errorDismissRunnable = new Runnable() {
        @Override
        public void run() {
            VoiceState newState = null;
            synchronized (stateLock) {
                if (currentState == VoiceState.ERROR) {
                    currentState = VoiceState.IDLE;
                    newState = VoiceState.IDLE;
                }
            }
            if (newState != null) {
                dispatchStateChange(newState);
            }
        }
    };

    public VoiceInputManager(Context context, TranscriptionOverlay overlay, VoiceInputCallback callback) {
        this.overlay = overlay;
        this.callback = callback;
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.audioCapture = new AudioCapture();
        this.whisperBridge = new WhisperBridge();

        overlay.setTranscriptionListener(this);

        SharedPreferences prefs = context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(configInvalidator);
        cachedConfig = readWhisperConfig(prefs);
        preprocessingEnabled = prefs.getBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, true);
        cachedAudioConfig = readAudioConfig(prefs);

        String modelName = prefs.getString(SettingsDialog.KEY_MODEL_NAME, DEFAULT_MODEL);
        boolean useGpu = prefs.getBoolean(SettingsDialog.KEY_USE_GPU, false);

        synchronized (stateLock) {
            currentState = VoiceState.LOADING;
        }
        dispatchStateChange(VoiceState.LOADING);

        whisperBridge.loadModel(context, modelName, useGpu, createLoadCallback(modelName));
    }

    /**
     * Push-to-talk button pressed. Transitions from IDLE to RECORDING.
     * Ignored if not in IDLE state.
     */
    public void onPushToTalkPressed() {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.IDLE) {
                Log.w(TAG, "PTT pressed in " + currentState + " state, ignoring");
                return;
            }
            currentState = VoiceState.RECORDING;
            newState = VoiceState.RECORDING;
        }

        if (!whisperBridge.isModelLoaded()) {
            Log.e(TAG, "PTT pressed but whisper model not loaded");
            synchronized (stateLock) {
                currentState = VoiceState.ERROR;
                newState = VoiceState.ERROR;
            }
            overlay.showError("Voice model not ready");
            dispatchStateChange(newState);
            mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            return;
        }

        dispatchStateChange(newState);

        boolean started = audioCapture.startRecording();
        if (!started) {
            // Recording failed (likely permission denied)
            synchronized (stateLock) {
                currentState = VoiceState.ERROR;
                newState = VoiceState.ERROR;
            }
            overlay.showError("Microphone permission required");
            dispatchStateChange(newState);
            mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            return;
        }

        mainHandler.post(volumePollRunnable);
    }

    /**
     * Push-to-talk button released. Transitions from RECORDING to TRANSCRIBING.
     * Stops audio capture and sends PCM data to WhisperBridge.
     */
    public void onPushToTalkReleased() {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.RECORDING) {
                Log.w(TAG, "PTT released in " + currentState + " state, ignoring");
                return;
            }
            mainHandler.removeCallbacks(volumePollRunnable);
            currentState = VoiceState.TRANSCRIBING;
            newState = VoiceState.TRANSCRIBING;
        }
        dispatchStateChange(newState);

        // Snapshot config before spawning thread (volatile read)
        WhisperConfig config = buildWhisperConfig();

        // Move stopRecording + transcribe off main thread — the main thread returns
        // immediately so the UI can show "Transcribing..." without waiting for the
        // recording thread join (~50-100ms).
        new Thread(() -> {
            try {
                float[] pcmData = audioCapture.stopRecording();
                if (preprocessingEnabled && pcmData != null && pcmData.length > 0) {
                    pcmData = AudioPreprocessor.process(pcmData, buildAudioConfig());
                }
                float audioDurationSec = pcmData != null ? pcmData.length / (float) AudioCapture.SAMPLE_RATE : 0f;
                long transcribeStart = System.currentTimeMillis();
                final int[] streamingSentLength = {0};
                whisperBridge.transcribe(pcmData, config, new WhisperBridge.Callback() {
                    @Override
                    public void onSuccess(String text) {
                        long processingTimeMs = System.currentTimeMillis() - transcribeStart;
                        VoiceState newState = null;

                        if (config.streaming) {
                            // Send any remaining delta directly to terminal
                            if (text.length() > streamingSentLength[0]) {
                                callback.onVoiceTextReady(text.substring(streamingSentLength[0]));
                            }
                            synchronized (stateLock) {
                                if (currentState != VoiceState.TRANSCRIBING) {
                                    Log.w(TAG, "Streaming success in " + currentState + ", discarding");
                                    return;
                                }
                                currentState = VoiceState.IDLE;
                                newState = VoiceState.IDLE;
                            }
                            dispatchStateChange(newState);
                        } else {
                            synchronized (stateLock) {
                                if (currentState != VoiceState.TRANSCRIBING) {
                                    Log.w(TAG, "Transcription success received in " + currentState + " state, discarding");
                                    return;
                                }
                                currentState = VoiceState.SHOWING_RESULT;
                                newState = VoiceState.SHOWING_RESULT;
                            }
                            overlay.showTranscription(text, audioDurationSec, processingTimeMs);
                            dispatchStateChange(newState);
                        }
                    }

                    @Override
                    public void onPartialResult(String accumulatedText) {
                        synchronized (stateLock) {
                            if (currentState != VoiceState.TRANSCRIBING) return;
                        }
                        // Send new text delta directly to terminal PTY
                        String trimmed = accumulatedText.trim();
                        if (trimmed.length() > streamingSentLength[0]) {
                            String delta = trimmed.substring(streamingSentLength[0]);
                            streamingSentLength[0] = trimmed.length();
                            callback.onVoiceTextReady(delta);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        VoiceState newState = null;
                        synchronized (stateLock) {
                            if (currentState != VoiceState.TRANSCRIBING) {
                                Log.w(TAG, "Transcription error received in " + currentState + " state, discarding: " + error);
                                return;
                            }
                            currentState = VoiceState.ERROR;
                            newState = VoiceState.ERROR;
                        }
                        String logs = whisperBridge.getAndClearLogs();
                        overlay.showError(error, logs);
                        dispatchStateChange(newState);
                        // No auto-dismiss when logs are available — user dismisses manually
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Pipeline thread failed", e);
                try {
                    audioCapture.stopRecording();
                } catch (Exception stopEx) {
                    Log.w(TAG, "Failed to stop recording during error recovery", stopEx);
                }
                VoiceState errorState;
                synchronized (stateLock) {
                    currentState = VoiceState.ERROR;
                    errorState = VoiceState.ERROR;
                }
                mainHandler.post(() -> {
                    overlay.showError("Voice pipeline error: " + e.getMessage());
                    dispatchStateChange(errorState);
                    mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
                });
            }
        }, "VoiceInput-Pipeline").start();
    }

    /**
     * Double-tap detected. Cancels recording and returns to IDLE.
     */
    public void onDoubleTap() {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.RECORDING) {
                Log.w(TAG, "Double-tap in " + currentState + " state, ignoring");
                return;
            }
            mainHandler.removeCallbacks(volumePollRunnable);
            currentState = VoiceState.IDLE;
            newState = VoiceState.IDLE;
        }
        // stopRecording outside lock — avoids holding lock during thread join
        audioCapture.stopRecording();
        dispatchStateChange(newState);
    }

    public VoiceState getCurrentState() {
        synchronized (stateLock) {
            return currentState;
        }
    }

    /**
     * Reload whisper model with a different file.
     * Releases the current model and loads the new one.
     */
    public void reloadModel(Context context, String modelFileName) {
        whisperBridge.release();
        whisperBridge = new WhisperBridge();

        SharedPreferences prefs = context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        boolean useGpu = prefs.getBoolean(SettingsDialog.KEY_USE_GPU, false);

        synchronized (stateLock) {
            currentState = VoiceState.LOADING;
        }
        dispatchStateChange(VoiceState.LOADING);

        whisperBridge.loadModel(context, modelFileName, useGpu, createLoadCallback(modelFileName));
    }

    private WhisperConfig buildWhisperConfig() {
        WhisperConfig config = cachedConfig;
        if (config != null) return config;

        SharedPreferences prefs = appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        config = readWhisperConfig(prefs);
        cachedConfig = config;
        return config;
    }

    private AudioConfig buildAudioConfig() {
        AudioConfig config = cachedAudioConfig;
        if (config != null) return config;

        SharedPreferences prefs = appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        config = readAudioConfig(prefs);
        cachedAudioConfig = config;
        return config;
    }

    private static AudioConfig readAudioConfig(SharedPreferences prefs) {
        return new AudioConfig(
                prefs.getFloat(SettingsDialog.KEY_AUDIO_GAIN, AudioConfig.DEFAULT.inputGain),
                prefs.getFloat(SettingsDialog.KEY_AUDIO_PRE_EMPHASIS, AudioConfig.DEFAULT.preEmphasis),
                prefs.getInt(SettingsDialog.KEY_AUDIO_HP_CUTOFF, AudioConfig.DEFAULT.hpCutoffHz),
                prefs.getFloat(SettingsDialog.KEY_AUDIO_NORM_TARGET, AudioConfig.DEFAULT.normTarget)
        );
    }

    private static WhisperConfig readWhisperConfig(SharedPreferences prefs) {
        return new WhisperConfig(
                prefs.getString(SettingsDialog.KEY_WHISPER_LANGUAGE, "en"),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, false),
                prefs.getString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, ""),
                prefs.getFloat(SettingsDialog.KEY_WHISPER_TEMPERATURE, 0.0f),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false),
                prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 5),
                prefs.getInt(SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, 0),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, false),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, false),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_STREAMING, false)
        );
    }

    private WhisperBridge.Callback createLoadCallback(String modelName) {
        return new WhisperBridge.Callback() {
            @Override
            public void onSuccess(String result) {
                Log.i(TAG, "Whisper model loaded: " + result);

                SharedPreferences prefs = appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
                DeviceProfiler.migrateIfNeeded(prefs);

                if (DeviceProfiler.needsProfiling(prefs, modelName)) {
                    overlay.setLoadingProgress("Optimizing...", 95);
                    new Thread(() -> {
                        int threadCount = CpuInfo.getPreferredThreadCount();
                        DeviceProfiler.Result profResult = DeviceProfiler.profile(whisperBridge, threadCount);
                        if (profResult != null) {
                            DeviceProfiler.applyDefaults(prefs, modelName, profResult);
                            cachedConfig = null;
                        } else {
                            Log.w(TAG, "Profiling failed, keeping existing settings");
                        }
                        mainHandler.post(() -> {
                            VoiceState newState;
                            synchronized (stateLock) {
                                currentState = VoiceState.IDLE;
                                newState = VoiceState.IDLE;
                            }
                            dispatchStateChange(newState);
                        });
                    }, "DeviceProfiler").start();
                } else {
                    Log.i(TAG, "Auto-tune cache hit for " + modelName);
                    VoiceState newState;
                    synchronized (stateLock) {
                        currentState = VoiceState.IDLE;
                        newState = VoiceState.IDLE;
                    }
                    dispatchStateChange(newState);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to load whisper model: " + error);
                VoiceState newState;
                synchronized (stateLock) {
                    currentState = VoiceState.ERROR;
                    newState = VoiceState.ERROR;
                }
                overlay.showError("Voice model failed to load: " + error);
                dispatchStateChange(newState);
                mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            }

            @Override
            public void onProgress(String phase, int percent) {
                overlay.setLoadingProgress(phase, percent);
            }
        };
    }

    /**
     * Release all resources. Must be called when voice input is no longer needed.
     */
    public void destroy() {
        mainHandler.removeCallbacks(volumePollRunnable);
        mainHandler.removeCallbacks(errorDismissRunnable);
        appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(configInvalidator);
        audioCapture.release();
        whisperBridge.release();
        Log.i(TAG, "VoiceInputManager destroyed");
    }

    // --- TranscriptionListener implementation ---

    @Override
    public void onSendRequested(String text) {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.SHOWING_RESULT && currentState != VoiceState.EDITING) {
                return;
            }
            currentState = VoiceState.IDLE;
            newState = VoiceState.IDLE;
        }
        dispatchStateChange(newState);
        callback.onVoiceTextReady(text);
    }

    @Override
    public void onCancelRequested() {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.SHOWING_RESULT
                    && currentState != VoiceState.EDITING
                    && currentState != VoiceState.ERROR) {
                return;
            }
            mainHandler.removeCallbacks(errorDismissRunnable);
            currentState = VoiceState.IDLE;
            newState = VoiceState.IDLE;
        }
        dispatchStateChange(newState);
    }

    @Override
    public void onEditStarted() {
        VoiceState newState = null;
        synchronized (stateLock) {
            if (currentState != VoiceState.SHOWING_RESULT) {
                return;
            }
            currentState = VoiceState.EDITING;
            newState = VoiceState.EDITING;
        }
        dispatchStateChange(newState);
    }

    /**
     * Dispatch state change to overlay and callback.
     * MUST be called OUTSIDE stateLock to prevent deadlocks from re-entrant calls.
     */
    private void dispatchStateChange(VoiceState newState) {
        if (newState == null) return;
        Log.d(TAG, "-> " + newState);
        overlay.setState(newState);
        callback.onVoiceStateChanged(newState);
    }
}
