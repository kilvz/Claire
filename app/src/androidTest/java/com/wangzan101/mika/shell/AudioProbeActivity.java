package com.wangzan101.mika.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;

/**
 * Probe page that is producing sound: after opening it loops a silent sound forever (the player state is "playing", but nothing is audible), and stops when closed.
 * Everything else is the same as {@link ShellProbeActivity} (reports the display and task to the test). Used to test the "apps that are producing sound are closed by default when the task ends" behavior.
 */
public class AudioProbeActivity extends ShellProbeActivity {
    private static final int RATE = 8000;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        short[] silence = new short[RATE / 2];
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.length * 2)
                .build();
        track.write(silence, 0, silence.length);
        track.setLoopPoints(0, silence.length, -1);
        track.play();
    }

    @Override
    protected void onDestroy() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // Closed before playback started
            }
            track.release();
            track = null;
        }
        super.onDestroy();
    }
}
