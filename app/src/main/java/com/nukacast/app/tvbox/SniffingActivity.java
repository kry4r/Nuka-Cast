package com.nukacast.app.tvbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.nukacast.app.diagnostics.AppLog;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SniffingActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MEDIA_URL = "mediaUrl";
    public static final String EXTRA_USER_AGENT = "userAgent";
    public static final String RESULT_URL = "resultUrl";
    public static final String RESULT_COOKIE = "resultCookie";
    public static final String RESULT_REFERER = "resultReferer";
    public static final String RESULT_USER_AGENT = "resultUserAgent";
    private static final long TIMEOUT_MS = 25000L;

    private final AtomicBoolean completed = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            AppLog.w("解析", "嗅探器收到无效解析页地址");
            finishWithError("无效解析页地址");
            return;
        }
        createView();
        configureWebView();
        Map<String, String> headers = new HashMap<String, String>();
        String agent = getIntent().getStringExtra(EXTRA_USER_AGENT);
        if (agent != null && !agent.trim().isEmpty()) headers.put("User-Agent", agent);
        webView.loadUrl(url, headers);
        AppLog.i("解析", "已打开 WebView 解析页进行媒体嗅探");
        handler.postDelayed(new Runnable() {
            @Override public void run() { finishWithError("解析页嗅探超时"); }
        }, TIMEOUT_MS);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("NukaSniffer");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void createView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView status = new TextView(this);
        status.setText("正在解析播放地址...");
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(28, 12, 28, 12);
        status.setBackgroundColor(0xcc111827);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 64, Gravity.TOP);
        root.addView(status, params);
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(webView, true);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String agent = getIntent().getStringExtra(EXTRA_USER_AGENT);
        if (agent != null && !agent.trim().isEmpty()) settings.setUserAgentString(agent);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "NukaSniffer");
        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, String url) {
                inspect(url);
                return null;
            }

            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= 21 && request != null && request.getUrl() != null) {
                    inspect(request.getUrl().toString());
                }
                return null;
            }

            @Override public void onLoadResource(WebView view, String url) {
                inspect(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                view.loadUrl("javascript:(function(){var e=document.querySelector('video[src],source[src]');"
                        + "if(e&&e.src)NukaSniffer.found(e.src);"
                        + "var p=window.performance&&performance.getEntriesByType?performance.getEntriesByType('resource'):[];"
                        + "for(var i=0;i<p.length;i++)NukaSniffer.found(p[i].name);})()");
            }
        });
    }

    private void inspect(final String candidate) {
        if (!PlaybackInfoParser.isDirectMedia(candidate)) return;
        runOnUiThread(new Runnable() {
            @Override public void run() { finishWithUrl(candidate); }
        });
    }

    private void finishWithUrl(String url) {
        if (!completed.compareAndSet(false, true)) return;
        AppLog.i("解析", "WebView 已嗅探到直链");
        Intent result = new Intent();
        result.putExtra(RESULT_URL, url);
        String cookie = CookieManager.getInstance().getCookie(url);
        result.putExtra(RESULT_COOKIE, cookie == null ? "" : cookie);
        result.putExtra(RESULT_REFERER, webView == null ? "" : webView.getUrl());
        result.putExtra(RESULT_USER_AGENT, webView == null
                ? "" : webView.getSettings().getUserAgentString());
        setResult(RESULT_OK, result);
        finish();
    }

    private void finishWithError(String message) {
        if (!completed.compareAndSet(false, true)) return;
        AppLog.w("解析", message);
        Intent result = new Intent();
        result.putExtra("error", message);
        setResult(RESULT_CANCELED, result);
        finish();
    }

    private final class JsBridge {
        @JavascriptInterface public void found(String url) { inspect(url); }
    }
}
