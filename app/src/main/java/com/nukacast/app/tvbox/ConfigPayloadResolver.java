package com.nukacast.app.tvbox;

import com.nukacast.app.net.ResponseTextDecoder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class ConfigPayloadResolver {
    public interface Fetcher {
        Payload fetch(String url) throws IOException;
    }

    private final ConfigDecoder decoder;

    public ConfigPayloadResolver(ConfigDecoder decoder) {
        this.decoder = decoder;
    }

    public Resolved resolve(String sourceUrl, Fetcher fetcher) throws IOException {
        Payload root = fetcher.fetch(sourceUrl);
        String content = ResponseTextDecoder.decode(root.bytes, root.contentType);
        RuntimeException rootFailure;
        try {
            return resolved(root, content);
        } catch (RuntimeException failure) {
            rootFailure = failure;
        }
        if (!isHtml(root.contentType, content)) {
            throw invalid(rootFailure);
        }

        List<String> candidates = ConfigLinkDiscovery.candidates(content, root.url);
        IOException lastFailure = null;
        for (String candidate : candidates) {
            try {
                Payload payload = fetcher.fetch(candidate);
                String candidateContent = ResponseTextDecoder.decode(
                        payload.bytes, payload.contentType);
                return resolved(payload, candidateContent);
            } catch (IOException failure) {
                lastFailure = candidateFailure(candidate, failure);
            } catch (RuntimeException failure) {
                lastFailure = candidateFailure(candidate, failure);
            }
        }
        if (lastFailure != null) {
            throw new IOException("网页中的 TVBox 配置链接不可用："
                    + lastFailure.getMessage(), lastFailure);
        }
        throw new IOException("网页中没有可用的 TVBox 配置链接", rootFailure);
    }

    private Resolved resolved(Payload payload, String content) {
        return new Resolved(decoder.decodeDocument(content), payload.url,
                payload.bytes, payload.contentType, content);
    }

    private static IOException invalid(RuntimeException failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        return new IOException(message, failure);
    }

    private static IOException candidateFailure(String url, Throwable failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        return new IOException(url + "：" + message, failure);
    }

    private static boolean isHtml(String contentType, String content) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (type.contains("text/html") || type.contains("application/xhtml")) return true;
        String trimmed = content == null ? "" : content.trim().toLowerCase(Locale.US);
        return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html");
    }

    public static final class Payload {
        public final String url;
        public final byte[] bytes;
        public final String contentType;

        public Payload(String url, byte[] bytes, String contentType) {
            this.url = url;
            this.bytes = bytes;
            this.contentType = contentType == null ? "" : contentType;
        }
    }

    public static final class Resolved {
        public final ConfigDecoder.Document document;
        public final String url;
        public final byte[] bytes;
        public final String contentType;
        public final String content;

        Resolved(ConfigDecoder.Document document, String url, byte[] bytes,
                 String contentType, String content) {
            this.document = document;
            this.url = url;
            this.bytes = bytes;
            this.contentType = contentType;
            this.content = content;
        }
    }
}
