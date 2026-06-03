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
 * Coordinates AudioCapture, TranscriptionEngine, and TranscriptionOverlay.
 *
 * Thread safety: stateLock protects only the currentState field.
 * External calls (overlay, callback) are dispatched OUTSIDE the lock
 * to prevent deadlocks from re-entrant calls.
 */
public class VoiceInputManager implements TranscriptionListener {

    private static final String TAG = "VoiceInputManager";
    private static final long VOLUME_POLL_INTERVAL_MS = 100;
    private static final long ERROR_DISMISS_DELAY_MS = 3000;
    private final TranscriptionOverlay overlay;
    private final VoiceInputCallback callback;
    private final AudioCapture audioCapture;
    private final Context appContext;
    private volatile TranscriptionEngine engine;
    private final Handler mainHandler;

    private final Object stateLock = new Object();
    private VoiceState currentState = VoiceState.IDLE;

    private volatile boolean destroyed = false;

    private volatile boolean preprocessingEnabled;
    private volatile AudioConfig cachedAudioConfig;

    private static final Set<String> AUDIO_CONFIG_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_AUDIO_GAIN, SettingsDialog.KEY_AUDIO_PRE_EMPHASIS,
            SettingsDialog.KEY_AUDIO_HP_CUTOFF, SettingsDialog.KEY_AUDIO_NORM_TARGET
    ));

    private final SharedPreferences.OnSharedPreferenceChangeListener configInvalidator =
            (prefs, key) -> {
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
        this.audioCapture = new AudioCapture(new AudioFocus(this.appContext));

        overlay.setTranscriptionListener(this);

        SharedPreferences prefs = context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(configInvalidator);
        preprocessingEnabled = prefs.getBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, true);
        cachedAudioConfig = readAudioConfig(prefs);

        this.engine = createEngine(prefs);

        synchronized (stateLock) {
            currentState = VoiceState.LOADING;
        }
        dispatchStateChange(VoiceState.LOADING);

        engine.loadModel(context, createLoadCallback());
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

            // Precondition checks INSIDE stateLock — only transition to RECORDING
            // after both pass. engine.isModelLoaded() acquires contextLock
            // internally; nesting stateLock->contextLock is safe (no reverse ordering).
            if (!engine.isModelLoaded()) {
                Log.e(TAG, "PTT pressed but whisper model not loaded");
                currentState = VoiceState.ERROR;
                newState = VoiceState.ERROR;
            }

            if (newState == null) {
                boolean started = audioCapture.startRecording();
                if (!started) {
                    Log.e(TAG, "PTT pressed but microphone failed to start");
                    currentState = VoiceState.ERROR;
                    newState = VoiceState.ERROR;
                } else {
                    currentState = VoiceState.RECORDING;
                    newState = VoiceState.RECORDING;
                }
            }
        }

        if (newState == VoiceState.ERROR) {
            String errorMsg = engine.isModelLoaded()
                    ? "Microphone permission required"
                    : "Voice model not ready";
            overlay.showError(errorMsg);
            dispatchStateChange(newState);
            mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            return;
        }

        dispatchStateChange(newState);
        mainHandler.post(volumePollRunnable);
    }

    /**
     * Push-to-talk button released. Transitions from RECORDING to TRANSCRIBING.
     * Stops audio capture and sends PCM data to the transcription engine.
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

        // Snapshot delivery flags before spawning thread. Direct-send (bypass the
        // review overlay) is an engine capability — whisper additionally streams
        // text progressively, parakeet delivers the final text. Auto-submit only
        // applies when direct-send is on.
        boolean directSend = engine.isDirectToTerminal();
        boolean autoSubmit = directSend && appContext
                .getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsDialog.KEY_VOICE_AUTO_SUBMIT, false);

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

                // Skip transcription for ultra-short recordings (< 0.5s)
                // to avoid hallucinations on near-empty audio
                if (audioDurationSec < 0.5f) {
                    Log.w(TAG, "Recording too short (" + String.format("%.2f", audioDurationSec) + "s), skipping transcription");
                    VoiceState errorState;
                    synchronized (stateLock) {
                        currentState = VoiceState.ERROR;
                        errorState = VoiceState.ERROR;
                    }
                    mainHandler.post(() -> {
                        overlay.showError("Recording too short");
                        dispatchStateChange(errorState);
                        mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
                    });
                    return;
                }

                // Re-check state before expensive transcription — a cancel
                // (double-tap or onCancelRequested) may have moved us out of
                // TRANSCRIBING while we were stopping/preprocessing audio.
                synchronized (stateLock) {
                    if (currentState != VoiceState.TRANSCRIBING) {
                        Log.w(TAG, "Pipeline cancelled before transcribe() (state=" + currentState + ")");
                        return;
                    }
                }

                long transcribeStart = System.currentTimeMillis();
                final int[] streamingSentLength = {0};
                engine.transcribe(pcmData, new TranscriptionEngine.Callback() {
                    @Override
                    public void onSuccess(String text) {
                        long processingTimeMs = System.currentTimeMillis() - transcribeStart;
                        VoiceState newState = null;

                        if (directSend) {
                            // Send any remaining delta directly to terminal
                            if (text.length() > streamingSentLength[0]) {
                                callback.onVoiceTextReady(text.substring(streamingSentLength[0]));
                            }
                            // Auto-submit: press Enter once, after the final text.
                            // Deferred so the "\r" arrives as a SEPARATE terminal read
                            // rather than coalesced with the text into one chunk — a
                            // coalesced trailing "\r" is treated by TUIs as a paste
                            // newline (Shift+Enter), not a submit. Mirrors the 50ms
                            // delay MacroExecutor uses before its "\r".
                            if (autoSubmit) {
                                mainHandler.postDelayed(() -> callback.onVoiceTextReady("\r"), 50);
                            }
                            synchronized (stateLock) {
                                if (currentState != VoiceState.TRANSCRIBING) {
                                    Log.w(TAG, "Direct-send success in " + currentState + ", discarding");
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
                        String logs = engine.getAndClearLogs();
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
     * Reload the transcription engine. Releases the current engine,
     * creates a new one based on settings, and loads its model.
     */
    public void reloadModel(Context context) {
        // Reject if voice pipeline is active — release()+new is not atomic and a
        // pipeline thread could call transcribe() on the released engine.
        synchronized (stateLock) {
            if (currentState != VoiceState.IDLE && currentState != VoiceState.ERROR) {
                Log.w(TAG, "Cannot reload model in " + currentState + " state");
                return;
            }
        }

        engine.release();
        SharedPreferences prefs = context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        engine = createEngine(prefs);
        // Stale mainHandler callbacks (from old engine's loadModel or profiling) are
        // safe: they check currentState under stateLock before acting, and will see
        // LOADING (which discards stale IDLE transitions from the old callback).

        synchronized (stateLock) {
            currentState = VoiceState.LOADING;
        }
        dispatchStateChange(VoiceState.LOADING);

        engine.loadModel(context, createLoadCallback());
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

    private TranscriptionEngine createEngine(SharedPreferences prefs) {
        String type = prefs.getString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_DEFAULT);
        if (SettingsDialog.ENGINE_PARAKEET.equals(type)) {
            return new ParakeetEngine(prefs);
        }
        return new WhisperEngine(prefs);
    }

    private TranscriptionEngine.Callback createLoadCallback() {
        return new TranscriptionEngine.Callback() {
            @Override
            public void onSuccess(String result) {
                Log.i(TAG, "Model loaded: " + result);

                // DeviceProfiler is whisper-specific — only run for WhisperEngine
                if (engine instanceof WhisperEngine) {
                    WhisperEngine whisperEngine = (WhisperEngine) engine;
                    String modelName = whisperEngine.getModelName();
                    SharedPreferences prefs = appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
                    DeviceProfiler.migrateIfNeeded(prefs);

                    if (DeviceProfiler.needsProfiling(prefs, modelName)) {
                        overlay.setLoadingProgress("Optimizing...", 95);
                        new Thread(() -> {
                            int threadCount = CpuInfo.getPreferredThreadCount();
                            DeviceProfiler.Result profResult = DeviceProfiler.profile(whisperEngine.getBridge(), threadCount);
                            if (profResult != null) {
                                DeviceProfiler.applyDefaults(prefs, modelName, profResult);
                                whisperEngine.invalidateConfig();
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
                        return;
                    }
                    Log.i(TAG, "Auto-tune cache hit for " + modelName);
                }

                VoiceState newState;
                synchronized (stateLock) {
                    currentState = VoiceState.IDLE;
                    newState = VoiceState.IDLE;
                }
                dispatchStateChange(newState);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to load model: " + error);
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
        destroyed = true;
        mainHandler.removeCallbacks(volumePollRunnable);
        mainHandler.removeCallbacks(errorDismissRunnable);
        appContext.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(configInvalidator);
        audioCapture.release();
        engine.release();
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
        boolean wasTranscribing = false;
        synchronized (stateLock) {
            if (currentState == VoiceState.TRANSCRIBING) {
                // I5: Allow cancelling during transcription — especially important
                // in streaming mode where hallucinations go directly to terminal.
                wasTranscribing = true;
                currentState = VoiceState.IDLE;
                newState = VoiceState.IDLE;
            } else if (currentState == VoiceState.SHOWING_RESULT
                    || currentState == VoiceState.EDITING
                    || currentState == VoiceState.ERROR) {
                mainHandler.removeCallbacks(errorDismissRunnable);
                currentState = VoiceState.IDLE;
                newState = VoiceState.IDLE;
            } else {
                return;
            }
        }
        // When cancelling during transcription, abort native computation early
        // and let the state guard in the callback discard the stale result.
        if (wasTranscribing) {
            engine.abort();
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
        if (destroyed) {
            Log.w(TAG, "dispatchStateChange(" + newState + ") after destroy, ignoring");
            return;
        }
        Log.d(TAG, "-> " + newState);
        overlay.setState(newState);
        callback.onVoiceStateChanged(newState);
    }
}
