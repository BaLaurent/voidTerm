package com.voidterm.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Log;

/**
 * Acquires transient audio focus so other apps' media pauses while voice is
 * being captured, and resumes once focus is abandoned.
 *
 * AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: peer media apps PAUSE (not duck) and the
 * system suppresses notification sounds — both desirable while dictating. The
 * "transient" hint tells those apps to resume their playback when we abandon.
 *
 * acquire()/abandon() are idempotent: a duplicate acquire is a no-op, and an
 * abandon with nothing held is a no-op. This keeps the recording lifecycle
 * simple even though stop is reached from several paths (PTT release, cancel,
 * background, error recovery) — the only fatal failure here is a MISSED abandon
 * (music paused forever), which balanced acquire/abandon on the single
 * start/stop pair prevents.
 */
public class AudioFocus {

    private static final String TAG = "AudioFocus";

    private final AudioManager audioManager;
    private final AudioFocusRequest request;
    private boolean held = false;

    public AudioFocus(Context context) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        this.request = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                // Transient capture: we don't pause/duck ourselves on focus loss,
                // so no reaction is needed — but a listener keeps the request well-formed.
                .setOnAudioFocusChangeListener(focusChange -> { })
                .build();
    }

    /** Pause other apps' media by acquiring transient exclusive focus. Idempotent. */
    public synchronized void acquire() {
        if (held || audioManager == null) return;
        int result = audioManager.requestAudioFocus(request);
        held = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        if (!held) {
            Log.w(TAG, "Audio focus not granted (" + result + "); other media may keep playing");
        }
    }

    /** Let other apps' media resume by abandoning focus. Idempotent. */
    public synchronized void abandon() {
        if (!held || audioManager == null) return;
        audioManager.abandonAudioFocusRequest(request);
        held = false;
    }
}
