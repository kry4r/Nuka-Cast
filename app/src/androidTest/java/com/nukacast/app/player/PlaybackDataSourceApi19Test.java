package com.nukacast.app.player;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The playback data source must work on API 19, including its error path.
 *
 * <p>This test exists because of a real crash: {@code extension-okhttp} is pinned to ExoPlayer
 * 2.14.2 (the last release supporting API 19) while core is 2.18.5, and its error path calls a
 * constructor that no longer exists. A CDN answering 403 therefore raised
 * {@code java.lang.NoSuchMethodError} from an ExoPlayer loader thread — an uncaught error that
 * killed the process a few seconds into playback. The assertions below fail loudly if that
 * combination ever comes back.
 */
@RunWith(AndroidJUnit4.class)
public final class PlaybackDataSourceApi19Test {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void createsDataSource() {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        assertNotNull(factory.createDataSource());
    }

    @Test
    public void serverErrorRaisesTypedExceptionNotNoSuchMethodError() throws Exception {
        ServerSocket server = new ServerSocket(0, 1);
        Thread responder = new Thread(new Runnable() {
            @Override public void run() {
                serveOnce(server);
            }
        }, "test-http-403");
        responder.setDaemon(true);
        responder.start();

        DefaultHttpDataSource source = new DefaultHttpDataSource.Factory().createDataSource();
        try {
            source.open(new DataSpec(android.net.Uri.parse(
                    "http://127.0.0.1:" + server.getLocalPort() + "/nope.m3u8")));
            fail("403 should not open successfully");
        } catch (HttpDataSource.InvalidResponseCodeException expected) {
            assertTrue(expected.getMessage(), expected.responseCode == 403);
        } catch (NoSuchMethodError regression) {
            fail("播放器数据源的错误路径又出现了 NoSuchMethodError：" + regression.getMessage());
        } finally {
            try {
                source.close();
            } catch (Exception ignored) {
                // Closing a source that never opened is allowed to fail.
            }
            try {
                server.close();
            } catch (Exception ignored) {
                // Nothing to do.
            }
            responder.interrupt();
        }
    }

    private static void serveOnce(ServerSocket server) {
        try {
            Socket socket = server.accept();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), UTF_8));
                while (true) {
                    String line = reader.readLine();
                    if (line == null || line.isEmpty()) break;
                }
                String body = "denied";
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 403 Forbidden\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.length() + "\r\n"
                        + "Connection: close\r\n\r\n" + body).getBytes(UTF_8));
                out.flush();
            } finally {
                socket.close();
            }
        } catch (Exception ignored) {
            // The client may have gone away; the test asserts on its own result.
        }
    }
}
