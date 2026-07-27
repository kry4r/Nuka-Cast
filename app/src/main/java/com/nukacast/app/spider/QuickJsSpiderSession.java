package com.nukacast.app.spider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.tvbox.model.TvBoxConfig;
import com.nukacast.app.util.Urls;
import com.quickjs.ES6Module;
import com.quickjs.JSArray;
import com.quickjs.JSObject;
import com.quickjs.JavaCallback;
import com.quickjs.QuickJS;
import com.quickjs.plugin.ConsolePlugin;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class QuickJsSpiderSession implements SpiderSession {
    private static final String PRELUDE =
            "function __nukaRequest(url, options) { return __nukaHttp(url, options || {}); }\n" +
            "globalThis.req = __nukaRequest;\n" +
            "globalThis._http = __nukaRequest;\n" +
            "globalThis.http = __nukaRequest;\n" +
            "for (const name of ['global','window','self']) { try { Object.defineProperty(globalThis, name, { configurable: true, get() { return globalThis; } }); } catch (_) {} }\n";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String scriptUrl;
    private final String extension;
    private QuickJS runtime;
    private RemoteModule module;
    private JSObject spider;

    QuickJsSpiderSession(TvBoxConfig.Site site) throws Exception {
        this.scriptUrl = findScriptUrl(site);
        this.extension = site.ext == null ? "" : site.ext;
        submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                initialize();
                return null;
            }
        }).get();
    }

    static boolean supports(TvBoxConfig.Site site) {
        try {
            return !findScriptUrl(site).isEmpty();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override public String home(final boolean filter) throws Exception {
        return call("home", filter);
    }

    @Override public String category(final String id, final String page, final boolean filter,
                                     final HashMap<String, String> extend) throws Exception {
        return submit(new Callable<String>() {
            @Override public String call() {
                JSObject options = new JSObject(module, new JSONObject(extend == null
                        ? Collections.<String, String>emptyMap() : extend));
                return stringify(spider.executeFunction2("category", id, page, filter, options));
            }
        }).get();
    }

    @Override public String detail(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("详情 ID 为空");
        return call("detail", ids.get(0));
    }

    @Override public String search(String keyword, boolean quick, String page) throws Exception {
        return call("search", keyword, quick, page);
    }

    @Override public String play(final String flag, final String id, final List<String> vipFlags)
            throws Exception {
        return submit(new Callable<String>() {
            @Override public String call() {
                JSArray flags = new JSArray(module);
                if (vipFlags != null) {
                    for (String value : vipFlags) flags.push(value);
                }
                return stringify(spider.executeFunction2("play", flag, id, flags));
            }
        }).get();
    }

    @Override public void destroy() {
        try {
            submit(new Callable<Void>() {
                @Override public Void call() {
                    try {
                        if (spider != null && spider.contains("destroy")) {
                            spider.executeFunction2("destroy");
                        }
                    } catch (RuntimeException ignored) {}
                    if (module != null) module.close();
                    if (runtime != null) runtime.close();
                    spider = null;
                    module = null;
                    runtime = null;
                    return null;
                }
            }).get();
        } catch (Exception ignored) {
        } finally {
            executor.shutdownNow();
        }
    }

    private void initialize() throws Exception {
        runtime = QuickJS.createRuntime();
        module = new RemoteModule(runtime, scriptUrl);
        module.addPlugin(new ConsolePlugin());
        module.registerJavaMethod((JavaCallback) (receiver, args) -> HttpBridge.request(
                module, args.getString(0), args.length() > 1 ? args.getObject(1) : null), "__nukaHttp");
        module.executeVoidScript(PRELUDE, "nukacast-http.js");
        String escaped = scriptUrl.replace("\\", "\\\\").replace("'", "\\'");
        String entry = "import * as source from '" + escaped + "';\n" +
                "if (source.__jsEvalReturn) globalThis.__NUKA_SPIDER__ = source.__jsEvalReturn();\n" +
                "else if (source.default) globalThis.__NUKA_SPIDER__ = typeof source.default === 'function' ? source.default() : source.default;\n" +
                "else globalThis.__NUKA_SPIDER__ = source;\n";
        module.executeModuleScript(entry, "nukacast-entry.js");
        spider = module.getObject("__NUKA_SPIDER__");
        if (spider == null) throw new IllegalStateException("JS Spider 未导出对象");
        if (spider.contains("init")) {
            Object ext = extension;
            if (extension.trim().startsWith("{")) {
                try { ext = new JSObject(module, new JSONObject(extension)); }
                catch (Exception ignored) { ext = extension; }
            }
            spider.executeFunction2("init", ext);
        }
    }

    private String call(final String method, final Object... arguments) throws Exception {
        return submit(new Callable<String>() {
            @Override public String call() {
                if (!spider.contains(method)) {
                    throw new IllegalStateException("JS Spider 未实现 " + method);
                }
                return stringify(spider.executeFunction2(method, arguments));
            }
        }).get();
    }

    private <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    private static String stringify(Object value) {
        if (value == null) return "{}";
        if (value instanceof String) return (String) value;
        if (value instanceof JSArray) return ((JSArray) value).toJSONArray().toString();
        if (value instanceof JSObject) return ((JSObject) value).toJSONObject().toString();
        return String.valueOf(value);
    }

    private static String findScriptUrl(TvBoxConfig.Site site) {
        String[] candidates = { site.api, site.ext, site.jar, site.globalSpider };
        for (String candidate : candidates) {
            String found = scriptCandidate(site.configBaseUrl, candidate);
            if (!found.isEmpty()) return found;
        }
        throw new IllegalArgumentException("JS 站点未提供脚本地址");
    }

    private static String scriptCandidate(String base, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return "";
        String value = candidate.trim();
        if (value.startsWith("{")) {
            try {
                JsonElement element = new JsonParser().parse(value);
                String nested = findUrl(element);
                if (!nested.isEmpty()) value = nested;
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        String resolved = Urls.resolve(base == null ? "" : base, value);
        String clean = resolved.toLowerCase(Locale.US);
        return (clean.startsWith("http://") || clean.startsWith("https://"))
                && clean.contains(".js") ? resolved : "";
    }

    private static String findUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            return value.toLowerCase(Locale.US).contains(".js") ? value : "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String[] keys = { "js", "url", "api", "ext" };
            for (String key : keys) {
                String value = findUrl(object.get(key));
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static final class RemoteModule extends ES6Module {
        private final Map<String, String> scripts = new HashMap<String, String>();
        private final String entryUrl;

        RemoteModule(QuickJS runtime, String entryUrl) {
            super(runtime);
            this.entryUrl = entryUrl;
        }

        @Override protected String getModuleScript(String moduleName) {
            String url = normalize(entryUrl, moduleName);
            String cached = scripts.get(url);
            if (cached != null) return cached;
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "NukaCast/0.1 QuickJS")
                    .build();
            try (Response response = HttpStack.client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("JS 模块 HTTP " + response.code());
                }
                String content = response.body().string();
                scripts.put(url, content);
                return content;
            } catch (IOException error) {
                throw new IllegalStateException("无法加载 JS 模块: " + url, error);
            }
        }

        @Override protected String convertModuleName(String baseName, String moduleName) {
            if (moduleName == null || moduleName.isEmpty()) return moduleName;
            if (moduleName.startsWith("http://") || moduleName.startsWith("https://")) return moduleName;
            return normalize(entryUrl, baseName == null || baseName.isEmpty()
                    ? moduleName : Urls.resolve(baseName, moduleName));
        }

        private static String normalize(String base, String value) {
            if (value == null || value.isEmpty() || "nukacast-entry.js".equals(value)) return base;
            return Urls.resolve(base, value);
        }
    }

    private static final class HttpBridge {
        static JSObject request(ES6Module context, String url, JSObject options) {
            JSONObject config = options == null ? new JSONObject() : options.toJSONObject();
            String method = config.optString("method", "GET").toUpperCase(Locale.US);
            Request.Builder request = new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; NukaCast)");
            JSONObject headers = config.optJSONObject("headers");
            if (headers != null) {
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    request.header(key, headers.optString(key));
                }
            }
            String body = config.has("data") ? String.valueOf(config.opt("data"))
                    : config.optString("body", "");
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                String type = headers == null ? "application/json; charset=utf-8"
                        : headers.optString("Content-Type", "application/json; charset=utf-8");
                request.method(method, RequestBody.create(MediaType.parse(type), body));
            } else {
                request.method(method, null);
            }
            JSONObject result = new JSONObject();
            try (Response response = HttpStack.client().newCall(request.build()).execute()) {
                String content = response.body() == null ? "" : response.body().string();
                result.put("ok", response.isSuccessful());
                result.put("code", response.code());
                result.put("status", response.code());
                result.put("url", response.request().url().toString());
                result.put("content", content);
                JSONObject responseHeaders = new JSONObject();
                for (String name : response.headers().names()) {
                    responseHeaders.put(name, response.header(name, ""));
                }
                result.put("headers", responseHeaders);
            } catch (Exception error) {
                try {
                    result.put("ok", false);
                    result.put("code", 599);
                    result.put("status", 599);
                    result.put("url", url);
                    result.put("content", "");
                    result.put("error", error.getMessage());
                } catch (Exception ignored) {}
            }
            return new JSObject(context, result);
        }
    }
}
