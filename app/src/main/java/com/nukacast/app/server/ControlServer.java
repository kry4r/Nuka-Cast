package com.nukacast.app.server;

import android.content.Context;
import android.content.res.AssetManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.BuildConfig;
import com.nukacast.app.CrashReporter;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.diagnostics.PostMortemLog;
import com.nukacast.app.diagnostics.ProcessMemory;
import com.nukacast.app.diagnostics.SessionMarker;
import com.nukacast.app.diagnostics.StageTrace;
import com.nukacast.app.drama.DramaService;
import com.nukacast.app.drama.model.DramaLineResult;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.live.model.LiveCatalog;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.net.ProbeTool;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.storage.StorageLibrary;
import com.nukacast.app.storage.model.StorageMount;
import com.nukacast.app.tvbox.SearchEngine;
import com.nukacast.app.tvbox.SiteHealthSweep;
import com.nukacast.app.tvbox.SiteHealthStore;
import com.nukacast.app.tvbox.TvBoxContentService;
import com.nukacast.app.tvbox.TvBoxRepository;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            if (path.startsWith("/dlna/")) {
                return decorate(serveDlna(session, path));
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
        } catch (com.nukacast.app.drama.DramaException error) {
            // A drama catalogue saying "there is no such short drama" is an answer, not a server fault.
            // Only /api/drama/play used to translate this, so the rest reported 500 for a missing id.
            return decorate(json(Response.Status.BAD_REQUEST, error(error.getMessage())));
        } catch (Exception error) {
            AppLog.e("网页服务", "请求处理失败 [" + session.getUri() + "]", error);
            return decorate(json(Response.Status.INTERNAL_ERROR, error(message(error))));
        }
    }

    /**
     * Machine-facing endpoints used by the debug CLI / MCP server while diagnosing a real TV.
     *
     * <p>They exist because the interesting details (which site answers, what a stream URL returns
     * from the device's own network, whether the decoder accepted a format) are only observable on
     * the device itself. Everything here answers JSON so tooling can compare runs.
     */
    private Response serveDebug(IHTTPSession session, String path) throws Exception {
        if ("/api/debug/ping".equals(path)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("app", "NukaCast");
            payload.put("time", System.currentTimeMillis());
            payload.put("pid", android.os.Process.myPid());
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/snapshot".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, debugSnapshot(true));
        }
        if ("/api/debug/sites".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, debugSites());
        }
        if ("/api/debug/sources".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, debugSources());
        }
        if ("/api/debug/sources/refresh".equals(path) && Method.POST.equals(session.getMethod())) {
            return json(Response.Status.OK, debugRefreshSources());
        }
        if ("/api/debug/site/test".equals(path) && Method.POST.equals(session.getMethod())) {
            DebugSiteRequest request = body(session, DebugSiteRequest.class);
            return json(Response.Status.OK, debugSiteTest(request));
        }
        if ("/api/debug/search".equals(path) && Method.POST.equals(session.getMethod())) {
            DebugSearchRequest request = body(session, DebugSearchRequest.class);
            return json(Response.Status.OK, debugSearch(request));
        }
        if ("/api/debug/health".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, debugHealth());
        }
        if ("/api/debug/health/run".equals(path) && Method.POST.equals(session.getMethod())) {
            DebugHealthRequest request = body(session, DebugHealthRequest.class);
            runtime.getSiteHealthSweep().start(request.limit, request.keyword,
                    request.pluginsOnly, request.failedOnly);
            return json(Response.Status.ACCEPTED, debugHealth());
        }
        if ("/api/debug/health/stop".equals(path) && Method.POST.equals(session.getMethod())) {
            boolean stopped = runtime.getSiteHealthSweep().stop();
            return json(Response.Status.OK, Collections.singletonMap("stopped", stopped));
        }
        if ("/api/debug/health/clear".equals(path) && Method.POST.equals(session.getMethod())) {
            runtime.getSiteHealthStore().clear();
            return json(Response.Status.OK, Collections.singletonMap("cleared", true));
        }
        if ("/api/debug/probe".equals(path) && Method.POST.equals(session.getMethod())) {
            return json(Response.Status.OK, debugProbe(session));
        }
        if ("/api/debug/play".equals(path) && Method.POST.equals(session.getMethod())) {
            DebugPlayRequest request = body(session, DebugPlayRequest.class);
            return json(Response.Status.OK, debugPlay(request));
        }
        if ("/api/debug/player".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getPlayerController().snapshot());
        }
        if ("/api/debug/categories".equals(path)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> sites = new ArrayList<Map<String, Object>>();
            for (TvBoxConfig.Site site : runtime.getTvBoxRepository().getEnabledSites()) {
                if (site.type == 3) continue;
                List<com.nukacast.app.tvbox.model.Category> categories =
                        runtime.getContentService().categories(site.sourceId, site.key);
                if (categories.isEmpty()) continue;
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("siteKey", site.key);
                entry.put("siteName", site.name);
                entry.put("categories", categories);
                sites.add(entry);
            }
            payload.put("sites", sites);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/browse".equals(path)) {
            String siteKey = session.getParms().get("siteKey");
            String sourceId = session.getParms().get("sourceId");
            String categoryId = session.getParms().get("categoryId");
            int page = debugIntParam(session, "page", 1);
            // year/area/lang apply the same device-side filter the TV's browse page uses, because the
            // sites themselves ignore these parameters.
            com.nukacast.app.tvbox.BrowseFilter filter = new com.nukacast.app.tvbox.BrowseFilter(
                    safe(session.getParms().get("year")), safe(session.getParms().get("area")),
                    safe(session.getParms().get("lang")));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            try {
                List<com.nukacast.app.tvbox.model.SearchItem> items = runtime.getContentService()
                        .browseFiltered(sourceId, siteKey, categoryId, page, filter);
                payload.put("items", items);
                payload.put("count", items.size());
                if (!filter.isEmpty()) {
                    payload.put("filter", filter.label());
                    payload.put("matched", runtime.getContentService().filterMatchCount(
                            sourceId, siteKey, categoryId, filter));
                    payload.put("scanComplete", runtime.getContentService().filterScanComplete(
                            sourceId, siteKey, categoryId, filter));
                }
            } catch (Exception error) {
                payload.put("error", error.getMessage());
            }
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/navigate".equals(path)) {
            String page = session.getParms().get("page");
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("page", runtime.navigateTo(page));
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/focus".equals(path)) {
            final String target = safe(session.getParms().get("target"));
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            String outcome = activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    return activity.focusForDebug(target);
                }
            });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("focus", outcome);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/scroll".equals(path)) {
            int delta = debugIntParam(session, "delta", 600);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("scrolled", runtime.scrollBy(delta));
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/open".equals(path)) {
            // Opening a detail screen remotely is how its layout is checked on a TV that is not in
            // front of the developer: navigate + screenshot + /api/debug/layout.
            String sourceId = session.getParms().get("sourceId");
            String siteKey = session.getParms().get("siteKey");
            String vodId = session.getParms().get("vodId");
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) {
                return json(Response.Status.OK, errorPayload("界面未在前台"));
            }
            try {
                final com.nukacast.app.tvbox.model.MediaDetail detail =
                        runtime.getContentService().detail(sourceId, siteKey, vodId);
                activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                    @Override public String call() {
                        activity.openDetailForDebug(detail);
                        return detail.name;
                    }
                });
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("opened", detail.name);
                payload.put("lines", detail.playSources == null ? 0 : detail.playSources.size());
                return json(Response.Status.OK, payload);
            } catch (Exception error) {
                return json(Response.Status.OK, errorPayload(message(error)));
            }
        }
        if ("/api/debug/key".equals(path)) {
            // Sends a key the way a remote does: through the activity's own dispatch, so the checks
            // cover the key handling (seek, play/pause, menu, back, channel zap) rather than bypassing it.
            final int code = debugIntParam(session, "code", -1);
            final int repeat = debugIntParam(session, "repeat", 1);
            if (code < 0) throw new IllegalArgumentException("缺少 code（Android keyCode）");
            final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            Boolean handled = activity.onUiThreadNow(new java.util.concurrent.Callable<Boolean>() {
                @Override public Boolean call() {
                    boolean any = false;
                    for (int i = 0; i < Math.max(1, Math.min(20, repeat)); i++) {
                        // A dispatched BACK event does not reach onBackPressed (the framework acts on it
                        // before dispatch) and does not close a dialog either, so both are done here —
                        // otherwise an automated run leaves a modal holding every later key.
                        if (code == android.view.KeyEvent.KEYCODE_BACK) {
                            if (activity.closeTopDialogForDebug()) {
                                any = true;
                                continue;
                            }
                            activity.onBackPressed();
                            any = true;
                            continue;
                        }
                        any |= activity.dispatchKeyEvent(new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN, code));
                        any |= activity.dispatchKeyEvent(new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_UP, code));
                    }
                    return any;
                }
            });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("handled", handled);
            payload.put("player", runtime.getPlayerController().snapshot());
            payload.put("activeMedia", runtime.getState().getActiveMedia());
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/player/action".equals(path)) {
            // Drives the same code path as the player menu (speed, aspect, episode stepping), so the
            // menu can be exercised without a remote control in hand.
            String name = session.getParms().get("name");
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            String outcome = activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    return activity.playerMenuActionForDebug(name);
                }
            });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("action", name);
            payload.put("result", outcome);
            com.nukacast.app.player.PlayerController.Snapshot playback =
                    runtime.getPlayerController().snapshot();
            payload.put("speed", playback.speed);
            payload.put("state", playback.state);
            payload.put("aspect", activity.aspectModeForDebug());
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/browseFilter".equals(path)) {
            final String year = safe(session.getParms().get("year"));
            final String area = safe(session.getParms().get("area"));
            final String lang = safe(session.getParms().get("lang"));
            final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            String label = activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    return activity.applyBrowseFilterForDebug(year, area, lang);
                }
            });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("filter", label);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/epg".equals(path)) {
            // What the TV live page would show for a channel: fetched and parsed on the device.
            String sourceId = session.getParms().get("sourceId");
            String channelId = session.getParms().get("channelId");
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            try {
                com.nukacast.app.live.model.EpgSchedule schedule =
                        runtime.getLiveService().epg(sourceId, channelId, "");
                long now = System.currentTimeMillis();
                payload.put("channel", schedule.channel);
                payload.put("programs", schedule.programs.size());
                payload.put("reason", schedule.error);
                payload.put("label", com.nukacast.app.live.EpgNow.label(schedule, now));
                com.nukacast.app.live.EpgNow.Slot current =
                        com.nukacast.app.live.EpgNow.current(schedule, now);
                com.nukacast.app.live.EpgNow.Slot next =
                        com.nukacast.app.live.EpgNow.next(schedule, now);
                payload.put("current", current == null ? "" : current.title);
                payload.put("next", next == null ? "" : next.title);
                if (!schedule.programs.isEmpty()) {
                    com.nukacast.app.live.model.EpgSchedule.Program first = schedule.programs.get(0);
                    payload.put("firstStart", first.start);
                    payload.put("firstTitle", first.title);
                }
            } catch (Throwable error) {
                payload.put("error", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/type".equals(path)) {
            // Types into the TV's own on-screen keyboard (search or live channel search).
            final String target = session.getParms().containsKey("page")
                    ? session.getParms().get("page") : "search";
            final String text = session.getParms().containsKey("text")
                    ? session.getParms().get("text") : "";
            final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            String typed = activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    return activity.typeForDebug(target, text);
                }
            });
            List<String> recent = activity.onUiThreadNow(
                    new java.util.concurrent.Callable<List<String>>() {
                        @Override public List<String> call() {
                            return activity.recentSearchesForDebug();
                        }
                    });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("page", target);
            payload.put("typed", typed);
            payload.put("recentSearches", recent);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/live".equals(path)) {
            // Opens the live page and searches its channels, so the largest playlist can be checked
            // remotely (11k channels cannot be scrolled through from here).
            final String query = session.getParms().get("query");
            final String source = session.getParms().get("source");
            final com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity == null) return json(Response.Status.OK, errorPayload("界面未在前台"));
            if (source != null && !source.isEmpty()) {
                activity.onUiThreadNow(new java.util.concurrent.Callable<String>() {
                    @Override public String call() {
                        return activity.selectLiveSourceForDebug(source);
                    }
                });
            }
            // The catalog arrives asynchronously; wait for it here (this runs on the HTTP thread, so
            // waiting is free) instead of answering before the page has any channels.
            for (int attempt = 0; attempt < 100; attempt++) {
                Boolean ready = activity.onUiThreadNow(
                        new java.util.concurrent.Callable<Boolean>() {
                            @Override public Boolean call() {
                                return activity.liveCatalogReadyForDebug();
                            }
                        });
                if (Boolean.TRUE.equals(ready)) break;
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Without a query this only selects the source, leaving the channel grid focused, which
            // is what a screenshot of the EPG line needs.
            Integer hits = query == null ? null
                    : activity.onUiThreadNow(new java.util.concurrent.Callable<Integer>() {
                        @Override public Integer call() {
                            return activity.liveSearchForDebug(query);
                        }
                    });
            List<String> names = activity.onUiThreadNow(
                    new java.util.concurrent.Callable<List<String>>() {
                        @Override public List<String> call() {
                            return activity.liveSourceNamesForDebug();
                        }
                    });
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("state", activity.onUiThreadNow(
                    new java.util.concurrent.Callable<Map<String, Object>>() {
                        @Override public Map<String, Object> call() {
                            return activity.livePageStateForDebug();
                        }
                    }));
            payload.put("query", query);
            payload.put("hits", hits);
            payload.put("sources", names);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/library".equals(path)) {
            // Favourites and history as recorded on the device, so the TV pages can be checked remotely.
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> favorites = new ArrayList<Map<String, Object>>();
            for (com.nukacast.app.library.LibraryItem item : runtime.getMediaLibrary().favorites()) {
                favorites.add(libraryEntry(item));
            }
            List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
            for (com.nukacast.app.library.LibraryItem item : runtime.getMediaLibrary().history()) {
                history.add(libraryEntry(item));
            }
            payload.put("favorites", favorites);
            payload.put("history", history);
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/favorite".equals(path)) {
            // Toggles a favourite exactly like the remote does, so the page and the smoke test agree.
            ContentRequest request = body(session, ContentRequest.class);
            com.nukacast.app.tvbox.model.SearchItem item =
                    new com.nukacast.app.tvbox.model.SearchItem();
            item.sourceId = safe(request.sourceId);
            item.siteKey = safe(request.siteKey);
            item.siteName = safe(request.siteName);
            item.vodId = safe(request.vodId);
            item.name = safe(request.name);
            item.poster = safe(request.poster);
            item.remarks = safe(request.remarks);
            if (item.name.isEmpty() || item.vodId.isEmpty()) {
                throw new IllegalArgumentException("缺少 name 或 vodId");
            }
            boolean added = runtime.getMediaLibrary().toggleFavorite(item);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("favorited", added);
            payload.put("total", runtime.getMediaLibrary().favorites().size());
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity != null) activity.refreshMoviesPage();
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/dlna".equals(path)) {
            // Reports the renderer state and, on demand, runs an SSDP discovery from this device so the
            // discovery path is verified without a phone in hand.
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("running", runtime.isDlnaRunning());
            payload.put("friendlyName", runtime.dlnaFriendlyName());
            payload.put("uuid", runtime.dlnaUuid());
            payload.put("address", runtime.lanAddress());
            payload.put("descriptionUrl", "http://" + runtime.lanAddress() + ":"
                    + com.nukacast.app.core.NukaRuntime.CONTROL_PORT + "/dlna/description.xml");
            com.nukacast.app.dlna.DlnaRenderer renderer = runtime.getDlnaRenderer();
            payload.put("transportState", renderer.state());
            payload.put("currentUri", renderer.currentUri());
            payload.put("position", com.nukacast.app.dlna.DlnaRenderer.formatTime(renderer.positionMs()));
            payload.put("duration", com.nukacast.app.dlna.DlnaRenderer.formatTime(renderer.durationMs()));
            payload.put("volume", renderer.volume());
            if ("1".equals(session.getParms().get("probe"))) {
                String location = com.nukacast.app.dlna.DlnaSsdp.probe(3000);
                payload.put("ssdpLocation", location);
                payload.put("ssdpAnswered", !location.isEmpty());
            }
            return json(Response.Status.OK, payload);
        }
        if ("/api/debug/layout".equals(path) && Method.GET.equals(session.getMethod())) {
            return json(Response.Status.OK, runtime.getLayoutReport());
        }
        if ("/api/debug/logs".equals(path) && Method.GET.equals(session.getMethod())) {
            String level = session.getParms().get("level");
            int limit = debugIntParam(session, "limit", 80);
            AppLog.Level wanted = level == null || level.isEmpty() || "all".equalsIgnoreCase(level)
                    ? null : AppLog.Level.valueOf(level.toUpperCase(Locale.ROOT));
            List<AppLog.Entry> entries = AppLog.snapshot(wanted);
            if (entries.size() > limit) {
                entries = entries.subList(entries.size() - limit, entries.size());
            }
            return json(Response.Status.OK, entries);
        }
        if ("/api/debug/logs/clear".equals(path) && Method.POST.equals(session.getMethod())) {
            AppLog.clear();
            return json(Response.Status.OK, Collections.singletonMap("cleared", true));
        }
        if ("/api/debug/export".equals(path) && Method.GET.equals(session.getMethod())) {
            return diagnosticExport(session);
        }
        return json(Response.Status.NOT_FOUND, error("未知调试接口：" + path));
    }

    private Map<String, Object> debugSnapshot(boolean withLogs) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("generatedAt", System.currentTimeMillis());
        payload.put("status", status());
        payload.put("device", runtime.getDeviceProfile());
        payload.put("memory", memorySnapshot());
        payload.put("airPlay", runtime.getAirPlayReceiver().snapshot());
        payload.put("httpStack", httpStackSnapshot());
        payload.put("stages", StageTrace.snapshot());
        payload.put("runSession", SessionMarker.interruptedRun());
        payload.put("postMortem", PostMortemLog.read(runtime.getContext()));
        payload.put("sites", debugSites());
        payload.put("sources", debugSources());
        payload.put("health", debugHealth());
        payload.put("player", runtime.getPlayerController().snapshot());
        if (withLogs) {
            List<AppLog.Entry> entries = AppLog.snapshot(null);
            int from = Math.max(0, entries.size() - 60);
            payload.put("logs", entries.subList(from, entries.size()));
        }
        return payload;
    }

    /** The HTTP stack only exposes the frozen boot state; the debug API wants it named. */
    private Map<String, Object> httpStackSnapshot() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("degraded", HttpStack.degraded());
        payload.put("initError", HttpStack.initError());
        return payload;
    }

    /** The playback settings as the TV sees them (readable without the window in front). */
    private Map<String, Object> playbackSettings() {
        com.nukacast.app.player.PlaybackSettings settings = playbackSettingsStore();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("autoNextEpisode", settings.autoNextEpisode());
        values.put("quality", settings.quality());
        values.put("qualityLabel", com.nukacast.app.player.PlaybackSettings.qualityLabel(settings.quality()));
        values.put("softDecoder",
                com.nukacast.app.player.DecoderPreference.prefersSoftware(runtime.getContext()));
        return values;
    }

    private com.nukacast.app.player.PlaybackSettings playbackSettingsStore() {
        return new com.nukacast.app.player.PlaybackSettings(runtime.getContext());
    }

    private Map<String, Object> applyPlaybackSetting(String name, String value) {
        com.nukacast.app.player.PlaybackSettings settings = playbackSettingsStore();
        if ("autoNextEpisode".equals(name)) {
            settings.setAutoNextEpisode(!"0".equals(value) && !"false".equals(value));
        } else if ("quality".equals(name)) {
            settings.setQuality(value);
        } else if ("softDecoder".equals(name)) {
            if ("0".equals(value) || "false".equals(value)) {
                com.nukacast.app.player.DecoderPreference.clear(runtime.getContext());
            } else {
                com.nukacast.app.player.DecoderPreference.preferSoftware(runtime.getContext());
            }
        } else {
            throw new IllegalArgumentException("未知设置：" + name);
        }
        // A window in front must show the new value immediately.
        com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
        if (activity != null) activity.refreshPlaybackSettings();
        return playbackSettings();
    }

    /** One library entry, reduced to what a caller needs to check the TV pages. */
    private Map<String, Object> libraryEntry(com.nukacast.app.library.LibraryItem item) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("name", item.name);
        entry.put("siteName", item.siteName);
        entry.put("vodId", item.vodId);
        entry.put("episodeName", item.episodeName);
        entry.put("positionMs", item.positionMs);
        entry.put("durationMs", item.durationMs);
        return entry;
    }

    /** Device, budget and current usage in one place: the numbers a crash report needs. */
    private Map<String, Object> memorySnapshot() {
        Map<String, Object> memory = new LinkedHashMap<String, Object>();
        Context context = runtime.getContext();
        Runtime java = Runtime.getRuntime();
        memory.put("heapUsedBytes", java.totalMemory() - java.freeMemory());
        memory.put("heapMaxBytes", java.maxMemory());
        memory.put("rssBytes", ProcessMemory.rssBytes());
        memory.put("vmSizeBytes", ProcessMemory.vmSizeBytes());
        memory.put("threads", ProcessMemory.threadCount());
        memory.put("oomScoreAdj", ProcessMemory.oomScoreAdj());
        memory.put("pssBytes", ProcessMemory.totalPssBytes(context));
        memory.put("totalRamBytes", ProcessMemory.totalRamBytes(context));
        memory.put("pluginBudgetBytes", ProcessMemory.pluginBudgetBytes(context));
        memory.put("pluginPausedForMemory", runtime.getSpiderManager().pausedForMemory());
        memory.put("pluginSessions", runtime.getSpiderManager().sessionDetail());
        memory.put("unsupportedSites", runtime.getSpiderManager().compatibilitySnapshot());
        memory.put("stageTraces", StageTrace.snapshot().size());
        memory.put("logEntries", AppLog.snapshot(null).size());
        return memory;
    }

    private Map<String, Object> debugSites() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<TvBoxConfig.Site> sites = runtime.getTvBoxRepository().getEnabledSites();
        int plugins = 0;
        for (TvBoxConfig.Site site : sites) {
            if (site.type == 3) plugins++;
        }
        payload.put("enabledSites", sites.size());
        payload.put("pluginSites", plugins);
        payload.put("cmsSites", sites.size() - plugins);
        payload.put("searchSiteLimit", SearchEngine.MAX_SEARCH_SITES);
        payload.put("homePluginLimit", TvBoxContentService.MAX_PLUGIN_HOME_SITES);
        payload.put("sites", sites);
        return payload;
    }

    private Map<String, Object> debugSources() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ConfigSource source : runtime.getSourceStore().getSources()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", source.id);
            row.put("name", source.name);
            row.put("url", source.url);
            row.put("enabled", source.enabled);
            row.put("siteCount", source.siteCount);
            row.put("latencyMs", source.latencyMs);
            row.put("error", source.error);
            row.put("updatedAt", source.updatedAt);
            rows.add(row);
        }
        payload.put("sources", rows);
        payload.put("liveSources", runtime.getTvBoxRepository().getLiveSourceStore().all());
        return payload;
    }

    private Map<String, Object> debugRefreshSources() {
        final CountDownLatch done = new CountDownLatch(1);
        runtime.getTvBoxRepository().refreshAllAsync(new TvBoxRepository.RefreshListener() {
            @Override public void onSourceRefreshed(int sources, int sites) { }

            @Override public void onRefreshComplete(int sources, int sites) {
                done.countDown();
            }
        });
        try {
            done.await(45, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return debugSources();
    }

    private Map<String, Object> debugHealth() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        SiteHealthSweep.Job job = runtime.getSiteHealthSweep().status();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("running", job.running);
        status.put("cancelled", job.cancelled);
        status.put("startedAt", job.startedAt);
        status.put("finishedAt", job.finishedAt);
        status.put("total", job.total);
        status.put("done", job.done);
        status.put("ok", job.ok);
        status.put("failed", job.failed);
        status.put("keyword", job.keyword);
        status.put("currentSite", job.currentSite);
        status.put("error", job.error);
        status.put("results", job.results);
        payload.put("sweep", status);
        SiteHealthStore store = runtime.getSiteHealthStore();
        int[] counts = store.counts();
        payload.put("knownGood", counts[0]);
        payload.put("knownBad", counts[1]);
        payload.put("lastCheckedAt", store.lastCheckedAt());
        payload.put("verdicts", store.snapshot());
        return payload;
    }

    private Map<String, Object> debugSiteTest(DebugSiteRequest request) throws Exception {
        if (request == null || request.siteKey == null || request.siteKey.isEmpty()) {
            throw new IllegalArgumentException("siteKey 必填");
        }
        TvBoxConfig.Site target = null;
        for (TvBoxConfig.Site site : runtime.getTvBoxRepository().getEnabledSites()) {
            if (request.siteKey.equals(site.key)) {
                target = site;
                break;
            }
        }
        if (target == null) throw new IllegalArgumentException("站点不存在：" + request.siteKey);
        long startedAt = System.currentTimeMillis();
        SiteHealthSweep.Result result = runtime.getSiteHealthSweep().test(target, request.keyword);
        runtime.getSiteHealthStore().record(target, result.ok, result.itemCount,
                result.latencyMs, result.reason);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("site", target);
        payload.put("ok", result.ok);
        payload.put("itemCount", result.itemCount);
        payload.put("latencyMs", result.latencyMs);
        payload.put("reason", result.reason);
        payload.put("elapsedMs", System.currentTimeMillis() - startedAt);
        return payload;
    }

    private Map<String, Object> debugSearch(DebugSearchRequest request) throws Exception {
        if (request == null || request.keyword == null || request.keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("keyword 必填");
        }
        SearchQuery query = new SearchQuery();
        query.keyword = request.keyword.trim();
        query.sourceId = request.sourceId == null ? "" : request.sourceId;
        query.page = Math.max(1, request.page);
        query.pageSize = request.pageSize <= 0 ? 40 : request.pageSize;
        query.forceSites = request.forceSites;
        if (request.siteKeys != null) query.siteKeys.addAll(request.siteKeys);
        long startedAt = System.currentTimeMillis();
        SearchResponse response = runtime.getSearchEngine().search(query);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("keyword", query.keyword);
        payload.put("searchedSites", response.searchedSites);
        payload.put("failedSites", response.failedSites);
        payload.put("partial", response.partial);
        payload.put("errors", response.errors);
        payload.put("items", response.items);
        payload.put("elapsedMs", response.elapsedMs);
        payload.put("wallMs", System.currentTimeMillis() - startedAt);
        return payload;
    }

    /** Fetches a URL from the device, which is the network that actually matters. */
    private Map<String, Object> debugProbe(IHTTPSession session) throws Exception {
        Map<String, String> parsed = new HashMap<String, String>();
        session.parseBody(parsed);
        String raw = parsed.get("postData");
        String url = null;
        String method = "GET";
        if (raw != null && raw.trim().startsWith("{")) {
            JsonObject object = JsonParser.parseString(raw.trim()).getAsJsonObject();
            if (object.has("url")) url = object.get("url").getAsString();
            if (object.has("method")) method = object.get("method").getAsString().toUpperCase(Locale.ROOT);
        } else {
            url = session.getParms().get("url");
        }
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("url 必填");
        return ProbeTool.run(url.trim(), method);
    }

    private Map<String, Object> debugPlay(DebugPlayRequest request) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        String url = request == null ? null : request.url;
        String title = request == null || request.title == null ? "" : request.title;
        Map<String, String> headers = new HashMap<String, String>();
        if (url == null || url.trim().isEmpty()) {
            if (request == null || request.siteKey == null || request.vodId == null) {
                throw new IllegalArgumentException("需要 url，或 siteKey + vodId");
            }
            TvBoxConfig.Site site = null;
            for (TvBoxConfig.Site candidate : runtime.getTvBoxRepository().getEnabledSites()) {
                if (request.siteKey.equals(candidate.key)) {
                    site = candidate;
                    break;
                }
            }
            if (site == null) throw new IllegalArgumentException("站点不存在：" + request.siteKey);
            MediaDetail detail = runtime.getContentService().detail(
                    site.sourceId, site.key, request.vodId);
            if (detail == null || detail.playSources.isEmpty()) {
                throw new IllegalArgumentException("该条目没有播放地址");
            }
            MediaDetail.PlaySource source = detail.playSources.get(0);
            if (source.episodes.isEmpty()) throw new IllegalArgumentException("该线路没有剧集");
            int index = Math.max(0, Math.min(source.episodes.size() - 1, request.episodeIndex));
            MediaDetail.Episode episode = source.episodes.get(index);
            PlaybackInfo info = runtime.getContentService().resolve(site.sourceId, site.key,
                    source.name, episode.id, episode.name);
            url = info.url;
            title = info.title == null || info.title.isEmpty() ? detail.name : info.title;
            headers.putAll(info.headers);
            payload.put("resolvedFrom", site.name + " / " + request.vodId);
        }
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("解析出的播放地址为空");
        payload.put("url", url);
        payload.put("title", title);
        // Probe first: a URL that answers 403/404 explains a black screen long before the player
        // reports "source error", and that response is the exact condition that used to crash the app.
        payload.put("probe", ProbeTool.run(url, "GET"));
        // Playing has to leave the app in the same state the UI does, otherwise the remote keys that
        // only act during full-screen playback (seek, up/down zapping) cannot be exercised from here.
        runtime.getState().updateActiveMedia(title);
        runtime.getPlayerController().play(runtime.getContext(), url, title, headers);
        payload.put("player", runtime.getPlayerController().snapshot());
        return payload;
    }

    private static final class DebugSiteRequest {
        String siteKey;
        String keyword;
    }

    /** Reads an integer query parameter, falling back when it is missing or malformed. */
    private static int debugIntParam(IHTTPSession session, String name, int fallback) {
        String raw = session.getParms().get(name);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static final class DebugHealthRequest {
        int limit;
        String keyword;
        boolean pluginsOnly;
        boolean failedOnly;
    }

    private static final class DebugPlayRequest {
        String url;
        String title;
        String siteKey;
        String vodId;
        int episodeIndex;
    }

    private static final class DebugSearchRequest {
        String keyword;
        String sourceId;
        List<String> siteKeys;
        int page = 1;
        int pageSize;
        boolean forceSites;
    }

    /**
     * DLNA: device and service descriptions, SOAP control, and event subscriptions.
     *
     * <p>Control points fetch the description, then POST SOAP actions to the control URLs advertised
     * inside it. Responding here (rather than on a second socket) keeps one address for the web
     * console and for casting, which is also the LOCATION announced over SSDP.
     */
    private Response serveDlna(IHTTPSession session, String path) throws Exception {
        if ("/dlna/description.xml".equals(path)) {
            return xml(dlnaDescription());
        }
        if (path.startsWith("/dlna/service/")) {
            String name = path.substring("/dlna/service/".length());
            String serviceType = dlnaServiceType(name);
            if (serviceType.isEmpty()) return notFound("服务不存在");
            return xml(com.nukacast.app.dlna.DlnaDescription.serviceScpd(serviceType));
        }
        if (path.startsWith("/dlna/control/")) {
            String name = path.substring("/dlna/control/".length());
            String serviceType = dlnaServiceType(name);
            if (serviceType.isEmpty()) return notFound("服务不存在");
            byte[] body = readBody(session);
            String soapAction = session.getHeaders().get("soapaction");
            com.nukacast.app.dlna.SoapMessage request =
                    com.nukacast.app.dlna.SoapMessage.parse(
                            soapAction, new String(body, UTF_8));
            if (request != null && (request.serviceType == null || request.serviceType.isEmpty())) {
                request = com.nukacast.app.dlna.SoapMessage.parse(
                        "\"" + serviceType + "#" + request.action + "\"",
                        new String(body, UTF_8));
            }
            com.nukacast.app.dlna.DlnaService.Result result =
                    runtime.getDlnaService().handle(request);
            onDlnaAction(request == null ? "" : request.action);
            Response response = newFixedLengthResponse(
                    result.status == 200 ? Response.Status.OK : Response.Status.INTERNAL_ERROR,
                    result.contentType, new java.io.ByteArrayInputStream(result.body), result.body.length);
            response.addHeader("EXT", "");
            return response;
        }
        if (path.startsWith("/dlna/event/")) {
            String method = session.getMethod() == null ? "" : session.getMethod().name();
            if ("SUBSCRIBE".equals(method)) {
                String sid = "uuid:" + java.util.UUID.randomUUID();
                Response response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
                response.addHeader("SID", sid);
                response.addHeader("TIMEOUT", "Second-1800");
                return response;
            }
            if ("UNSUBSCRIBE".equals(method)) {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
            }
            // The initial event message some control points fetch instead of subscribing.
            return xml(dlnaEventMessage("AVTransport"));
        }
        return notFound("接口不存在");
    }

    private String dlnaDescription() {
        com.nukacast.app.dlna.DlnaDescription.Device device =
                new com.nukacast.app.dlna.DlnaDescription.Device();
        device.friendlyName = runtime.dlnaFriendlyName();
        device.uuid = runtime.dlnaUuid();
        device.modelName = runtime.getDeviceProfile().model;
        device.modelNumber = "Android " + runtime.getDeviceProfile().androidVersion;
        device.serial = runtime.getDeviceProfile().manufacturer + " "
                + runtime.getDeviceProfile().model;
        device.baseUrl = "http://" + runtime.lanAddress() + ":" + com.nukacast.app.core.NukaRuntime.CONTROL_PORT;
        return com.nukacast.app.dlna.DlnaDescription.device(device);
    }

    private static String dlnaServiceType(String name) {
        if (name.startsWith("AVTransport")) {
            return com.nukacast.app.dlna.DlnaDescription.SERVICE_AV_TRANSPORT;
        }
        if (name.startsWith("RenderingControl")) {
            return com.nukacast.app.dlna.DlnaDescription.SERVICE_RENDERING_CONTROL;
        }
        if (name.startsWith("ConnectionManager")) {
            return com.nukacast.app.dlna.DlnaDescription.SERVICE_CONNECTION_MANAGER;
        }
        return "";
    }

    /** Minimal event message: enough for control points that read the initial state. */
    private String dlnaEventMessage(String serviceName) {
        com.nukacast.app.dlna.DlnaRenderer renderer = runtime.getDlnaRenderer();
        return "<?xml version=\"1.0\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                + "<e:property><TransportState>" + renderer.state() + "</TransportState></e:property>"
                + "<e:property><CurrentTrackURI>" + com.nukacast.app.dlna.SoapMessage.escape(
                        renderer.currentUri()) + "</CurrentTrackURI></e:property>"
                + "<e:property><Volume>" + renderer.volume() + "</Volume></e:property>"
                + "</e:propertyset>";
    }

    /** Called after each control action, so the TV screen can react to a phone. */
    private void onDlnaAction(String action) {
        com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
        if (activity == null) return;
        activity.onDlnaAction(action);
    }

    private Response xml(String body) {
        byte[] bytes = body.getBytes(UTF_8);
        Response response = newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"",
                new java.io.ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("EXT", "");
        return response;
    }

    private Response notFound(String message) {
        return json(Response.Status.NOT_FOUND, error(message));
    }

    /** Reads the request body (NanoHTTPD requires parseBody for POST/PUT). */
    private byte[] readBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new java.util.HashMap<String, String>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null) {
            body = files.get("content");
        }
        if (body == null) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = session.getInputStream().read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_API_BODY_BYTES) break;
            }
            return out.toByteArray();
        }
        return body.getBytes(UTF_8);
    }

    private Response serveApi(IHTTPSession session, String path) throws Exception {
        if (path.startsWith("/api/debug")) {
            return serveDebug(session, path);
        }
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
        if ("/api/logs/export".equals(path) && Method.GET.equals(session.getMethod())) {
            return diagnosticExport(session);
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
            // Resolve through DramaService rather than sending the stored episode URL straight to the
            // player: several CMS back ends publish a player page, and "cannot play" has to come back
            // as an explained 400 instead of a generic request failure.
            com.nukacast.app.drama.model.DramaPlayResult resolved;
            try {
                resolved = runtime.getDramaService().play(
                        request.providerId, request.dramaId, request.index);
            } catch (com.nukacast.app.drama.DramaException dramaError) {
                Map<String, Object> failure = new LinkedHashMap<String, Object>();
                failure.put("error", dramaError.getMessage());
                failure.put("code", dramaError.code);
                return json(Response.Status.BAD_REQUEST, failure);
            }
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
            String title = resolved.title == null || resolved.title.isEmpty()
                    ? episode.name : resolved.title;
            runtime.getPlayerController().play(context, resolved.url, title, resolved.headers);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("title", title);
            payload.put("url", resolved.url);
            payload.put("index", episode.index);
            payload.put("episodeName", episode.name);
            payload.put("headers", resolved.headers);
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
            PlaybackInfo info = runtime.getContentService().resolvePlayable(request.sourceId,
                    request.siteKey, request.flag, request.episodeId, request.vodId, request.title);
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
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            boolean started = false;
            if (activity != null) {
                // Playing through the activity (rather than straight into the player) is what gives the
                // web console's play button the same behaviour as the remote: auto next episode,
                // automatic line switching, and the on-screen HUD.
                final com.nukacast.app.tvbox.model.MediaDetail detail =
                        runtime.getContentService().detail(request.sourceId, request.siteKey, request.vodId);
                final String flag = safe(request.flag);
                final String episodeId = safe(request.episodeId);
                started = activity.playDetailEpisodeForDebug(detail, flag, episodeId);
            }
            if (!started) {
                runtime.getPlayerController().play(context, info.url, info.title, info.headers);
            }
            return json(Response.Status.ACCEPTED, info);
        }
        if ("/api/settings".equals(path) && Method.GET.equals(session.getMethod())) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.putAll(playbackSettings());
            return json(Response.Status.OK, payload);
        }
        if ("/api/settings".equals(path) && Method.POST.equals(session.getMethod())) {
            ContentRequest request = body(session, ContentRequest.class);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.putAll(applyPlaybackSetting(safe(request.name), safe(request.value)));
            return json(Response.Status.OK, payload);
        }
        if ("/api/library".equals(path) && Method.GET.equals(session.getMethod())) {
            // The web console shows the same list the TV's 收藏 page and 继续观看 row read.
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> favorites = new ArrayList<Map<String, Object>>();
            for (com.nukacast.app.library.LibraryItem item : runtime.getMediaLibrary().favorites()) {
                favorites.add(libraryEntry(item));
            }
            List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
            for (com.nukacast.app.library.LibraryItem item : runtime.getMediaLibrary().history()) {
                history.add(libraryEntry(item));
            }
            payload.put("favorites", favorites);
            payload.put("history", history);
            return json(Response.Status.OK, payload);
        }
        if ("/api/library/favorite".equals(path) && Method.POST.equals(session.getMethod())) {
            ContentRequest request = body(session, ContentRequest.class);
            String name = safe(request.name);
            String vodId = safe(request.vodId);
            String action = safe(request.action).isEmpty() ? "toggle" : safe(request.action);
            if (!"toggle".equals(action)) {
                // Removal goes through /api/library/remove, which also covers history and clearing.
                throw new IllegalArgumentException("不支持的 action：" + action);
            }
            if ("toggle".equals(action)) {
                com.nukacast.app.tvbox.model.SearchItem item =
                        new com.nukacast.app.tvbox.model.SearchItem();
                item.sourceId = safe(request.sourceId);
                item.siteKey = safe(request.siteKey);
                item.siteName = safe(request.siteName);
                item.vodId = vodId;
                item.name = name;
                item.poster = safe(request.poster);
                item.remarks = safe(request.remarks);
                if (item.name.isEmpty() || item.vodId.isEmpty()) {
                    throw new IllegalArgumentException("缺少 name 或 vodId");
                }
                boolean added = runtime.getMediaLibrary().toggleFavorite(item);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("favorited", added);
                payload.put("total", runtime.getMediaLibrary().favorites().size());
                com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
                if (activity != null) activity.refreshMoviesPage();
                return json(Response.Status.OK, payload);
            }
            throw new IllegalArgumentException("不支持的 action：" + action);
        }
        if ("/api/library/remove".equals(path) && Method.POST.equals(session.getMethod())) {
            // Removing one entry by kind (favorite/history) and key, or clearing a whole list.
            ContentRequest request = body(session, ContentRequest.class);
            String kind = safe(request.kind).isEmpty() ? "history" : safe(request.kind);
            String key = safe(request.vodId).isEmpty() ? safe(request.name) : safe(request.vodId);
            boolean cleared = "1".equals(safe(request.all)) || "true".equals(safe(request.all));
            int removed = runtime.getMediaLibrary().remove(kind, key, cleared);
            com.nukacast.app.MainActivity activity = com.nukacast.app.MainActivity.onScreen();
            if (activity != null) activity.refreshMoviesPage();
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("kind", kind);
            payload.put("removed", removed);
            return json(Response.Status.OK, payload);
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
        // A run replaced by an app update is not a crash; the console must not say it was.
        summary.put("killedByUpdate",
                !run.endedCleanly && run.startedAt > 0L
                        && com.nukacast.app.diagnostics.SessionMarker.packageUpdateTime(
                                context) > run.startedAt);
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

    /**
     * One-click diagnostic bundle. Returns a text file so it can be attached to a report as-is; the
     * web console downloads it, and the TV writes the same content next to its own files.
     */
    private Response diagnosticExport(IHTTPSession session) {
        Map<String, String> parameters = session.getParms();
        com.nukacast.app.diagnostics.AppLog.Level level = null;
        String requested = parameters == null ? null : parameters.get("level");
        if (requested != null && !requested.isEmpty() && !"all".equalsIgnoreCase(requested)) {
            try {
                level = com.nukacast.app.diagnostics.AppLog.Level.valueOf(
                        requested.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                level = null;
            }
        }
        String body = com.nukacast.app.diagnostics.DiagnosticsReport.build(context, runtime, level);
        Response response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8",
                body);
        response.addHeader("Content-Disposition",
                "attachment; filename=\"" + com.nukacast.app.diagnostics.DiagnosticsReport.fileName() + "\"");
        response.addHeader("Cache-Control", "no-store");
        return response;
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

    private static Map<String, Object> errorPayload(String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", message);
        return payload;
    }

    private <T> T body(IHTTPSession session, Class<T> type) throws Exception {
        String content = readBodyAsUtf8(session);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("请求体为空");
        }
        T value = gson.fromJson(content, type);
        if (value == null) throw new IllegalArgumentException("JSON 无效");
        return value;
    }

    /**
     * Reads the request body as UTF-8 directly from the stream.
     *
     * <p>NanoHTTPD's {@code parseBody} decodes with the charset in the request header and falls back
     * to US-ASCII when there is none, which silently replaced every Chinese character with "�" —
     * adding a source named 饭太硬 stored the name as mojibake. JSON is UTF-8 by definition, so the
     * bytes are decoded here instead of trusting a header that clients routinely omit.
     */
    private static String readBodyAsUtf8(IHTTPSession session) throws IOException {
        String lengthHeader = session.getHeaders().get("content-length");
        int length = -1;
        if (lengthHeader != null) {
            try {
                length = Integer.parseInt(lengthHeader.trim());
            } catch (NumberFormatException ignored) {
                length = -1;
            }
        }
        if (length < 0 || length > MAX_API_BODY_BYTES) {
            // Without a usable Content-Length the framing is unknown, so let NanoHTTPD read it.
            Map<String, String> files = new HashMap<String, String>();
            try {
                session.parseBody(files);
            } catch (Exception error) {
                throw new IOException(error);
            }
            String content = files.get("postData");
            return content == null ? "" : content;
        }
        if (length == 0) return "";
        // Read exactly the declared number of bytes: the socket stays open for keep-alive, so a
        // read-until-EOF loop would block until the connection timed out.
        byte[] body = new byte[length];
        InputStream stream = session.getInputStream();
        int read = 0;
        while (read < length) {
            int chunk = stream.read(body, read, length - read);
            if (chunk < 0) break;
            read += chunk;
        }
        String content = new String(body, 0, read, UTF_8);
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
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
    /**
     * One row per playable live source.
     *
     * <p>Playlists added by the user and playlists inherited from a config used to be listed as
     * separate rows even when they pointed at the same URL, so the console (and the TV page) showed
     * the same source two or three times. {@link com.nukacast.app.live.LiveService#sources()} is now
     * the single, de-duplicated source of truth.
     */
    private List<Map<String, Object>> liveSources() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
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
        /** Settings requests: the new value of the named setting. */
        String value;
        /** Library requests: which list (favorite/history), what to do, and whether to clear it. */
        String kind;
        String action;
        String all;
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
