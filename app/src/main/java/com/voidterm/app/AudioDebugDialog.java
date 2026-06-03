package com.voidterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import android.content.Context;
import android.content.SharedPreferences;

import com.voidterm.voice.AudioCapture;
import com.voidterm.voice.AudioConfig;
import com.voidterm.voice.AudioFocus;
import com.voidterm.voice.AudioPreprocessor;

/**
 * Debug dialog for comparing raw vs preprocessed audio.
 * Records a test clip, runs AudioPreprocessor, and allows playback of both versions.
 * Includes tuning spinners that reprocess audio in real-time and persist to SharedPreferences.
 */
public class AudioDebugDialog {

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioCapture capture;
    private AudioTrack audioTrack;
    private float[] rawAudio;
    private float[] processedAudio;

    // Tuning spinners (kept as fields for reprocess() access)
    private Spinner gainSpinner;
    private Spinner emphasisSpinner;
    private Spinner hpCutoffSpinner;
    private Spinner normTargetSpinner;
    private TextView statsView;

    public AudioDebugDialog(Activity activity) {
        this.activity = activity;
    }

    public void show() {
        SharedPreferences prefs = activity.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // Stats display
        statsView = new TextView(activity);
        statsView.setText("(record to see pipeline breakdown)");
        statsView.setTextSize(12);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsView.setPadding(0, 0, 0, 16);
        layout.addView(statsView);

        // Tuning spinners
        gainSpinner = addTuningSpinner(layout, "Gain",
                SettingsDialog.GAIN_LABELS,
                findFloatIndex(SettingsDialog.GAIN_VALUES,
                        prefs.getFloat(SettingsDialog.KEY_AUDIO_GAIN, AudioConfig.DEFAULT.inputGain)));

        emphasisSpinner = addTuningSpinner(layout, "Pre-emphasis",
                SettingsDialog.PRE_EMPHASIS_LABELS,
                findFloatIndex(SettingsDialog.PRE_EMPHASIS_VALUES,
                        prefs.getFloat(SettingsDialog.KEY_AUDIO_PRE_EMPHASIS, AudioConfig.DEFAULT.preEmphasis)));

        hpCutoffSpinner = addTuningSpinner(layout, "HP Cutoff",
                SettingsDialog.HP_CUTOFF_LABELS,
                findIntIndex(SettingsDialog.HP_CUTOFF_VALUES,
                        prefs.getInt(SettingsDialog.KEY_AUDIO_HP_CUTOFF, AudioConfig.DEFAULT.hpCutoffHz)));

        normTargetSpinner = addTuningSpinner(layout, "Norm Target",
                SettingsDialog.NORM_TARGET_LABELS,
                findFloatIndex(SettingsDialog.NORM_TARGET_VALUES,
                        prefs.getFloat(SettingsDialog.KEY_AUDIO_NORM_TARGET, AudioConfig.DEFAULT.normTarget)));

        setSpinnersEnabled(false);

        // Spinner change listeners (persist + reprocess)
        gainSpinner.setOnItemSelectedListener(new SpinnerChangeListener() {
            @Override
            public void onChanged(int pos) {
                prefs.edit().putFloat(SettingsDialog.KEY_AUDIO_GAIN,
                        SettingsDialog.GAIN_VALUES[pos]).apply();
                reprocess();
            }
        });
        emphasisSpinner.setOnItemSelectedListener(new SpinnerChangeListener() {
            @Override
            public void onChanged(int pos) {
                prefs.edit().putFloat(SettingsDialog.KEY_AUDIO_PRE_EMPHASIS,
                        SettingsDialog.PRE_EMPHASIS_VALUES[pos]).apply();
                reprocess();
            }
        });
        hpCutoffSpinner.setOnItemSelectedListener(new SpinnerChangeListener() {
            @Override
            public void onChanged(int pos) {
                prefs.edit().putInt(SettingsDialog.KEY_AUDIO_HP_CUTOFF,
                        SettingsDialog.HP_CUTOFF_VALUES[pos]).apply();
                reprocess();
            }
        });
        normTargetSpinner.setOnItemSelectedListener(new SpinnerChangeListener() {
            @Override
            public void onChanged(int pos) {
                prefs.edit().putFloat(SettingsDialog.KEY_AUDIO_NORM_TARGET,
                        SettingsDialog.NORM_TARGET_VALUES[pos]).apply();
                reprocess();
            }
        });

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
                    setSpinnersEnabled(false);
                    return;
                }

