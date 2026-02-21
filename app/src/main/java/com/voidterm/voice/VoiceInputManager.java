package com.voidterm.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private static final String DEFAULT_MODEL = "ggml-base.bin";
    private static final String TRANSCRIPTION_LANGUAGE = "en";
    private static final String PREFS_NAME = "voidterm_settings";
    private static final String KEY_MODEL_NAME = "whisper_model_name";

    private final TranscriptionOverlay overlay;
    private final VoiceInputCallback callback;
    private final AudioCapture audioCapture;
    private WhisperBridge whisperBridge;
    private final Handler mainHandler;

    private final Object stateLock = new Object();
    private VoiceState currentState = VoiceState.IDLE;

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
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.audioCapture = new AudioCapture();
        this.whisperBridge = new WhisperBridge();

        overlay.setTranscriptionListener(this);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String modelName = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL);

        whisperBridge.loadModel(context, modelName, new WhisperBridge.Callback() {
            @Override
            public void onSuccess(String result) {
                Log.i(TAG, "Whisper model loaded: " + result);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to load whisper model: " + error);
                // Surface model load failure to the user
                VoiceState newState;
                synchronized (stateLock) {
                    currentState = VoiceState.ERROR;
                    newState = VoiceState.ERROR;
                }
                overlay.showError("Voice model failed to load: " + error);
                dispatchStateChange(newState);
                mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            }
        });
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

        // Blocking call OUTSIDE stateLock to avoid holding lock during thread join
        float[] pcmData = audioCapture.stopRecording();

        whisperBridge.transcribe(pcmData, TRANSCRIPTION_LANGUAGE, new WhisperBridge.Callback() {
            @Override
            public void onSuccess(String text) {
                VoiceState newState = null;
                synchronized (stateLock) {
                    if (currentState != VoiceState.TRANSCRIBING) {
                        return;
                    }
                    currentState = VoiceState.SHOWING_RESULT;
                    newState = VoiceState.SHOWING_RESULT;
                }
                overlay.showTranscription(text);
                dispatchStateChange(newState);
            }

            @Override
            public void onError(String error) {
                VoiceState newState = null;
                synchronized (stateLock) {
                    if (currentState != VoiceState.TRANSCRIBING) {
                        return;
                    }
                    currentState = VoiceState.ERROR;
                    newState = VoiceState.ERROR;
                }
                overlay.showError(error);
                dispatchStateChange(newState);
                mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            }
        });
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
        whisperBridge.loadModel(context, modelFileName, new WhisperBridge.Callback() {
            @Override
            public void onSuccess(String result) {
                Log.i(TAG, "Model reloaded: " + modelFileName);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to reload model: " + error);
                VoiceState newState;
                synchronized (stateLock) {
                    currentState = VoiceState.ERROR;
                    newState = VoiceState.ERROR;
                }
                overlay.showError("Model load failed: " + error);
                dispatchStateChange(newState);
                mainHandler.postDelayed(errorDismissRunnable, ERROR_DISMISS_DELAY_MS);
            }
        });
    }

    /**
     * Release all resources. Must be called when voice input is no longer needed.
     */
    public void destroy() {
        mainHandler.removeCallbacks(volumePollRunnable);
        mainHandler.removeCallbacks(errorDismissRunnable);
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
            if (currentState != VoiceState.SHOWING_RESULT && currentState != VoiceState.EDITING) {
                return;
            }
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
