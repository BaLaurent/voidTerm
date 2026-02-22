package com.voidterm.voice;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.voidterm.R;
import com.voidterm.contracts.TranscriptionListener;
import com.voidterm.contracts.VoiceState;

/**
 * Semi-transparent overlay displayed above the terminal during voice input.
 * Shows different content depending on the current VoiceState.
 */
public class TranscriptionOverlay extends FrameLayout {

    private View overlayRoot;
    private View recordingContainer;
    private View transcribingContainer;
    private View resultContainer;
    private View volumeBar;
    private EditText transcriptionText;
    private Button sendButton;
    private Button cancelButton;
    private View loadingContainer;
    private ProgressBar loadingProgress;
    private TextView loadingPhaseText;
    private View errorContainer;
    private TextView errorText;
    private TextView statsText;
    private View errorButtons;
    private Button copyLogsButton;
    private Button dismissErrorButton;
    private TranscriptionListener listener;
    private VoiceState currentState = VoiceState.IDLE;
    private String pendingLogs;

    public TranscriptionOverlay(Context context) {
        super(context);
        init(context);
    }

    public TranscriptionOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.transcription_overlay, this, true);

        overlayRoot = findViewById(R.id.overlay_root);
        recordingContainer = findViewById(R.id.recording_container);
        transcribingContainer = findViewById(R.id.transcribing_container);
        resultContainer = findViewById(R.id.result_container);
        volumeBar = findViewById(R.id.volume_bar);
        loadingContainer = findViewById(R.id.loading_container);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingPhaseText = findViewById(R.id.loading_phase_text);
        transcriptionText = findViewById(R.id.transcription_text);
        sendButton = findViewById(R.id.btn_send);
        cancelButton = findViewById(R.id.btn_cancel);
        statsText = findViewById(R.id.stats_text);
        errorContainer = findViewById(R.id.error_container);
        errorText = findViewById(R.id.error_text);
        errorButtons = findViewById(R.id.error_buttons);
        copyLogsButton = findViewById(R.id.btn_copy_logs);
        dismissErrorButton = findViewById(R.id.btn_dismiss_error);

        // Volume bar: use scaleX for animation (avoids requestLayout)
        if (volumeBar != null) {
            volumeBar.setPivotX(0f);
        }

        sendButton.setOnClickListener(v -> {
            if (listener != null && transcriptionText != null) {
                listener.onSendRequested(transcriptionText.getText().toString());
            }
        });

        cancelButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCancelRequested();
            }
        });

        copyLogsButton.setOnClickListener(v -> {
            if (pendingLogs != null) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("VoidTerm Logs", pendingLogs));
                copyLogsButton.setText("Copied!");
            }
        });

        dismissErrorButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCancelRequested();
            }
        });

        // Tap on text in SHOWING_RESULT transitions to EDITING
        transcriptionText.setOnClickListener(v -> {
            if (currentState == VoiceState.SHOWING_RESULT && listener != null) {
                listener.onEditStarted();
            }
        });

        // Handle Enter and Escape keys in text area
        transcriptionText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (listener != null) {
                    listener.onSendRequested(transcriptionText.getText().toString());
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (listener != null) {
                    listener.onCancelRequested();
                }
                return true;
            }

            return false;
        });

        setState(VoiceState.IDLE);
    }

    /**
     * Switch the overlay to show content for the given state.
     */
    public void setState(VoiceState state) {
        currentState = state;

        loadingContainer.setVisibility(GONE);
        recordingContainer.setVisibility(GONE);
        transcribingContainer.setVisibility(GONE);
        resultContainer.setVisibility(GONE);
        errorContainer.setVisibility(GONE);
        if (statsText != null && state != VoiceState.SHOWING_RESULT && state != VoiceState.EDITING) {
            statsText.setVisibility(GONE);
        }

        switch (state) {
            case LOADING:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                loadingContainer.setVisibility(VISIBLE);
                break;

            case IDLE:
                if (overlayRoot != null) overlayRoot.setVisibility(GONE);
                pendingLogs = null;
                break;

            case RECORDING:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                recordingContainer.setVisibility(VISIBLE);
                break;

            case TRANSCRIBING:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                transcribingContainer.setVisibility(VISIBLE);
                break;

            case SHOWING_RESULT:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                resultContainer.setVisibility(VISIBLE);
                transcriptionText.setFocusable(false);
                transcriptionText.setFocusableInTouchMode(false);
                transcriptionText.setCursorVisible(false);
                break;

            case EDITING:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                resultContainer.setVisibility(VISIBLE);
                transcriptionText.setFocusable(true);
                transcriptionText.setFocusableInTouchMode(true);
                transcriptionText.setCursorVisible(true);
                transcriptionText.requestFocus();
                break;

            case ERROR:
                if (overlayRoot != null) overlayRoot.setVisibility(VISIBLE);
                errorContainer.setVisibility(VISIBLE);
                if (pendingLogs != null) {
                    errorButtons.setVisibility(VISIBLE);
                    copyLogsButton.setText("Copy Logs");
                } else {
                    errorButtons.setVisibility(GONE);
                }
                break;
        }
    }

    /**
     * Update the loading progress bar and phase text.
     */
    public void setLoadingProgress(String phase, int percent) {
        if (loadingProgress != null) {
            loadingProgress.setProgress(percent);
        }
        if (loadingPhaseText != null) {
            loadingPhaseText.setText(phase);
        }
    }

    /**
     * Show transcription text in the result area with processing stats.
     *
     * @param text Transcribed text
     * @param audioDurationSec Duration of recorded audio in seconds
     * @param processingTimeMs Time taken by whisper.cpp to transcribe in milliseconds
     */
    public void showTranscription(String text, float audioDurationSec, long processingTimeMs) {
        if (transcriptionText != null) {
            transcriptionText.setText(text);
        }
        if (statsText != null) {
            float processingSec = processingTimeMs / 1000f;
            String speedRatio = audioDurationSec > 0
                    ? String.format("%.1fx", audioDurationSec / processingSec)
                    : "";
            String stats = String.format("%.1fs audio \u00b7 %.1fs process", audioDurationSec, processingSec);
            if (!speedRatio.isEmpty()) {
                stats += " \u00b7 " + speedRatio;
            }
            statsText.setText(stats);
            statsText.setVisibility(VISIBLE);
        }
    }

    /**
     * Set the listener for user actions (send, cancel, edit).
     */
    public void setTranscriptionListener(TranscriptionListener listener) {
        this.listener = listener;
    }

    /**
     * Update the volume bar during recording.
     * Uses scaleX transform to avoid triggering requestLayout.
     *
     * @param level Volume level 0.0 to 1.0
     */
    public void setVolumeLevel(float level) {
        if (volumeBar != null) {
            volumeBar.setScaleX(Math.max(0f, Math.min(1f, level)));
        }
    }

    /**
     * Show an error message in the overlay (no logs, auto-dismiss applies).
     */
    public void showError(String message) {
        showError(message, null);
    }

    /**
     * Show an error message with optional diagnostic logs.
     * When logs are provided, "Copy Logs" and "Dismiss" buttons are shown
     * and auto-dismiss is suppressed (caller's responsibility).
     */
    public void showError(String message, String logs) {
        pendingLogs = logs;
        if (errorText != null) {
            errorText.setText(message);
        }
    }
}
