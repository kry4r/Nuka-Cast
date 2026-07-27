package com.nukacast.app.server;

import android.content.Context;
import android.content.res.AssetManager;

import com.google.gson.Gson;
import com.nukacast.app.BuildConfig;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.live.model.LiveCatalog;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.storage.model.StorageMount;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class ControlServer extends NanoHTTPD {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Context context;
    private final NukaRuntime runtime;
    private final Gson gson = new Gson();

    public ControlServer(Context context, int port, NukaRuntime runtime) {
        super(port);
        this.context = context.getApplicationContext();
        this.runtime = runtime;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (Method.OPTIONS.equals(session.getMethod())) {
                return decorate(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""));
            }
            String path = session.getUri();
            if (path.startsWith("/media/")) {
                return decorate(serveStorageMedia(session, path.substring("/media/".length())));
            }
            if (path.startsWith("/api/")) {
                return decorate(serveApi(session, path));
            }
            return decorate(serveAsset(path));
        } catch (SecurityException error) {
            return decorate(json(Response.Status.UNAUTHORIZED, error(error.getMessage())));
        } catch (IllegalArgumentException error) {
            return decorate(json(Response.Status.BAD_REQUEST, error(error.getMessage())));
        } catch (Exception error) {
            return decorate(json(Response.Status.INTERNAL_ERROR, error(message(error))));
        }
    }

    private Response serveApi(IHTTPSession session, String path) throws Exception {
        if ("/api/status".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, status());
        }
        if ("/api/pair".equals(path) && Method.POST.equals(session.getMethod())) {
            PairRequest request = body(session, PairRequest.class);
            String token = runtime.getPairingManager().pair(request.code);
            if (token == null) {
                throw new SecurityException("配对码无效");
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("token", token);
            result.put("paired", true);
            return json(Response.Status.OK, result);
        }
        requireAuth(session);

        if ("/api/device".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getDeviceProfile());
        }
        if ("/api/sites".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getTvBoxRepository().getEnabledSites());
        }
        if ("/api/live".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getLiveService().sources());
        }
        if ("/api/live/catalog".equals(path) && Method.GET.equals(session.getMethod())) {
            String sourceId = session.getParms().get("sourceId");
            if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("缺少直播源 ID");
            return json(Response.Status.OK, runtime.getLiveService().catalog(sourceId));
        }
        if ("/api/live/epg".equals(path) && Method.GET.equals(session.getMethod())) {
            String sourceId = session.getParms().get("sourceId");
            String channelId = session.getParms().get("channelId");
            String date = session.getParms().get("date");
            if (sourceId == null || channelId == null) throw new IllegalArgumentException("缺少节目单参数");
            return json(Response.Status.OK, runtime.getLiveService().epg(sourceId, channelId, date));
        }
        if ("/api/live/play".equals(path) && Method.POST.equals(session.getMethod())) {
            LivePlayRequest request = body(session, LivePlayRequest.class);
            LiveCatalog.Channel channel = runtime.getLiveService().channel(request.sourceId, request.channelId);
            int index = Math.max(0, Math.min(request.urlIndex, channel.urls.size() - 1));
            if (channel.urls.isEmpty()) throw new IllegalArgumentException("频道没有播放地址");
            runtime.getPlayerController().play(context, channel.urls.get(index), channel.name, channel.headers);
            return json(Response.Status.ACCEPTED, runtime.getPlayerController().snapshot());
        }
        if ("/api/sources".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getSourceStore().getSources());
        }
        if ("/api/sources".equals(path) && Method.POST.equals(session.getMethod())) {
            SourceRequest request = body(session, SourceRequest.class);
            ConfigSource source = runtime.getSourceStore().add(request.name, request.url);
            runtime.getTvBoxRepository().refresh(source);
            runtime.getState().updateSources(runtime.getSourceStore().getSources().size(),
                    runtime.getTvBoxRepository().getEnabledSites().size());
            return json(Response.Status.CREATED, source);
        }
        if (path.startsWith("/api/sources/") && Method.DELETE.equals(session.getMethod())) {
            String id = path.substring("/api/sources/".length());
            boolean removed = runtime.getSourceStore().remove(id);
            return json(removed ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("removed", removed));
        }
        if ("/api/sources/refresh".equals(path) && Method.POST.equals(session.getMethod())) {
            runtime.getTvBoxRepository().refreshAllAsync(new com.nukacast.app.tvbox.TvBoxRepository.RefreshListener() {
                @Override public void onRefreshComplete(int configs, int sites) {
                    runtime.getState().updateSources(configs, sites);
                }
            });
            return json(Response.Status.ACCEPTED, Collections.singletonMap("refreshing", true));
        }
        if ("/api/storage/mounts".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getStorageLibrary().mounts());
        }
        if ("/api/storage/mounts".equals(path) && Method.POST.equals(session.getMethod())) {
            StorageRequest request = body(session, StorageRequest.class);
            StorageMount mount = runtime.getStorageLibrary().add(request.name, request.type,
                    request.uri, request.username, request.password);
            return json(Response.Status.CREATED, mount);
        }
        if (path.startsWith("/api/storage/mounts/") && Method.DELETE.equals(session.getMethod())) {
            String id = path.substring("/api/storage/mounts/".length());
            boolean removed = runtime.getStorageLibrary().remove(id);
            return json(removed ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("removed", removed));
        }
        if ("/api/storage/scan".equals(path) && Method.POST.equals(session.getMethod())) {
            runtime.getStorageLibrary().scanAllAsync(null);
            return json(Response.Status.ACCEPTED, Collections.singletonMap("scanning", true));
        }
        if ("/api/storage/library".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getStorageLibrary().entries());
        }
        if ("/api/search".equals(path) && Method.POST.equals(session.getMethod())) {
            SearchQuery query = body(session, SearchQuery.class);
            if (query.keyword == null || query.keyword.trim().isEmpty()) {
                throw new IllegalArgumentException("请输入搜索关键词");
            }
            SearchResponse response = runtime.getSearchEngine().search(query);
            return json(Response.Status.OK, response);
        }
        if ("/api/detail".equals(path) && Method.POST.equals(session.getMethod())) {
            ContentRequest request = body(session, ContentRequest.class);
            return json(Response.Status.OK, runtime.getContentService().detail(
                    request.sourceId, request.siteKey, request.vodId));
        }
        if ("/api/play".equals(path) && Method.POST.equals(session.getMethod())) {
            ContentRequest request = body(session, ContentRequest.class);
            PlaybackInfo info = runtime.getContentService().resolve(request.sourceId, request.siteKey,
                    request.flag, request.episodeId, request.title);
            if (!info.direct) throw new IllegalArgumentException(
                    info.error.isEmpty() ? "无法解析播放地址" : info.error);
            com.nukacast.app.tvbox.model.SearchItem item = new com.nukacast.app.tvbox.model.SearchItem();
            item.sourceId = safe(request.sourceId);
            item.siteKey = safe(request.siteKey);
            item.siteName = safe(request.siteName);
            item.vodId = safe(request.vodId);
            item.name = safe(request.name).isEmpty() ? safe(request.title) : request.name;
            item.poster = safe(request.poster);
            item.remarks = safe(request.remarks);
            item.year = safe(request.year);
            item.typeName = safe(request.typeName);
            runtime.getMediaLibrary().start(item, request.flag, request.episodeId, request.episodeName);
            runtime.getPlayerController().play(context, info.url, info.title, info.headers);
            return json(Response.Status.ACCEPTED, info);
        }
        if ("/api/player".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getPlayerController().snapshot());
        }
        if ("/api/player".equals(path) && Method.POST.equals(session.getMethod())) {
            PlayerRequest request = body(session, PlayerRequest.class);
            applyPlayerAction(request);
            return json(Response.Status.ACCEPTED, runtime.getPlayerController().snapshot());
        }
        return json(Response.Status.NOT_FOUND, error("接口不存在"));
    }

    private void applyPlayerAction(PlayerRequest request) {
        PlayerController player = runtime.getPlayerController();
        if ("play".equals(request.action)) {
            player.play(context, request.url, request.title, request.headers);
        } else if ("toggle".equals(request.action)) {
            player.toggle();
        } else if ("seek".equals(request.action)) {
            player.seekBy(request.offsetMs);
        } else if ("stop".equals(request.action)) {
            player.stop();
        } else {
            throw new IllegalArgumentException("未知播放命令");
        }
    }

    private Map<String, Object> status() {
        AppState state = runtime.getState();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("name", "NukaCast");
        result.put("version", BuildConfig.VERSION_NAME);
        result.put("serviceState", state.getServiceState().name().toLowerCase());
        result.put("message", state.getStatusMessage());
        result.put("activeMedia", state.getActiveMedia());
        result.put("sourceCount", state.getSourceCount());
        result.put("siteCount", state.getEnabledSiteCount());
        result.put("storageMountCount", runtime.getStorageLibrary().mounts().size());
        result.put("libraryItemCount", runtime.getStorageLibrary().entries().size());
        result.put("storageScanning", runtime.getStorageLibrary().isScanning());
        result.put("webAddress", runtime.getWebAddress());
        result.put("pairingRequired", true);
        result.put("airPlayName", "NukaCast");
        result.put("airPlay", runtime.getAirPlayReceiver().snapshot());
        return result;
    }

    private Response serveStorageMedia(IHTTPSession session, String id) throws Exception {
        String remote = session.getRemoteIpAddress();
        if (!("127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote))) {
            throw new SecurityException("媒体流仅允许电视本机访问");
        }
        long offset = rangeOffset(session.getHeaders().get("range"));
        StorageLibrary.MediaStream stream = runtime.getStorageLibrary().openSmb(id, offset);
        Response.Status status = offset > 0 ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
        Response response = newFixedLengthResponse(status, stream.mime, stream.input, stream.remainingLength);
        response.addHeader("Accept-Ranges", "bytes");
        if (offset > 0) response.addHeader("Content-Range", "bytes " + offset + "-"
                + (stream.totalLength - 1) + "/" + stream.totalLength);
        return response;
    }

    private static long rangeOffset(String range) {
        if (range == null || !range.startsWith("bytes=")) return 0;
        String value = range.substring(6);
        int dash = value.indexOf('-');
        if (dash >= 0) value = value.substring(0, dash);
        try { return Math.max(0, Long.parseLong(value)); } catch (Exception ignored) { return 0; }
    }

    private void requireAuth(IHTTPSession session) {
        String authorization = session.getHeaders().get("authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        if (!runtime.getPairingManager().isAuthorized(token)) {
            throw new SecurityException("请先与电视配对");
        }
    }

    private Response serveAsset(String requestPath) throws IOException {
        String path = requestPath == null || "/".equals(requestPath)
                ? "index.html" : requestPath.substring(1);
        if (path.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad path");
        }
        byte[] content;
        try {
            content = readAsset("web/" + path);
        } catch (IOException missing) {
            content = readAsset("web/index.html");
            path = "index.html";
        }
        return newFixedLengthResponse(Response.Status.OK, mime(path),
                new ByteArrayInputStream(content), content.length);
    }

    private byte[] readAsset(String path) throws IOException {
        AssetManager assets = context.getAssets();
        InputStream input = assets.open(path, AssetManager.ACCESS_STREAMING);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private <T> T body(IHTTPSession session, Class<T> type) throws Exception {
        Map<String, String> files = new HashMap<String, String>();
        session.parseBody(files);
        String content = files.get("postData");
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("请求体为空");
        }
        T value = gson.fromJson(content, type);
        if (value == null) throw new IllegalArgumentException("JSON 无效");
        return value;
    }

    private Response json(Response.IStatus status, Object body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body));
    }

    private Response decorate(Response response) {
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        return response;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("error", message == null ? "Unknown error" : message);
        return body;
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class PairRequest { String code; }
    private static final class SourceRequest { String name; String url; }
    private static final class StorageRequest {
        String name;
        String type;
        String uri;
        String username;
        String password;
    }
    private static final class ContentRequest {
        String sourceId;
        String siteKey;
        String vodId;
        String flag;
        String episodeId;
        String title;
        String name;
        String poster;
        String remarks;
        String year;
        String typeName;
        String siteName;
        String episodeName;
    }
    private static final class LivePlayRequest {
        String sourceId;
        String channelId;
        int urlIndex;
    }
    private static final class PlayerRequest {
        String action;
        String url;
        String title;
        int offsetMs;
        Map<String, String> headers;
    }
}