                rawAudio = raw;
                reprocess();

                setSpinnersEnabled(true);
                playRawBtn.setEnabled(true);
                playProcessedBtn.setEnabled(true);
            } else {
                // Start recording
                stopPlayback();
                stopBtn.setEnabled(false);
                capture = new AudioCapture(new AudioFocus(activity));
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

    private void reprocess() {
        if (rawAudio == null) return;

        AudioConfig config = new AudioConfig(
                SettingsDialog.GAIN_VALUES[gainSpinner.getSelectedItemPosition()],
                SettingsDialog.PRE_EMPHASIS_VALUES[emphasisSpinner.getSelectedItemPosition()],
                SettingsDialog.HP_CUTOFF_VALUES[hpCutoffSpinner.getSelectedItemPosition()],
                SettingsDialog.NORM_TARGET_VALUES[normTargetSpinner.getSelectedItemPosition()]);

        AudioPreprocessor.PipelineResult diag = AudioPreprocessor.processWithDiagnostics(rawAudio, config);
        processedAudio = diag.output;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Raw:       %.1fs  RMS=%.4f  Peak=%.4f\n",
                diag.raw.durationSec, diag.raw.rms, diag.raw.peakAmplitude));
        sb.append(String.format("DC Remove: RMS=%.4f\n", diag.afterDc.rms));
        sb.append(String.format("HP %dHz:   RMS=%.4f\n", config.hpCutoffHz, diag.afterHp.rms));
        if (config.preEmphasis > 0f) {
            sb.append(String.format("Emphasis:  RMS=%.4f  Peak=%.4f\n",
                    diag.afterEmphasis.rms, diag.afterEmphasis.peakAmplitude));
        }
        sb.append(String.format("Norm:      %s\n", diag.normAction));
        sb.append(String.format("Norm out:  RMS=%.4f  Peak=%.4f\n",
                diag.afterNormalization.rms, diag.afterNormalization.peakAmplitude));
        if (config.inputGain != 1.0f) {
            sb.append(String.format("Gain %.1fx: RMS=%.4f  Peak=%.4f\n",
                    config.inputGain, diag.afterGain.rms, diag.afterGain.peakAmplitude));
        }
        sb.append(String.format("Final:     RMS=%.4f  Peak=%.4f",
                diag.afterGain.rms, diag.afterGain.peakAmplitude));
        statsView.setText(sb.toString());
    }

    private Spinner addTuningSpinner(LinearLayout parent, String label, String[] items, int selectedIndex) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setMinWidth(200);
        row.addView(tv);

        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedIndex);
        row.addView(spinner);

        parent.addView(row);
        return spinner;
    }

    private void setSpinnersEnabled(boolean enabled) {
        gainSpinner.setEnabled(enabled);
        emphasisSpinner.setEnabled(enabled);
        hpCutoffSpinner.setEnabled(enabled);
        normTargetSpinner.setEnabled(enabled);
    }

    private static int findFloatIndex(float[] values, float target) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - target) < 0.01f) return i;
        }
        return 0;
    }

    private static int findIntIndex(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    /**
     * Fires onChanged only after initial layout (skips the first auto-trigger).
     */
    private abstract static class SpinnerChangeListener implements AdapterView.OnItemSelectedListener {
        private boolean initialized;

        abstract void onChanged(int position);

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!initialized) {
                initialized = true;
                return;
            }
            onChanged(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
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
