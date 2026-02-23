package com.voidterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.voidterm.voice.AudioCapture;
import com.voidterm.voice.AudioPreprocessor;

/**
 * Debug dialog for comparing raw vs preprocessed audio.
 * Records a test clip, runs AudioPreprocessor, and allows playback of both versions.
 * Uses its own AudioCapture instance (no interference with the voice pipeline).
 */
public class AudioDebugDialog {

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioCapture capture;
    private AudioTrack audioTrack;
    private float[] rawAudio;
    private float[] processedAudio;

    public AudioDebugDialog(Activity activity) {
        this.activity = activity;
    }

    public void show() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // Stats display
        TextView statsView = new TextView(activity);
        statsView.setText("(record to see pipeline breakdown)");
        statsView.setTextSize(12);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsView.setPadding(0, 0, 0, 16);
        layout.addView(statsView);

        // Record button
        Button recordBtn = new Button(activity);
        recordBtn.setText("Record Test");
        recordBtn.setAllCaps(false);
        layout.addView(recordBtn);

        // Playback buttons
        Button playRawBtn = new Button(activity);
        playRawBtn.setText("\u25B6 Play Raw");
        playRawBtn.setAllCaps(false);
        playRawBtn.setEnabled(false);
        layout.addView(playRawBtn);

        Button playProcessedBtn = new Button(activity);
        playProcessedBtn.setText("\u25B6 Play Processed");
        playProcessedBtn.setAllCaps(false);
        playProcessedBtn.setEnabled(false);
        layout.addView(playProcessedBtn);

        Button stopBtn = new Button(activity);
        stopBtn.setText("\u25A0 Stop Playback");
        stopBtn.setAllCaps(false);
        stopBtn.setEnabled(false);
        layout.addView(stopBtn);

        // Record toggle logic
        recordBtn.setOnClickListener(v -> {
            if (capture != null && capture.isRecording()) {
                // Stop recording
                float[] raw = capture.stopRecording();
                capture.release();
                capture = null;
                recordBtn.setText("Record Test");

                if (raw.length == 0) {
                    statsView.setText("(no audio captured)");
                    playRawBtn.setEnabled(false);
                    playProcessedBtn.setEnabled(false);
                    return;
                }

                rawAudio = raw;
                AudioPreprocessor.PipelineResult diag = AudioPreprocessor.processWithDiagnostics(raw);
                processedAudio = diag.output;

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Raw:       %.1fs  RMS=%.4f  Peak=%.4f\n",
                        diag.raw.durationSec, diag.raw.rms, diag.raw.peakAmplitude));
                sb.append(String.format("DC Remove: RMS=%.4f\n", diag.afterDc.rms));
                sb.append(String.format("HP 80Hz:   RMS=%.4f\n", diag.afterHp.rms));
                sb.append(String.format("Emphasis:  RMS=%.4f  Peak=%.4f\n",
                        diag.afterEmphasis.rms, diag.afterEmphasis.peakAmplitude));
                sb.append(String.format("Norm:      %s\n", diag.normAction));
                sb.append(String.format("Final:     RMS=%.4f  Peak=%.4f",
                        diag.afterNormalization.rms, diag.afterNormalization.peakAmplitude));
                statsView.setText(sb.toString());

                playRawBtn.setEnabled(true);
                playProcessedBtn.setEnabled(true);
            } else {
                // Start recording
                stopPlayback();
                stopBtn.setEnabled(false);
                capture = new AudioCapture();
                if (capture.startRecording()) {
                    recordBtn.setText("Stop Recording");
                    playRawBtn.setEnabled(false);
                    playProcessedBtn.setEnabled(false);
                } else {
                    capture.release();
                    capture = null;
                    statsView.setText("(mic permission denied)");
                }
            }
        });

        playRawBtn.setOnClickListener(v -> {
            if (rawAudio != null) {
                playAudio(rawAudio, stopBtn);
            }
        });

        playProcessedBtn.setOnClickListener(v -> {
            if (processedAudio != null) {
                playAudio(processedAudio, stopBtn);
            }
        });

        stopBtn.setOnClickListener(v -> {
            stopPlayback();
            stopBtn.setEnabled(false);
        });

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Audio Preprocessing Debug")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> cleanup());
        dialog.show();
    }

    private void playAudio(float[] pcm, Button stopBtn) {
        stopPlayback();

        int bufferBytes = pcm.length * Float.BYTES;
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(AudioCapture.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        audioTrack.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        audioTrack.setNotificationMarkerPosition(pcm.length);
        audioTrack.setPlaybackPositionUpdateListener(
                new AudioTrack.OnPlaybackPositionUpdateListener() {
                    @Override
                    public void onMarkerReached(AudioTrack track) {
                        mainHandler.post(() -> stopBtn.setEnabled(false));
                    }
                    @Override
                    public void onPeriodicNotification(AudioTrack track) {}
                });

        stopBtn.setEnabled(true);
        audioTrack.play();
    }

    private void stopPlayback() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (IllegalStateException ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void cleanup() {
        stopPlayback();
        if (capture != null) {
            if (capture.isRecording()) {
                capture.stopRecording();
            }
            capture.release();
            capture = null;
        }
        rawAudio = null;
        processedAudio = null;
    }
}
