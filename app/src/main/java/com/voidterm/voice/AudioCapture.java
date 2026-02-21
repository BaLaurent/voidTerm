package com.voidterm.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Audio capture component for Push-to-Talk voice input.
 * Records microphone input as PCM float32 at 16kHz mono.
 * Thread-safe start/stop with 30-second maximum duration.
 */
public class AudioCapture {

    private static final String TAG = "AudioCapture";
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_DURATION_SECONDS = 30;
    private static final int MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS; // 480,000
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int READ_CHUNK_SAMPLES = SAMPLE_RATE / 10; // 100ms chunks at 16kHz = 1600

    private AudioRecord audioRecord;
    private float[] buffer; // allocated per recording, null when idle
    private volatile int samplesRecorded = 0;
    private volatile boolean isRecording = false;
    private Thread recordingThread;
    private volatile float currentVolumeLevel = 0f;
    private final Object lock = new Object();

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

            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid AudioRecord buffer size: " + minBufferSize);
                return false;
            }

            // Use at least 2x minimum buffer for smoother recording
            int bufferSize = Math.max(minBufferSize * 2, SAMPLE_RATE); // at least 1 second

            try {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize * Float.BYTES
                );
            } catch (SecurityException e) {
                Log.e(TAG, "Microphone permission not granted", e);
                return false;
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                audioRecord.release();
                audioRecord = null;
                return false;
            }

            buffer = new float[MAX_SAMPLES];
            samplesRecorded = 0;
            currentVolumeLevel = 0f;
            isRecording = true;

            audioRecord.startRecording();

            recordingThread = new Thread(this::recordLoop, "AudioCapture-Thread");
            recordingThread.start();

            Log.i(TAG, "Recording started");
            return true;
        }
    }

    /**
     * Recording loop running on background thread.
     * Reads audio data and computes RMS volume.
     */
    private void recordLoop() {
        float[] readBuffer = new float[READ_CHUNK_SAMPLES];

        while (isRecording && samplesRecorded < MAX_SAMPLES) {
            int remaining = MAX_SAMPLES - samplesRecorded;
            int toRead = Math.min(readBuffer.length, remaining);

            int read = audioRecord.read(readBuffer, 0, toRead, AudioRecord.READ_BLOCKING);

            if (read > 0) {
                System.arraycopy(readBuffer, 0, buffer, samplesRecorded, read);
                samplesRecorded += read;
                currentVolumeLevel = computeRms(readBuffer, read);
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
        }

        if (samplesRecorded >= MAX_SAMPLES) {
            Log.i(TAG, "Max recording duration reached (30s)");
        }
    }

    /**
     * Compute RMS (Root Mean Square) volume level from audio samples.
     * @return Normalized volume level 0.0 to 1.0
     */
    private float computeRms(float[] samples, int count) {
        if (count == 0) return 0f;

        float sumSquares = 0f;
        for (int i = 0; i < count; i++) {
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

            if (recordingThread != null) {
                try {
                    recordingThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while waiting for recording thread");
                }
                recordingThread = null;
            }

            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "AudioRecord stop failed", e);
                }
            }

            // Copy only the recorded portion
            int recorded = samplesRecorded;
            float[] result = new float[recorded];
            if (recorded > 0 && buffer != null) {
                System.arraycopy(buffer, 0, result, 0, recorded);
            }

            // Release the large buffer when not recording
            buffer = null;
            currentVolumeLevel = 0f;

            Log.i(TAG, "Recording stopped: " + recorded + " samples (" +
                    String.format("%.1f", recorded / (float) SAMPLE_RATE) + "s)");

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
                try {
                    audioRecord.stop();
                } catch (IllegalStateException e) {
                    // Already stopped
                }
                audioRecord.release();
                audioRecord = null;
            }
            buffer = null;
            Log.i(TAG, "AudioCapture released");
        }
    }
}
