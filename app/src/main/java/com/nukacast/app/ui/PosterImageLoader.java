package com.nukacast.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ResponseBodies;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

public final class PosterImageLoader {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 12000;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    public void load(final String url, final ImageView target) {
        load(url, target, null);
    }

    /**
     * Loads a poster, optionally with the page that owns it as Referer.
     *
     * <p>Many CMS hosts refuse image requests without one (hotlink protection), which shows up as a
     * grid of empty tiles even though the API returned picture URLs. A failed load also notifies
     * the caller so the card can draw a placeholder instead of leaving a blank rectangle.
     */
    public void load(final String url, final ImageView target, final String referer) {
        load(url, target, referer, 360, 540);
    }

    /**
     * Loads a small icon (a channel logo), decoded to icon size rather than poster size.
     *
     * <p>A live page shows up to 120 channels at once and their logos are tiny; decoding each of them
     * at 360x540, as a poster would be, is what turns a channel list into a memory spike on a 1.5GB
     * device.
     */
    public void loadIcon(final String url, final ImageView target) {
        load(url, target, null, 96, 96);
    }

    /**
     * Loads an image into an image view.
     *
     * @param targetWidth height the bitmap is needed at; the decoder samples down towards it
     */
    public void load(final String url, final ImageView target, final String referer,
                     final int targetWidth, final int targetHeight) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            if (fallback != null) fallback.onFailed(url, target);
            return;
        }
        final String key = "i" + targetWidth + "x" + targetHeight + "|" + url;
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = download(url, referer, targetWidth, targetHeight);
                if (bitmap == null) {
                    if (fallback != null) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (key.equals(target.getTag())) fallback.onFailed(url, target);
                            }
                        });
                    }
                    return;
                }
                cache.put(key, bitmap);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(target.getTag())) target.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    /** Notified when a poster could not be loaded, so cards can show a placeholder. */
    public interface Fallback {
        void onFailed(String url, ImageView target);
    }

    private Fallback fallback;

    public void setFallback(Fallback value) { this.fallback = value; }

    public void shutdown() { executor.shutdownNow(); }

    private static Bitmap download(String url, String referer, int targetWidth, int targetHeight) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; NukaCast)");
        if (referer != null && !referer.isEmpty()) builder.header("Referer", referer);
        Request request = builder.build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] bytes = ResponseBodies.bytes(response.body(), MAX_IMAGE_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > MAX_IMAGE_DIMENSION
                    || bounds.outHeight > MAX_IMAGE_DIMENSION
                    || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }
}
