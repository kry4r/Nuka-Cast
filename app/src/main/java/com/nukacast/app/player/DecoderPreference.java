package com.nukacast.app.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which video decoder to use, and remembers when the hardware one does not work.
 *
 * <p>The SHARP TV this app runs on has an AVC hardware decoder ({@code OMX.hisi.video.decoder.avc})
 * that accepts a stream and then produces no output — the same fault seen on the AirPlay path. In
 * the player that surfaced as
 *
 * <pre>ERROR_CODE_DECODING_FAILED … format_supported=NO_EXCEEDS_CAPABILITIES
 * java.lang.IllegalStateException at MediaCodec.dequeueInputBuffer</pre>
 *
 * followed by the app returning to the home screen, i.e. "播放后直接闪退到主页面". ExoPlayer's built-in
 * decoder fallback only covers codec *initialisation* failures, so a decoder that dies mid-stream is
 * handled here: the failure is recorded once, and every later playback starts with the software
 * decoder instead. The flag is persisted because the fault is a property of the device.
 */
public final class DecoderPreference {
    private static final String PREFS = "nukacast-player";
    private static final String KEY_FORCE_SOFTWARE = "force_software_decoder";

    private static volatile Boolean cached;

    private DecoderPreference() {}

    /** True when hardware decoding is known to fail on this device. */
    public static boolean prefersSoftware(Context context) {
        Boolean value = cached;
        if (value != null) return value;
        boolean stored = false;
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            stored = prefs.getBoolean(KEY_FORCE_SOFTWARE, false);
        } catch (Throwable ignored) {
            stored = false;
        }
        cached = stored;
        return stored;
    }

    public static void preferSoftware(Context context) {
        cached = true;
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FORCE_SOFTWARE, true).apply();
        } catch (Throwable ignored) {
            // Best effort: the in-memory flag still covers this session.
        }
    }

    /** Clears the preference, used by the settings page when a device gets fixed. */
    public static void clear(Context context) {
        cached = false;
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_FORCE_SOFTWARE).apply();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    /** Codec selection order for this device: software first when hardware is known to fail. */
    public static MediaCodecSelector selector(final Context context) {
        return new MediaCodecSelector() {
            @Override public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                                   boolean requiresSecureDecoder,
                                                                   boolean requiresTunnelingDecoder)
                    throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                if (!prefersSoftware(context) || decoders.size() < 2) return decoders;
                List<MediaCodecInfo> ordered = new ArrayList<MediaCodecInfo>(decoders.size());
                for (MediaCodecInfo info : decoders) {
                    if (isSoftware(info)) ordered.add(info);
                }
                for (MediaCodecInfo info : decoders) {
                    if (!isSoftware(info)) ordered.add(info);
                }
                return ordered.isEmpty() ? decoders : ordered;
            }
        };
    }

    /** The Android convention for a software codec is an {@code OMX.google.} name. */
    static boolean isSoftware(MediaCodecInfo info) {
        return info != null && isSoftwareName(info.name);
    }

    static boolean isSoftwareName(String name) {
        return name != null && name.startsWith("OMX.google.");
    }
}
