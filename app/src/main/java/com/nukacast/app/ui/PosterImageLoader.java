package com.nukacast.app.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.nukacast.app.net.HttpStack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

public final class PosterImageLoader {
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    public void load(final String url, final ImageView target) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
        target.setTag(url);
        Bitmap cached = cache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = download(url);
                if (bitmap == null) return;
                cache.put(url, bitmap);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    public void shutdown() { executor.shutdownNow(); }

    private static Bitmap download(String url) {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; NukaCast)")
                .build();
        try (Response response = HttpStack.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] bytes = response.body().bytes();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 360, 540);
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
