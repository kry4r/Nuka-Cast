package com.nukacast.app.player;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import com.nukacast.app.net.HttpStack;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ExoPlayer data source backed by the app's own OkHttp client.
 *
 * <p>Two problems made this necessary on Android 4.4:
 *
 * <ul>
 *   <li><b>TLS.</b> {@code DefaultHttpDataSource} uses the platform's {@code HttpsURLConnection},
 *       which on API 19 negotiates ancient protocols and ciphers. Media hosts answered
 *       {@code SSLProtocolException: SSL handshake aborted … sslv3 alert handshake failure}. The
 *       app's OkHttp client already carries the Conscrypt TLS 1.2 stack and the platform+bundled
 *       trust managers, so playback and the API now speak the same TLS.
 *   <li><b>Version safety.</b> The official {@code extension-okhttp} is pinned to ExoPlayer 2.14
 *       while core is 2.18.5, and its error path called a constructor that no longer exists —
 *       a 403 from a CDN raised {@link NoSuchMethodError} inside a loader thread and killed the
 *       process. This class is compiled against the exact ExoPlayer version in the build.
 * </ul>
 */
public final class OkHttpDataSource implements DataSource {
    public static final class Factory implements DataSource.Factory {
        private final OkHttpClient client;
        private final String userAgent;
        private final Map<String, String> defaultHeaders;
        private TransferListener transferListener;

        public Factory(OkHttpClient client, String userAgent, Map<String, String> defaultHeaders) {
            this.client = client;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.defaultHeaders = defaultHeaders == null
                    ? Collections.<String, String>emptyMap()
                    : new LinkedHashMap<String, String>(defaultHeaders);
        }

        public Factory setTransferListener(TransferListener listener) {
            this.transferListener = listener;
            return this;
        }

        @Override public DataSource createDataSource() {
            OkHttpDataSource created = new OkHttpDataSource(client, userAgent, defaultHeaders);
            if (transferListener != null) created.addTransferListener(transferListener);
            return created;
        }
    }

    private final OkHttpClient client;
    private final String userAgent;
    private final Map<String, String> defaultHeaders;
    private final List<TransferListener> listeners = new ArrayList<TransferListener>();

    private Response response;
    private InputStream stream;
    private Uri uri;
    private long bytesRemaining;
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    public OkHttpDataSource(OkHttpClient client, String userAgent, Map<String, String> defaultHeaders) {
        this.client = client;
        this.userAgent = userAgent == null ? "" : userAgent;
        this.defaultHeaders = defaultHeaders == null
                ? Collections.<String, String>emptyMap() : defaultHeaders;
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        close();
        uri = dataSpec.uri;
        Request.Builder request = new Request.Builder().url(dataSpec.uri.toString());
        if (!userAgent.isEmpty()) request.header("User-Agent", userAgent);
        for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        for (Map.Entry<String, String> header : dataSpec.httpRequestHeaders.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (dataSpec.position != 0 || dataSpec.length != C.LENGTH_UNSET) {
            request.header("Range", rangeHeader(dataSpec));
        }
        Response opened = client.newCall(request.build()).execute();
        response = opened;
        responseHeaders = toHeaderMap(opened.headers());
        for (TransferListener listener : listeners) {
            listener.onTransferStart(this, dataSpec, false);
        }
        if (opened.code() == 416) {
            // A range past the end is not an error: it is the end of the resource.
            opened.close();
            response = null;
            bytesRemaining = 0L;
            return C.RESULT_END_OF_INPUT;
        }
        if (!opened.isSuccessful()) {
            int code = opened.code();
            opened.close();
            response = null;
            throw new HttpDataSource.InvalidResponseCodeException(code, responseHeaders, dataSpec);
        }
        ResponseBody body = opened.body();
        if (body == null) {
            opened.close();
            response = null;
            throw new HttpDataSource.HttpDataSourceException("响应没有内容",
                    dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
        stream = body.byteStream();
        long contentLength = body.contentLength();
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else if (contentLength >= 0) {
            long available = contentLength - (dataSpec.position == 0 ? 0L : 0L);
            bytesRemaining = available;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }
        return 0L;
    }

    private static String rangeHeader(DataSpec dataSpec) {
        if (dataSpec.length == C.LENGTH_UNSET) {
            return "bytes=" + dataSpec.position + "-";
        }
        return "bytes=" + dataSpec.position + "-" + (dataSpec.position + dataSpec.length - 1);
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        int readLength = length;
        if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining < readLength) {
            readLength = (int) bytesRemaining;
        }
        int read = stream.read(buffer, offset, readLength);
        if (read == -1) {
            bytesRemaining = 0L;
            return C.RESULT_END_OF_INPUT;
        }
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
        for (TransferListener listener : listeners) listener.onBytesTransferred(this, null, false, read);
        return read;
    }

    @Override public Uri getUri() {
        return uri;
    }

    @Override public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    @Override public void close() throws IOException {
        try {
            if (stream != null) stream.close();
        } catch (IOException ignored) {
            // The transfer is over; a failing close must not abort playback.
        } finally {
            stream = null;
            if (response != null) response.close();
            response = null;
            bytesRemaining = 0L;
        }
    }

    private static Map<String, List<String>> toHeaderMap(Headers headers) {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            List<String> values = map.get(name);
            if (values == null) {
                values = new ArrayList<String>(2);
                map.put(name, values);
            }
            values.add(headers.value(i));
        }
        return map;
    }

    static OkHttpClient client() {
        return HttpStack.client();
    }
}
