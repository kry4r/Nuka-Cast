package com.nukacast.app.net;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Configs that ship inside the APK.
 *
 * <p>A bundled config is the answer to "the box only has 1 GB and the public repos hand it 139
 * sites, most of them unloadable plugin sites": {@code asset://sources/starter.json} resolves to a
 * short list of measured CMS sites with no plugin JARs, so it works offline, starts no JS runtimes
 * and does not depend on a repository staying up.
 */
public final class BundledAssets {
    public static final String SCHEME = "asset://";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private BundledAssets() {}

    public static boolean isBundled(String url) {
        return url != null && url.toLowerCase(Locale.US).startsWith(SCHEME);
    }

    /** The asset path behind an {@code asset://} URL, e.g. {@code sources/starter.json}. */
    public static String pathOf(String url) {
        if (!isBundled(url)) return "";
        String path = url.substring(SCHEME.length());
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    public static boolean exists(Context context, String url) {
        if (context == null || !isBundled(url)) return false;
        InputStream stream = null;
        try {
            stream = context.getAssets().open(pathOf(url));
            return true;
        } catch (IOException missing) {
            return false;
        } finally {
            closeQuietly(stream);
        }
    }

    public static byte[] read(Context context, String url) throws IOException {
        if (context == null) throw new IOException("资源不可用");
        if (!isBundled(url)) throw new IOException("不是内置配置：" + url);
        InputStream stream = null;
        try {
            stream = context.getAssets().open(pathOf(url));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_BYTES) break;
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IOException("内置配置读取失败：" + pathOf(url), error);
        } finally {
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }
}
