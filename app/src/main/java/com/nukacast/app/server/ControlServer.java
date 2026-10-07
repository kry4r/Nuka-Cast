package com.nukacast.app.server;

import android.content.Context;
import android.content.res.AssetManager;

import com.google.gson.Gson;
import com.nukacast.app.BuildConfig;
import com.nukacast.app.CrashReporter;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.StageTrace;
import com.nukacast.app.drama.DramaService;
import com.nukacast.app.drama.model.DramaLineResult;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.live.model.LiveCatalog;
import com.nukacast.app.net.HttpStack;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class ControlServer extends NanoHTTPD {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_API_BODY_BYTES = 256 * 1024;
    private static final int MAX_ASSET_BYTES = 8 * 1024 * 1024;
    private final Context context;
    private final NukaRuntime runtime;
    private final Gson gson = new Gson();

    public ControlServer(Context context, int port, NukaRuntime runtime) {
        super("0.0.0.0", port);
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
            if (("/proxy".equals(path) || "/".equals(path))
                    && (session.getParms().containsKey("do")
                    || session.getParms().containsKey("go"))) {
                return decorate(serveSpiderProxy(session));
            }
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
            AppLog.e("网页服务", "请求处理失败 [" + session.getUri() + "]", error);
            return decorate(json(Response.Status.INTERNAL_ERROR, error(message(error))));
        }
    }

    private Response serveApi(IHTTPSession session, String path) throws Exception {
        if ("/api/status".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, status());
        }
        if ("/api/device".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getDeviceProfile());
        }
        if ("/api/diagnostics".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, diagnostics());
        }
        if ("/api/logs".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, AppLog.snapshot(null));
        }
        if ("/api/logs".equals(path) && Method.DELETE.equals(session.getMethod())) {
            AppLog.clear();
            return json(Response.Status.OK, Collections.singletonMap("cleared", true));
        }
        if ("/api/sites".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getTvBoxRepository().getEnabledSites());
        }
        if ("/api/live".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getLiveService().sources());
        }
        if ("/api/live/sources".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, liveSources());
        }
        if ("/api/live/sources".equals(path) && Method.POST.equals(session.getMethod())) {
            LiveSourceRequest request = body(session, LiveSourceRequest.class);
            com.nukacast.app.tvbox.model.LivePlaylist created =
                    runtime.getTvBoxRepository().getLiveSourceStore().add(request.name, request.url);
            return json(Response.Status.CREATED, created);
        }
        if (path.startsWith("/api/live/sources/") && Method.DELETE.equals(session.getMethod())) {
            String id = path.substring("/api/live/sources/".length());
            boolean removed = runtime.getTvBoxRepository().getLiveSourceStore().remove(id);
            return json(removed ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("removed", removed));
        }
        if (path.startsWith("/api/live/sources/") && path.endsWith("/enabled")
                && Method.POST.equals(session.getMethod())) {
            String id = path.substring("/api/live/sources/".length(),
                    path.length() - "/enabled".length());
            DramaEnabledRequest request = body(session, DramaEnabledRequest.class);
            boolean updated = runtime.getTvBoxRepository().getLiveSourceStore()
                    .setEnabled(id, request.enabled);
            return json(updated ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("enabled", updated && request.enabled));
        }
        if ("/api/recommended".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, recommendedSources());
        }
        if ("/api/recommended/verify".equals(path) && Method.POST.equals(session.getMethod())) {
            RecommendedVerifyRequest request = body(session, RecommendedVerifyRequest.class);
            return json(Response.Status.OK, verifyRecommended(request));
        }
        if ("/api/recommended/add".equals(path) && Method.POST.equals(session.getMethod())) {
            RecommendedAddRequest request = body(session, RecommendedAddRequest.class);
            return json(Response.Status.OK, addRecommended(request));
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
        if ("/api/drama/providers".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, dramaProviders());
        }
        if ("/api/drama/providers".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaProviderRequest request = body(session, DramaProviderRequest.class);
            DramaProviderConfig provider = runtime.getDramaService().registry()
                    .add(request.name, request.url);
            return json(Response.Status.CREATED, provider);
        }
        if (path.startsWith("/api/drama/providers/") && Method.DELETE.equals(session.getMethod())) {
            String id = path.substring("/api/drama/providers/".length());
            boolean removed = runtime.getDramaService().registry().remove(id);
            return json(removed ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("removed", removed));
        }
        if (path.startsWith("/api/drama/providers/") && path.endsWith("/enabled")
                && Method.POST.equals(session.getMethod())) {
            String id = path.substring("/api/drama/providers/".length(),
                    path.length() - "/enabled".length());
            DramaEnabledRequest request = body(session, DramaEnabledRequest.class);
            boolean updated = runtime.getDramaService().registry().setEnabled(id, request.enabled);
            return json(updated ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("enabled", updated && request.enabled));
        }
        if ("/api/drama/search".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaSearchRequest request = body(session, DramaSearchRequest.class);
            DramaSearchResult result = runtime.getDramaService().search(
                    request.providerId, request.keyword);
            return json(Response.Status.OK, result);
        }
        if ("/api/drama/detail".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaDetailRequest request = body(session, DramaDetailRequest.class);
            return json(Response.Status.OK, runtime.getDramaService().detail(
                    request.providerId, request.dramaId));
        }
        if ("/api/drama/lines".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaDetailRequest request = body(session, DramaDetailRequest.class);
            DramaLineResult result = runtime.getDramaService().lines(
                    request.providerId, request.dramaId, request.sourceId);
            return json(Response.Status.OK, result);
        }
        if ("/api/drama/browse".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaBrowseRequest request = body(session, DramaBrowseRequest.class);
            return json(Response.Status.OK, runtime.getDramaService().browse(
                    request.providerId, request.categoryId, request.page));
        }
        if ("/api/drama/play".equals(path) && Method.POST.equals(session.getMethod())) {
            DramaPlayRequest request = body(session, DramaPlayRequest.class);
            com.nukacast.app.drama.model.DramaEpisode episode = runtime.getDramaService()
                    .episode(request.providerId, request.dramaId, request.index);
            com.nukacast.app.tvbox.model.SearchItem item =
                    new com.nukacast.app.tvbox.model.SearchItem();
            item.sourceId = "drama:" + safe(request.providerId);
            item.siteKey = safe(request.providerId);
            item.siteName = "短剧直连";
            item.vodId = safe(request.dramaId);
            item.name = safe(request.title).isEmpty() ? episode.name : request.title;
            item.poster = safe(request.poster);
            item.remarks = episode.name;
            runtime.getMediaLibrary().start(item, "drama", String.valueOf(episode.index),
                    episode.name);
            String title = safe(request.title).isEmpty()
                    ? episode.name : request.title + " · " + episode.name;
            runtime.getPlayerController().play(context, episode.playUrl, title, episode.headers);
            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("title", title);
            payload.put("url", episode.playUrl);
            payload.put("index", episode.index);
            payload.put("episodeName", episode.name);
            payload.put("headers", episode.headers);
            return json(Response.Status.ACCEPTED, payload);
        }
        if ("/api/sources".equals(path) && Method.POST.equals(session.getMethod())) {
            SourceRequest request = body(session, SourceRequest.class);
            ConfigSource source = runtime.getSourceStore().add(request.name, request.url);
            runtime.contentChanged();
            runtime.getTvBoxRepository().refreshSourceTreeAsync(source,
                    new com.nukacast.app.tvbox.TvBoxRepository.RefreshListener() {
                        @Override public void onSourceRefreshed(int configs, int sites) {
                            runtime.contentChanged();
                        }
                        @Override public void onRefreshComplete(int configs, int sites) {
                            // Per-source callbacks publish progress immediately.
                        }
                    });
            return json(Response.Status.CREATED, source);
        }
        if (path.startsWith("/api/sources/") && Method.DELETE.equals(session.getMethod())) {
            String id = path.substring("/api/sources/".length());
            boolean removed = runtime.removeSource(id);
            return json(removed ? Response.Status.OK : Response.Status.NOT_FOUND,
                    Collections.singletonMap("removed", removed));
        }
        if ("/api/sources/refresh".equals(path) && Method.POST.equals(session.getMethod())) {
            runtime.getTvBoxRepository().refreshAllAsync(new com.nukacast.app.tvbox.TvBoxRepository.RefreshListener() {
                @Override public void onSourceRefreshed(int configs, int sites) {
                    runtime.contentChanged();
                }
                @Override public void onRefreshComplete(int configs, int sites) {
                    // Per-source callbacks publish progress immediately.
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
            runtime.sourceHealthChanged();
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
        if ("/api/airplay/disconnect".equals(path) && Method.POST.equals(session.getMethod())) {
            runtime.getAirPlayReceiver().disconnectSession();
            return json(Response.Status.ACCEPTED,
                    Collections.singletonMap("disconnected", true));
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
            if (runtime.getAirPlayReceiver().snapshot().sessionActive
                    || "AirPlay 镜像".equals(runtime.getState().getActiveMedia())) {
                runtime.getAirPlayReceiver().disconnectSession();
            } else {
                player.stop();
            }
        } else {
            throw new IllegalArgumentException("未知播放命令");
        }
    }

    private Map<String, Object> status() {
        AppState state = runtime.getState();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("name", "NukaCast");
        result.put("version", BuildConfig.VERSION_NAME);
        result.put("serviceState", state.getServiceState().name().toLowerCase(Locale.ROOT));
        result.put("message", state.getStatusMessage());
        result.put("activeMedia", state.getActiveMedia());
        result.put("sourceCount", state.getSourceCount());
        result.put("siteCount", state.getEnabledSiteCount());
        result.put("stateVersion", state.getStateVersion());
        result.put("contentVersion", state.getContentVersion());
        result.put("storageMountCount", runtime.getStorageLibrary().mounts().size());
        result.put("libraryItemCount", runtime.getStorageLibrary().entries().size());
        result.put("storageScanning", runtime.getStorageLibrary().isScanning());
        result.put("webAddress", runtime.getWebAddress());
        result.put("airPlayName", "NukaCast");
        result.put("airPlay", runtime.getAirPlayReceiver().snapshot());
        return result;
    }

    private Map<String, Object> diagnostics() {
        AppState state = runtime.getState();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("javaCrash", CrashReporter.read(context));
        result.put("serviceState", state.getServiceState().name().toLowerCase(Locale.ROOT));
        result.put("serviceMessage", state.getStatusMessage());
        result.put("deviceWarnings", runtime.getDeviceProfile().warnings);
        result.put("airPlay", runtime.getAirPlayReceiver().snapshot());
        result.put("player", runtime.getPlayerController().snapshot());
        result.put("sources", runtime.getSourceStore().getSources());
        result.put("homeErrors", runtime.getContentService().homeFailures());
        result.put("drama", runtime.getDramaService().diagnostics());
        result.put("stages", StageTrace.snapshot());
        result.put("siteIssues", runtime.getSpiderManager().compatibility().snapshot());
        result.put("lastRun", lastRunSummary());
        Map<String, Object> httpStack = new HashMap<String, Object>();
        httpStack.put("degraded", HttpStack.degraded());
        httpStack.put("initError", HttpStack.initError());
        result.put("httpStack", httpStack);
        return result;
    }

    /**
     * How the previous process ended, with the memory curve that led there. This is what answers
     * "it crashes after a while" when the platform kills the process for memory and no crash
     * handler ever runs.
     */
    private Map<String, Object> lastRunSummary() {
        com.nukacast.app.diagnostics.SessionMarker.Run run =
                com.nukacast.app.diagnostics.SessionMarker.interruptedRun();
        if (run == null) return null;
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("startedAt", run.startedAt);
        summary.put("endedAt", run.endedAt);
        summary.put("endedCleanly", run.endedCleanly);
        summary.put("durationMs", run.durationMs());
        summary.put("device", run.device);
        summary.put("version", run.version);
        summary.put("peakHeapPercent", run.peakHeapPercent());
        com.nukacast.app.diagnostics.SessionMarker.Sample last = run.lastSample();
        summary.put("lastStage", last == null ? "" : last.stage);
        summary.put("lastHeapPercent", last == null ? 0 : last.heapPercent());
        summary.put("lastAvailableMemoryBytes", last == null ? 0L : last.availableMemoryBytes);
        List<Map<String, Object>> samples = new ArrayList<Map<String, Object>>();
        for (com.nukacast.app.diagnostics.SessionMarker.Sample sample : run.samples) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("at", sample.at);
            row.put("heapPercent", sample.heapPercent());
            row.put("heapUsedBytes", sample.heapUsedBytes);
            row.put("nativeHeapBytes", sample.nativeHeapBytes);
            row.put("availableMemoryBytes", sample.availableMemoryBytes);
            row.put("stage", sample.stage);
            samples.add(row);
        }
        summary.put("samples", samples);
        return summary;
    }

    private Response serveStorageMedia(IHTTPSession session, String id) throws Exception {
        String remote = session.getRemoteIpAddress();
        if (!"127.0.0.1".equals(remote)) {
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

    private Response serveSpiderProxy(IHTTPSession session) throws Exception {
        Map<String, String> params = new HashMap<String, String>(session.getParms());
        params.putAll(session.getHeaders());
        Object[] result = runtime.getSpiderManager().proxy(params);
        if (result == null || result.length < 3) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Spider proxy returned no response");
        }
        if (result[0] instanceof Response) return (Response) result[0];
        int code = result[0] instanceof Number ? ((Number) result[0]).intValue() : 500;
        Response.Status status = Response.Status.lookup(code);
        if (status == null) status = Response.Status.INTERNAL_ERROR;
        String mime = result[1] == null ? MIME_PLAINTEXT : String.valueOf(result[1]);
        InputStream input;
        if (result[2] instanceof InputStream) {
            input = (InputStream) result[2];
        } else if (result[2] instanceof byte[]) {
            input = new ByteArrayInputStream((byte[]) result[2]);
        } else {
            String value = result[2] == null ? "" : String.valueOf(result[2]);
            input = new ByteArrayInputStream(value.getBytes(UTF_8));
        }
        Response response = newChunkedResponse(status, mime, input);
        if (result.length >= 4 && result[3] instanceof Map) {
            Map<?, ?> headers = (Map<?, ?>) result[3];
            for (Map.Entry<?, ?> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    response.addHeader(String.valueOf(header.getKey()), String.valueOf(header.getValue()));
                }
            }
        }
        return response;
    }

    private static long rangeOffset(String range) {
        if (range == null || !range.startsWith("bytes=")) return 0;
        String value = range.substring(6);
        int dash = value.indexOf('-');
        if (dash >= 0) value = value.substring(0, dash);
        try { return Math.max(0, Long.parseLong(value)); } catch (Exception ignored) { return 0; }
    }

    static boolean requiresAuthentication(String path) {
        return false;
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
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_ASSET_BYTES) throw new IOException("资源文件过大");
                output.write(buffer, 0, count);
            }
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
        if (content.getBytes(UTF_8).length > MAX_API_BODY_BYTES) {
            throw new IllegalArgumentException("请求体过大");
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
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: http: https:; connect-src 'self'; frame-ancestors 'none'");
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

    private Map<String, Object> dramaProviders() {
        DramaService service = runtime.getDramaService();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("providers", service.registry().providers());
        return result;
    }

    /** User playlists plus the live sources that come from TVBox configs, marked for the UI. */
    private List<Map<String, Object>> liveSources() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (com.nukacast.app.tvbox.model.LivePlaylist playlist : runtime.getTvBoxRepository()
                .getLiveSourceStore().all()) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("id", playlist.id);
            row.put("name", playlist.name);
            row.put("url", playlist.url);
            row.put("enabled", playlist.enabled);
            row.put("error", playlist.error);
            row.put("updatedAt", playlist.updatedAt);
            row.put("user", true);
            result.add(row);
        }
        for (com.nukacast.app.live.model.LiveSourceInfo info : runtime.getLiveService().sources()) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("id", info.id);
            row.put("name", info.name);
            row.put("url", info.url);
            row.put("enabled", true);
            row.put("error", "");
            row.put("updatedAt", 0L);
            row.put("user", com.nukacast.app.live.LiveService.isUserSource(info.id));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> recommendedSources() {
        com.nukacast.app.sources.RecommendedSources sources = runtime.getRecommendedSources();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("verifiedAt", sources.verifiedAt());
        result.put("note", sources.note());
        result.put("items", sources.list());
        return result;
    }

    private Map<String, Object> verifyRecommended(RecommendedVerifyRequest request) {
        com.nukacast.app.sources.RecommendedSources sources = runtime.getRecommendedSources();
        final List<com.nukacast.app.sources.RecommendedSource> targets =
                new ArrayList<com.nukacast.app.sources.RecommendedSource>();
        if (request != null && request.all) {
            for (com.nukacast.app.sources.RecommendedSource item : sources.list()) {
                if (request.kind == null || request.kind.isEmpty()
                        || request.kind.equals(item.kind)) {
                    targets.add(item);
                }
            }
        } else if (request != null && request.id != null && !request.id.isEmpty()) {
            com.nukacast.app.sources.RecommendedSource item = sources.find(request.id);
            if (item == null) throw new IllegalArgumentException("推荐源不存在");
            targets.add(item);
        } else {
            throw new IllegalArgumentException("缺少要检测的推荐源");
        }
        // Probes hit third-party hosts: run them in parallel so a full pass stays interactive.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<com.nukacast.app.sources.RecommendedSource.Probe>> futures =
                new ArrayList<java.util.concurrent.Future<com.nukacast.app.sources.RecommendedSource.Probe>>();
        for (final com.nukacast.app.sources.RecommendedSource item : targets) {
            futures.add(pool.submit(new java.util.concurrent.Callable<
                    com.nukacast.app.sources.RecommendedSource.Probe>() {
                @Override public com.nukacast.app.sources.RecommendedSource.Probe call() {
                    return sources.verify(item.id);
                }
            }));
        }
        List<com.nukacast.app.sources.RecommendedSource.Probe> probes =
                new ArrayList<com.nukacast.app.sources.RecommendedSource.Probe>();
        for (java.util.concurrent.Future<com.nukacast.app.sources.RecommendedSource.Probe> future
                : futures) {
            try {
                probes.add(future.get(40, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception error) {
                AppLog.w("推荐源", "检测未完成：" + error.getMessage());
            }
        }
        pool.shutdownNow();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("probes", probes);
        result.put("items", sources.list());
        return result;
    }

    private Map<String, Object> addRecommended(RecommendedAddRequest request) {
        com.nukacast.app.sources.RecommendedSources sources = runtime.getRecommendedSources();
        int added = 0;
        if (request != null && request.all) {
            added = sources.addAll(request.kind);
        } else if (request != null && request.ids != null && !request.ids.isEmpty()) {
            for (String id : request.ids) {
                try {
                    sources.add(id);
                    added++;
                } catch (RuntimeException error) {
                    AppLog.w("推荐源", "添加失败 [" + id + "]：" + error.getMessage());
                }
            }
        } else if (request != null && request.id != null && !request.id.isEmpty()) {
            sources.add(request.id);
            added = 1;
        } else {
            throw new IllegalArgumentException("缺少要添加的推荐源");
        }
        runtime.sourceHealthChanged();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("added", added);
        result.put("items", sources.list());
        return result;
    }

    private static final class SourceRequest { String name; String url; }
    private static final class LiveSourceRequest { String name; String url; }
    private static final class DramaProviderRequest {
        String name;
        String url;
    }
    private static final class DramaEnabledRequest { boolean enabled; }
    private static final class DramaSearchRequest {
        String providerId;
        String keyword;
    }
    private static final class DramaDetailRequest {
        String providerId;
        String dramaId;
        String sourceId;
    }
    private static final class DramaBrowseRequest {
        String providerId;
        String categoryId;
        int page;
    }
    private static final class DramaPlayRequest {
        String providerId;
        String dramaId;
        String title;
        String poster;
        int index;
    }
    private static final class RecommendedVerifyRequest {
        String id;
        String kind;
        boolean all;
    }
    private static final class RecommendedAddRequest {
        String id;
        java.util.List<String> ids;
        String kind;
        boolean all;
    }
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
