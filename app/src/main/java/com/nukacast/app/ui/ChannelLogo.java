package com.nukacast.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;

import java.util.Locale;

/**
 * A tile with a channel's initial, used when a playlist has no logo for it.
 *
 * <p>Half of a public IPTV list has no {@code tvg-logo}, and a row of names with an empty square in
 * front of each looks broken; a letter on a coloured tile is what viewers already recognise from the
 * web console. The colour is derived from the name so a channel keeps the same tile everywhere.
 */
public final class ChannelLogo {

    private static final int SIZE = 96;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(64);

    private ChannelLogo() {
    }

    /** The first character worth showing for a channel name, skipping decorations. */
    public static String initial(String name) {
        if (name == null) return "?";
        String text = name.trim();
        // "CCTV-13 (1080p)" → "C", "4K 超清" → "4", "· 北京卫视" → "北".
        while (text.length() > 0) {
            char first = text.charAt(0);
            if (Character.isLetterOrDigit(first)) return String.valueOf(first).toUpperCase(Locale.US);
            text = text.substring(1).trim();
        }
        return "?";
    }

    /** A square tile with the channel's initial, cached per letter. */
    public static Bitmap tile(String name) {
        String initial = initial(name);
        Bitmap cached = CACHE.get(initial);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(tint(initial));
        canvas.drawRoundRect(new RectF(0, 0, SIZE, SIZE), SIZE * 0.22f, SIZE * 0.22f, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(SIZE * 0.52f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        // Centre on the cap height rather than the baseline, or the letter sits low in the tile.
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = SIZE / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(initial, SIZE / 2f, baseline, paint);
        CACHE.put(initial, bitmap);
        return bitmap;
    }

    /** A muted colour picked from the initial, so tiles vary without competing with the artwork. */
    private static int tint(String initial) {
        int[] palette = {
                0xFF3F5A78, 0xFF4A5A3F, 0xFF6B4A3F, 0xFF4A3F5A, 0xFF3F5F5A, 0xFF5A4A3F,
        };
        return palette[Math.abs(initial.hashCode()) % palette.length];
    }
}
