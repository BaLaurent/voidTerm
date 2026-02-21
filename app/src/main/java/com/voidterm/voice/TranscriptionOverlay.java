package com.voidterm.voice;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private TextView errorText;
    private TranscriptionListener listener;
    private VoiceState currentState = VoiceState.IDLE;

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
        transcriptionText = findViewById(R.id.transcription_text);
        sendButton = findViewById(R.id.btn_send);
        cancelButton = findViewById(R.id.btn_cancel);
        errorText = findViewById(R.id.error_text);

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

        recordingContainer.setVisibility(GONE);
        transcribingContainer.setVisibility(GONE);
        resultContainer.setVisibility(GONE);
        errorText.setVisibility(GONE);

        switch (state) {
            case IDLE:
                if (overlayRoot != null) overlayRoot.setVisibility(GONE);
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
                errorText.setVisibility(VISIBLE);
                break;
        }
    }

    /**
     * Show transcription text in the result area.
     */
    public void showTranscription(String text) {
        if (transcriptionText != null) {
            transcriptionText.setText(text);
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
     * Show an error message in the overlay.
     */
    public void showError(String message) {
        if (errorText != null) {
            errorText.setText(message);
        }
    }
}
