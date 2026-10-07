package com.nukacast.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nukacast.app.airplay.AirPlayReceiver;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.DeviceProfile;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.drama.model.DramaDetail;
import com.nukacast.app.drama.model.DramaEpisode;
import com.nukacast.app.drama.model.DramaPlayResult;
import com.nukacast.app.drama.model.DramaItem;
import com.nukacast.app.drama.model.DramaLine;
import com.nukacast.app.drama.model.DramaLineResult;
import com.nukacast.app.drama.model.DramaSearchResult;
import com.nukacast.app.library.LibraryItem;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.service.NukaCastService;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;
import com.nukacast.app.tvbox.SniffingActivity;
import com.nukacast.app.ui.MediaCardView;
import com.nukacast.app.ui.PosterImageLoader;
import com.nukacast.app.ui.TvTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements AppState.Listener, SurfaceHolder.Callback {
    private static final int REQUEST_STORAGE_PERMISSION = 4101;
    private static final int REQUEST_MANAGE_STORAGE = 4102;
    private static final int REQUEST_NOTIFICATIONS = 4103;
    private static final int REQUEST_SNIFF_PLAYBACK = 4104;
    private static final String PAGE_HOME = "home";
    private static final String PAGE_MOVIES = "movies";
    private static final String PAGE_SEARCH = "search";
    private static final String PAGE_CAST = "cast";
    private static final String PAGE_SETTINGS = "settings";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final PosterImageLoader images = new PosterImageLoader();
    private final List<SearchItem> homeItems = new ArrayList<SearchItem>();
    private static final String DRAMA_SOURCE_PREFIX = "drama:";

    private final List<SearchItem> dramaItems = new ArrayList<SearchItem>();
    private NukaRuntime runtime;
    private View appShell;
    private View homePage;
    private View moviesPage;
    private View searchPage;
    private View castPage;
    private View settingsPage;
    private LinearLayout homeContent;
    private LinearLayout moviesContent;
    private LinearLayout searchResults;
    private GridLayout searchKeyboard;
    private EditText searchKeyword;
    private TextView searchStatus;
    private TextView homeLoading;
    private TextView serviceStatus;
    private TextView networkStatus;
    private TextView webAddress;
    private TextView airplayState;
    private TextView deviceSummary;
    private TextView codecSummary;
    private TextView sourceSummary;
    private TextView storageSummary;
    private View featuredPanel;
    private TextView featuredEyebrow;
    private TextView featuredTitle;
    private TextView featuredMeta;
    private TextView featuredPlot;
    private ImageView featuredPoster;
    private SurfaceView videoSurface;
    private View castPlaybackOverlay;
    private Button castStopOverlay;
    private Button refreshSourcesButton;
    private Button scanStorageButton;
    private Button themeToggleButton;
    private Button viewLogsButton;
    private String currentPage = PAGE_HOME;
    private String currentMovieFilter = "";
    private boolean homeRequestRunning;
    private boolean homeLoaded;
    private int lastSiteCount = -1;
    private PendingPlayback pendingPlayback;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private int searchGeneration;
    private final Runnable delayedSearch = new Runnable() {
        @Override public void run() { searchFromKeyboard(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(TvTheme.isLight(this) ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUi();
        setContentView(R.layout.activity_main);

        runtime = ((NukaCastApp) getApplication()).runtime();
        bindViews();
        TvTheme.apply(this, appShell);
        bindNavigation();
        videoSurface.getHolder().addCallback(this);
        runtime.getState().addListener(this);
        startReceiverService();
        requestNotificationPermission();
        showPage(PAGE_HOME);
        render();
        loadHome(false);
        findViewById(R.id.navHome).requestFocus();
        showPreviousCrash();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        render();
        if (PAGE_HOME.equals(currentPage)) renderHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE && hasStoragePermission()) scanStorage();
        if (requestCode == REQUEST_SNIFF_PLAYBACK) {
            PendingPlayback pending = pendingPlayback;
            pendingPlayback = null;
            String url = data == null ? "" : safe(data.getStringExtra(SniffingActivity.RESULT_URL));
            if (resultCode == RESULT_OK && pending != null && !url.isEmpty()) {
                mergeSniffHeader(pending.info.headers, "Cookie", data,
                        SniffingActivity.RESULT_COOKIE);
                mergeSniffHeader(pending.info.headers, "Referer", data,
                        SniffingActivity.RESULT_REFERER);
                mergeSniffHeader(pending.info.headers, "User-Agent", data,
                        SniffingActivity.RESULT_USER_AGENT);
                completePlayback(pending, url);
            } else {
                String error = data == null ? "未嗅探到媒体地址" : safe(data.getStringExtra("error"));
                AppLog.w("解析", error.isEmpty() ? "未嗅探到媒体地址" : error);
                Toast.makeText(this, error.isEmpty() ? "未嗅探到媒体地址" : error,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanStorage();
            } else {
                Toast.makeText(this, "未获得存储权限，无法扫描本机或 U 盘",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        runtime.getState().removeListener(this);
        io.shutdownNow();
        images.shutdown();
        super.onDestroy();
    }

    @Override
    public void onStateChanged(final AppState state) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                render();
                if (state.getEnabledSiteCount() != lastSiteCount) {
                    lastSiteCount = state.getEnabledSiteCount();
                    if (lastSiteCount > 0) loadHome(true);
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE) {
            runtime.getPlayerController().toggle();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            runtime.getPlayerController().seekBy(30000);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            runtime.getPlayerController().seekBy(-10000);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            stopActivePlayback();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSearchPage();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            stopActivePlayback();
            showPage(PAGE_SETTINGS);
            findViewById(R.id.navSettings).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        AirPlayReceiver.Snapshot airplay = runtime.getAirPlayReceiver().snapshot();
        if (airplay.sessionActive || "AirPlay 镜像".equals(runtime.getState().getActiveMedia())) {
            runtime.getAirPlayReceiver().disconnectSession();
            Toast.makeText(this, "已退出 AirPlay 投屏", Toast.LENGTH_SHORT).show();
            return;
        }
        if (runtime.getState().getActiveMedia() != null
                && !runtime.getState().getActiveMedia().isEmpty()) {
            runtime.getPlayerController().stop();
            return;
        }
        if (!PAGE_HOME.equals(currentPage)) {
            showPage(PAGE_HOME);
            findViewById(R.id.navHome).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        runtime.getPlayerController().attachSurface(holder);
        runtime.getAirPlayReceiver().attachSurface(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        runtime.getPlayerController().detachSurface(holder);
        runtime.getAirPlayReceiver().detachSurface(holder);
    }

    private void bindViews() {
        appShell = findViewById(R.id.appShell);
        homePage = findViewById(R.id.homePage);
        moviesPage = findViewById(R.id.moviesPage);
        searchPage = findViewById(R.id.searchPage);
        castPage = findViewById(R.id.castPage);
        settingsPage = findViewById(R.id.settingsPage);
        homeContent = (LinearLayout) findViewById(R.id.homeContent);
        moviesContent = (LinearLayout) findViewById(R.id.moviesContent);
        searchResults = (LinearLayout) findViewById(R.id.searchResults);
        searchKeyboard = (GridLayout) findViewById(R.id.searchKeyboard);
        searchKeyword = (EditText) findViewById(R.id.searchKeyword);
        searchStatus = (TextView) findViewById(R.id.searchStatus);
        homeLoading = (TextView) findViewById(R.id.homeLoading);
        serviceStatus = (TextView) findViewById(R.id.serviceStatus);
        networkStatus = (TextView) findViewById(R.id.networkStatus);
        webAddress = (TextView) findViewById(R.id.webAddress);
        airplayState = (TextView) findViewById(R.id.airplayState);
        deviceSummary = (TextView) findViewById(R.id.deviceSummary);
        codecSummary = (TextView) findViewById(R.id.codecSummary);
        sourceSummary = (TextView) findViewById(R.id.sourceSummary);
        storageSummary = (TextView) findViewById(R.id.storageSummary);
        refreshSourcesButton = (Button) findViewById(R.id.refreshSourcesButton);
        scanStorageButton = (Button) findViewById(R.id.scanStorageButton);
        themeToggleButton = (Button) findViewById(R.id.themeToggleButton);
        viewLogsButton = (Button) findViewById(R.id.viewLogsButton);
        videoSurface = (SurfaceView) findViewById(R.id.videoSurface);
        castPlaybackOverlay = findViewById(R.id.castPlaybackOverlay);
        castStopOverlay = (Button) findViewById(R.id.castStopOverlay);
    }

    private void bindNavigation() {
        findViewById(R.id.navHome).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(PAGE_HOME); }
        });
        findViewById(R.id.navMovies).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showMovies(""); }
        });
        findViewById(R.id.navCast).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(PAGE_CAST); }
        });
        findViewById(R.id.navSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(PAGE_SETTINGS); }
        });
        findViewById(R.id.searchButton).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showSearchPage(); }
        });
        buildSearchKeyboard();

        bindFilter(R.id.filterAll, "");
        bindFilter(R.id.filterMovie, "电影");
        bindFilter(R.id.filterSeries, "电视剧");
        bindFilter(R.id.filterVariety, "综艺");
        bindFilter(R.id.filterAnime, "动漫");
        bindFilter(R.id.filterDrama, "短剧");

        refreshSourcesButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refreshSources(); }
        });
        scanStorageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { scanStorage(); }
        });
        themeToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                TvTheme.toggle(MainActivity.this);
                recreate();
            }
        });
        viewLogsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showLogViewer(); }
        });
        castStopOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                runtime.getAirPlayReceiver().disconnectSession();
                Toast.makeText(MainActivity.this, "已退出 AirPlay 投屏", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindFilter(int id, final String filter) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showMovies(filter); }
        });
    }

    private void showPage(String page) {
        currentPage = page;
        homePage.setVisibility(PAGE_HOME.equals(page) ? View.VISIBLE : View.GONE);
        moviesPage.setVisibility(PAGE_MOVIES.equals(page) ? View.VISIBLE : View.GONE);
        searchPage.setVisibility(PAGE_SEARCH.equals(page) ? View.VISIBLE : View.GONE);
        castPage.setVisibility(PAGE_CAST.equals(page) ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(PAGE_SETTINGS.equals(page) ? View.VISIBLE : View.GONE);
        findViewById(R.id.navHome).setSelected(PAGE_HOME.equals(page));
        findViewById(R.id.navMovies).setSelected(PAGE_MOVIES.equals(page));
        findViewById(R.id.navCast).setSelected(PAGE_CAST.equals(page));
        findViewById(R.id.navSettings).setSelected(PAGE_SETTINGS.equals(page));
        if (PAGE_HOME.equals(page)) renderHome();
        render();
    }

    private void showMovies(String filter) {
        currentMovieFilter = filter;
        showPage(PAGE_MOVIES);
        setFilterSelection(filter);
        if ("短剧".equals(filter)) {
            renderDramaMovies();
            return;
        }
        List<SearchItem> filtered = filter(homeItems, filter);
        renderMovieGrid(filter.isEmpty() ? "最近更新" : filter, filtered);
    }

    private void renderDramaMovies() {
        if (moviesContent == null) return;
        moviesContent.removeAllViews();
        moviesContent.addView(sectionTitle("短剧目录 · " + dramaItems.size()));
        if (dramaItems.isEmpty()) {
            TextView empty = bodyText("使用顶部搜索输入剧名：匹配到的短剧会显示在这里，"
                    + "并可继续从已启用片源中匹配播放线路。");
            empty.setPadding(0, dp(22), 0, 0);
            moviesContent.addView(empty);
            return;
        }
        appendGrid(moviesContent, dramaItems, gridColumns());
    }

    private void setFilterSelection(String filter) {
        findViewById(R.id.filterAll).setSelected(filter.isEmpty());
        findViewById(R.id.filterMovie).setSelected("电影".equals(filter));
        findViewById(R.id.filterSeries).setSelected("电视剧".equals(filter));
        findViewById(R.id.filterVariety).setSelected("综艺".equals(filter));
        findViewById(R.id.filterAnime).setSelected("动漫".equals(filter));
        findViewById(R.id.filterDrama).setSelected("短剧".equals(filter));
    }

    private void loadHome(boolean force) {
        if (homeRequestRunning || (homeLoaded && !force)) return;
        homeRequestRunning = true;
        homeLoading.setVisibility(View.VISIBLE);
        io.execute(new Runnable() {
            @Override public void run() {
                List<SearchItem> loaded;
                try {
                    loaded = new ArrayList<SearchItem>();
                    loaded.addAll(runtime.getStorageLibrary().home(36));
                    loaded.addAll(runtime.getContentService().home(8, 72));
                } catch (Exception ignored) {
                    loaded = runtime.getStorageLibrary().home(36);
                }
                final List<SearchItem> result = loaded;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        homeRequestRunning = false;
                        homeLoaded = true;
                        homeItems.clear();
                        homeItems.addAll(result);
                        renderHome();
                        if (PAGE_MOVIES.equals(currentPage)) showMovies(currentMovieFilter);
                    }
                });
            }
        });
    }

    private void renderHome() {
        if (homeContent == null) return;
        homeContent.removeAllViews();
        List<LibraryItem> history = runtime.getMediaLibrary().history();
        List<LibraryItem> favorites = runtime.getMediaLibrary().favorites();
        SearchItem spotlight = !history.isEmpty() ? history.get(0).toSearchItem()
                : (!favorites.isEmpty() ? favorites.get(0).toSearchItem()
                : (!homeItems.isEmpty() ? homeItems.get(0) : null));
        addFeaturedPanel(spotlight);
        addQuickBrowse();

        if (!history.isEmpty()) addLibrarySection("继续观看", history, true);

        if (!favorites.isEmpty()) addLibrarySection("我的收藏", favorites, false);

        if (!homeItems.isEmpty()) {
            addMediaSection("最近更新", homeItems, 24);
        } else {
            TextView empty = bodyText(homeRequestRunning
                    ? "正在从已启用片源加载内容…"
                    : "暂时没有首页内容，请在设置中刷新片源或通过网页添加配置源。");
            empty.setPadding(0, dp(28), 0, 0);
            homeContent.addView(empty);
        }
    }

    private void addFeaturedPanel(SearchItem item) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(28), dp(22), dp(22), dp(22));
        panel.setBackgroundDrawable(TvTheme.panel(this));
        panel.setClipChildren(false);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);

        featuredEyebrow = featuredText(12, TvTheme.secondary(this), true);
        featuredTitle = featuredText(32, TvTheme.primary(this), true);
        featuredTitle.setSingleLine(true);
        featuredTitle.setEllipsize(TextUtils.TruncateAt.END);
        featuredMeta = featuredText(14, TvTheme.secondary(this), false);
        featuredPlot = featuredText(14, TvTheme.secondary(this), false);
        featuredPlot.setMaxLines(2);
        featuredPlot.setEllipsize(TextUtils.TruncateAt.END);
        featuredPlot.setLineSpacing(0, 1.15f);

        copy.addView(featuredEyebrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        copy.addView(featuredTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        metaParams.topMargin = dp(3);
        copy.addView(featuredMeta, metaParams);
        LinearLayout.LayoutParams plotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        plotParams.topMargin = dp(5);
        copy.addView(featuredPlot, plotParams);
        panel.addView(copy, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        featuredPoster = new ImageView(this);
        featuredPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        featuredPoster.setBackgroundColor(TvTheme.soft(this));
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(122), dp(174));
        posterParams.leftMargin = dp(28);
        panel.addView(featuredPoster, posterParams);

        featuredPanel = panel;
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(218));
        panelParams.bottomMargin = dp(22);
        homeContent.addView(panel, panelParams);
        updateFeatured(item);
    }

    private TextView featuredText(int sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private void updateFeatured(SearchItem item) {
        if (featuredPanel == null || featuredTitle == null) return;
        if (item == null) {
            featuredEyebrow.setText(R.string.app_name);
            featuredTitle.setText(R.string.featured_preparing);
            featuredMeta.setText(runtime.getState().getStatusMessage());
            featuredPlot.setText("");
            featuredPoster.setImageDrawable(null);
            return;
        }
        featuredEyebrow.setText(safe(item.siteName).isEmpty()
                ? getString(R.string.featured_recommendation) : item.siteName);
        featuredTitle.setText(safe(item.name));
        featuredMeta.setText(joinMeta(item.typeName, item.year, item.area, item.remarks));
        featuredPlot.setText(safe(item.plot));
        featuredPoster.setImageDrawable(null);
        images.load(item.poster, featuredPoster);
        featuredPanel.animate().cancel();
        featuredPanel.setAlpha(0.78f);
        featuredPanel.animate().alpha(1f).setDuration(150L).start();
    }

    private void addQuickBrowse() {
        homeContent.addView(sectionTitle("快速浏览"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        String[] labels = {"电影", "电视剧", "综艺", "动漫", "全部影视"};
        String[] filters = {"电影", "电视剧", "综艺", "动漫", ""};
        for (int i = 0; i < labels.length; i++) {
            final String filter = filters[i];
            Button button = actionButton(labels[i], 122);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showMovies(filter); }
            });
            row.addView(button);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        params.bottomMargin = dp(20);
        homeContent.addView(row, params);
    }

    private void addLibrarySection(String title, List<LibraryItem> items, final boolean resume) {
        homeContent.addView(sectionTitle(title));
        HorizontalScrollView scroll = horizontalTrack();
        LinearLayout track = (LinearLayout) scroll.getChildAt(0);
        int count = Math.min(16, items.size());
        for (int i = 0; i < count; i++) {
            final LibraryItem library = items.get(i);
            final SearchItem item = library.toSearchItem();
            MediaCardView card = card(item, library.positionMs, library.durationMs);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (resume && !library.episodeId.isEmpty()) {
                        resume(library);
                    } else {
                        openMedia(item);
                    }
                }
            });
            bindFavoriteShortcut(card, item);
            track.addView(card, cardParams());
        }
        addTrack(scroll);
    }

    private void addMediaSection(String title, List<SearchItem> items, int limit) {
        homeContent.addView(sectionTitle(title));
        HorizontalScrollView scroll = horizontalTrack();
        LinearLayout track = (LinearLayout) scroll.getChildAt(0);
        int count = Math.min(limit, items.size());
        for (int i = 0; i < count; i++) {
            final SearchItem item = items.get(i);
            MediaCardView card = card(item, 0, 0);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { openMedia(item); }
            });
            bindFavoriteShortcut(card, item);
            track.addView(card, cardParams());
        }
        addTrack(scroll);
    }

    private void addTrack(HorizontalScrollView scroll) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(312));
        params.bottomMargin = dp(26);
        homeContent.addView(scroll, params);
    }

    private HorizontalScrollView horizontalTrack() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setFillViewport(false);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setClipChildren(false);
        track.setPadding(dp(8), dp(10), dp(42), dp(8));
        scroll.addView(track, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void renderMovieGrid(String title, List<SearchItem> items) {
        moviesContent.removeAllViews();
        TextView heading = sectionTitle(title + " · " + items.size());
        moviesContent.addView(heading);
        if (items.isEmpty()) {
            TextView empty = bodyText(homeRequestRunning
                    ? "正在加载影视内容…" : "当前筛选没有结果，可使用顶部搜索进行全站检索。");
            empty.setPadding(0, dp(22), 0, 0);
            moviesContent.addView(empty);
            return;
        }
        appendGrid(moviesContent, items, gridColumns());
    }

    private void appendGrid(LinearLayout target, List<SearchItem> items, int columns) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(302));
                rowParams.bottomMargin = dp(14);
                target.addView(row, rowParams);
            }
            final SearchItem item = items.get(i);
            MediaCardView card = card(item, 0, 0);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { openMedia(item); }
            });
            bindFavoriteShortcut(card, item);
            row.addView(card, cardParams());
        }
    }

    private int gridColumns() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return Math.max(3, (int) ((widthDp - 250f) / 180f));
    }

    private List<SearchItem> filter(List<SearchItem> source, String filter) {
        if (filter == null || filter.isEmpty()) return new ArrayList<SearchItem>(source);
        List<SearchItem> result = new ArrayList<SearchItem>();
        for (SearchItem item : source) {
            String type = safe(item.typeName);
            if ("电视剧".equals(filter)) {
                if (type.contains("剧") || type.contains("连续")) result.add(item);
            } else if ("动漫".equals(filter)) {
                if (type.contains("动漫") || type.contains("动画")) result.add(item);
            } else if (type.contains(filter)) {
                result.add(item);
            }
        }
        return result;
    }

    private void showSearchPage() {
        showPage(PAGE_SEARCH);
        if (searchKeyboard.getChildCount() > 2) searchKeyboard.getChildAt(2).requestFocus();
        else searchKeyword.requestFocus();
    }

    private void buildSearchKeyboard() {
        searchKeyboard.removeAllViews();
        String[] keys = {"清空", "退格", "A", "B", "C", "D", "E", "F", "G", "H",
                "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
                "W", "X", "Y", "Z", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "搜索"};
        for (final String key : keys) {
            Button button = new Button(this);
            button.setText(key);
            button.setTextSize(key.length() > 1 ? 13 : 16);
            button.setTextColor(TvTheme.primary(this));
            button.setAllCaps(false);
            button.setFocusable(true);
            button.setBackgroundDrawable(TvTheme.focusable(this));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = key.length() > 1 ? dp(98) : dp(44);
            params.height = dp(42);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            if ("清空".equals(key) || "退格".equals(key) || "搜索".equals(key)) {
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2);
            }
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { pressSearchKey(key); }
            });
            searchKeyboard.addView(button);
        }
    }

    private void pressSearchKey(String key) {
        String current = searchKeyword.getText().toString();
        if ("清空".equals(key)) searchKeyword.setText("");
        else if ("退格".equals(key)) {
            if (!current.isEmpty()) searchKeyword.setText(current.substring(0, current.length() - 1));
        } else if ("搜索".equals(key)) {
            searchHandler.removeCallbacks(delayedSearch);
            searchFromKeyboard();
            return;
        } else {
            searchKeyword.append(key);
        }
        searchKeyword.setSelection(searchKeyword.length());
        searchHandler.removeCallbacks(delayedSearch);
        if (searchKeyword.length() > 0) searchHandler.postDelayed(delayedSearch, 450L);
        else {
            searchGeneration++;
            searchResults.removeAllViews();
            searchStatus.setText(R.string.search_initial_empty);
        }
    }

    private void searchFromKeyboard() {
        String keyword = searchKeyword.getText().toString().trim();
        if (keyword.isEmpty()) return;
        SearchQuery query = new SearchQuery();
        query.keyword = keyword;
        List<ConfigSource> ranked = runtime.getTvBoxRepository().getRankedLeafSources();
        if (!ranked.isEmpty()) query.sourceId = ranked.get(0).id;
        performSearch(query);
    }

    private void performSearch(final SearchQuery query) {
        showPage(PAGE_SEARCH);
        final int generation = ++searchGeneration;
        searchResults.removeAllViews();
        searchStatus.setText(getString(R.string.search_in_progress, query.keyword));
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final SearchResponse response = runtime.getSearchEngine().search(query);
                    runtime.sourceHealthChanged();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation != searchGeneration) return;
                            searchStatus.setText(getString(R.string.search_result_summary,
                                    query.keyword, response.items.size(), response.searchedSites));
                            renderGrid(searchResults, response.items,
                                    Math.max(2, gridColumns() - 2), "没有找到匹配内容");
                            if (response.partial) {
                                Toast.makeText(MainActivity.this, "部分站点超时或不可用", Toast.LENGTH_SHORT).show();
                            }
                            loadDramaSection(query.keyword, generation);
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation != searchGeneration) return;
                            searchStatus.setText("搜索失败");
                            showError("搜索失败", error);
                        }
                    });
                }
            }
        });
    }

    private void renderGrid(LinearLayout target, List<SearchItem> items, int columns,
                            String emptyMessage) {
        target.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = bodyText(emptyMessage);
            empty.setPadding(0, dp(22), 0, 0);
            target.addView(empty);
            return;
        }
        appendGrid(target, items, columns);
    }

    private void openMedia(final SearchItem item) {
        if (item.sourceId != null && item.sourceId.startsWith(DRAMA_SOURCE_PREFIX)) {
            openDrama(item);
            return;
        }
        Toast.makeText(this, "正在加载“" + item.name + "”", Toast.LENGTH_SHORT).show();        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final MediaDetail detail = runtime.getContentService()
                            .detail(item.sourceId, item.siteKey, item.vodId);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDetail(detail); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("详情加载失败", error); }
                    });
                }
            }
        });
    }

    /**
     * Adds a short-drama catalog section under the regular TVBox search results. It runs after the
     * site results are rendered so a late catalog reply can never wipe the search grid.
     */
    private void loadDramaSection(final String keyword, final int generation) {
        if (runtime.getDramaService().registry().enabledProviders().isEmpty()) return;
        io.execute(new Runnable() {
            @Override public void run() {
                final DramaSearchResult result = runtime.getDramaService().search("", keyword);
                if (!result.ok || result.items.isEmpty()) return;
                final List<SearchItem> entries = new ArrayList<SearchItem>();
                for (DramaItem item : result.items) entries.add(item.toSearchItem());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (generation != searchGeneration || !PAGE_SEARCH.equals(currentPage)) return;
                        dramaItems.clear();
                        dramaItems.addAll(entries);
                        String heading = "短剧目录 · " + entries.size()
                                + (result.warning.isEmpty() ? "" : "（" + result.warning + "）");
                        searchResults.addView(sectionTitle(heading));
                        appendGrid(searchResults, entries, Math.max(2, gridColumns() - 2));
                    }
                });
            }
        });
    }

    /** Loads a catalog entry, matches playback lines, then reuses the normal detail/play flow. */
    private void openDrama(final SearchItem entry) {
        final String providerId = entry.siteKey;
        final String dramaId = entry.vodId;
        Toast.makeText(this, "正在加载短剧“" + entry.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final DramaDetail detail = runtime.getDramaService().detail(providerId, dramaId);
                    if (detail != null && detail.directPlayable && !detail.episodes.isEmpty()) {
                        // A CMS catalog already carries playable episode URLs, so matching TVBox
                        // lines first would only add latency to a result we already have.
                        runOnUiThread(new Runnable() {
                            @Override public void run() { showDramaEpisodes(entry, detail); }
                        });
                        return;
                    }
                    final DramaLineResult lines = runtime.getDramaService().lines(
                            providerId, dramaId, "");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDramaDetail(entry, detail, lines); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("短剧加载失败", error); }
                    });
                }
            }
        });
    }

    /**
     * Episode picker for catalogs that publish direct episode URLs. Picking an episode plays it
     * straight from the catalog; "用片源线路播放" keeps the TVBox line flow available as a fallback.
     */
    private void showDramaEpisodes(final SearchItem entry, final DramaDetail detail) {
        final CharSequence[] labels = new CharSequence[detail.episodes.size()];
        for (int i = 0; i < detail.episodes.size(); i++) {
            DramaEpisode episode = detail.episodes.get(i);
            labels[i] = episode.name + "  ·  " + (i + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择集数（共 " + detail.episodes.size() + " 集）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playDramaEpisode(detail, detail.episodes.get(which));
                    }
                })
                .setNeutralButton("用片源线路播放", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        loadDramaLines(entry, detail);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadDramaLines(final SearchItem entry, final DramaDetail detail) {
        Toast.makeText(this, "正在匹配播放线路", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final DramaLineResult lines = runtime.getDramaService().lines(
                            entry.siteKey, entry.vodId, "");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDramaDetail(entry, detail, lines); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("线路匹配失败", error); }
                    });
                }
            }
        });
    }

    private void playDramaEpisode(final DramaDetail detail, final DramaEpisode episode) {
        Toast.makeText(this, "正在解析“" + episode.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final DramaPlayResult result = runtime.getDramaService().play(
                            detail.item.providerId, detail.item.dramaId, episode.index);
                    final MediaDetail media = dramaAsMedia(detail, result);
                    final PlaybackInfo info = new PlaybackInfo();
                    info.siteKey = detail.item.providerId;
                    info.title = result.title;
                    info.url = result.url;
                    info.sniffUrl = result.url;
                    info.direct = true;
                    info.headers.putAll(result.headers);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            MediaDetail.PlaySource source = media.playSources.get(0);
                            completePlayback(PendingPlayback.episode(result.title, info, 0,
                                    media, source, source.episodes.get(0)), result.url);
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("播放失败", error); }
                    });
                }
            }
        });
    }

    /** Wraps a direct drama episode in a MediaDetail so favorites and continue-watching keep working. */
    private MediaDetail dramaAsMedia(final DramaDetail detail, final DramaPlayResult result) {
        DramaItem item = detail.item;
        MediaDetail media = new MediaDetail();
        media.sourceId = DRAMA_SOURCE_PREFIX + item.providerId;
        media.siteKey = item.providerId;
        media.vodId = item.dramaId;
        media.name = joinMeta(item.title, result.episodeName);
        media.poster = item.cover;
        media.plot = item.intro;
        media.remarks = item.remark;
        media.typeName = joinMeta(item.category, "短剧");
        MediaDetail.PlaySource source = new MediaDetail.PlaySource();
        source.name = "短剧直连";
        MediaDetail.Episode picked = new MediaDetail.Episode();
        picked.name = result.episodeName.isEmpty() ? "第" + result.index + "集" : result.episodeName;
        picked.id = String.valueOf(result.index);
        source.episodes.add(picked);
        media.playSources.add(source);
        return media;
    }

    private void showDramaDetail(final SearchItem entry, final DramaDetail detail,
                                final DramaLineResult lines) {
        final DramaItem drama = detail == null ? null : detail.item;
        final SearchItem catalog = drama == null || drama.title.isEmpty()
                ? entry : drama.toSearchItem();
        if (lines == null || lines.lines.isEmpty()) {
            MediaDetail readOnly = new MediaDetail();
            readOnly.sourceId = catalog.sourceId;
            readOnly.siteKey = catalog.siteKey;
            readOnly.vodId = catalog.vodId;
            readOnly.name = catalog.name;
            readOnly.poster = catalog.poster;
            readOnly.plot = catalog.plot;
            readOnly.remarks = catalog.remarks;
            readOnly.typeName = catalog.typeName;
            showDetail(readOnly);
            String reason = lines == null ? "" : lines.error;
            if (!reason.isEmpty()) {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            }
            return;
        }
        DramaLine exact = null;
        for (DramaLine line : lines.lines) {
            if (line.isExact()) { exact = line; break; }
        }
        if (exact != null) {
            openDramaLine(drama, exact);
            return;
        }
        final DramaItem selected = drama;
        CharSequence[] labels = new CharSequence[lines.lines.size()];
        for (int i = 0; i < lines.lines.size(); i++) {
            DramaLine line = lines.lines.get(i);
            labels[i] = joinMeta(line.siteName, line.name, line.remarks);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择播放线路（未找到完全同名条目）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        openDramaLine(selected, lines.lines.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openDramaLine(final DramaItem drama, final DramaLine line) {
        Toast.makeText(this, "正在读取“" + line.siteName + "”的剧集", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final MediaDetail media = runtime.getContentService()
                            .detail(line.sourceId, line.siteKey, line.vodId);
                    if (drama != null) {
                        if (!drama.title.isEmpty()) media.name = drama.title;
                        if (!drama.cover.isEmpty()) media.poster = drama.cover;
                        if (!drama.intro.isEmpty()) media.plot = drama.intro;
                        if (!drama.remark.isEmpty()) media.remarks = drama.remark;
                        media.typeName = joinMeta(drama.category, media.typeName);
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDetail(media); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("剧集加载失败", error); }
                    });
                }
            }
        });
    }

    private void showDetail(final MediaDetail detail) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(20));

        TextView title = bodyText(detail.name);
        title.setTextColor(TvTheme.primary(this));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView meta = bodyText(joinMeta(detail.typeName, detail.year, detail.area, detail.siteName));
        meta.setPadding(0, dp(6), 0, 0);
        content.addView(meta);

        if (!safe(detail.plot).isEmpty()) {
            TextView plot = bodyText(detail.plot);
            plot.setMaxLines(4);
            plot.setEllipsize(TextUtils.TruncateAt.END);
            plot.setPadding(0, dp(12), 0, dp(6));
            content.addView(plot);
        }

        final Button favorite = actionButton("", 190);
        setFavoriteLabel(favorite, detail);
        content.addView(favorite);

        final AlertDialog[] holder = new AlertDialog[1];
        favorite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean added = runtime.getMediaLibrary().toggleFavorite(detail);
                favorite.setText(added ? "已收藏" : "加入收藏");
                renderHome();
            }
        });

        for (final MediaDetail.PlaySource source : detail.playSources) {
            TextView sourceTitle = sectionTitle(source.name.isEmpty() ? "播放线路" : source.name);
            sourceTitle.setPadding(0, dp(16), 0, dp(7));
            content.addView(sourceTitle);
            LinearLayout episodeRow = null;
            for (int i = 0; i < source.episodes.size(); i++) {
                if (i % 5 == 0) {
                    episodeRow = new LinearLayout(this);
                    episodeRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
                    rowParams.bottomMargin = dp(6);
                    content.addView(episodeRow, rowParams);
                }
                final MediaDetail.Episode episode = source.episodes.get(i);
                Button button = actionButton(episode.name, 116);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        if (holder[0] != null) holder[0].dismiss();
                        playEpisode(detail, source, episode, 0);
                    }
                });
                episodeRow.addView(button);
            }
        }

        if (detail.playSources.isEmpty()) {
            TextView empty = bodyText("该条目没有可用播放线路");
            empty.setPadding(0, dp(18), 0, 0);
            content.addView(empty);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        holder[0] = new AlertDialog.Builder(this).setView(scroll).create();
        holder[0].show();
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.78f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        favorite.requestFocus();
    }

    private void playEpisode(final MediaDetail detail, final MediaDetail.PlaySource source,
                             final MediaDetail.Episode episode, final int startPositionMs) {
        Toast.makeText(this, "正在解析“" + episode.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String title = detail.name + " · " + episode.name;
                    final PlaybackInfo info = runtime.getContentService().resolve(detail.sourceId,
                            detail.siteKey, source.name, episode.id, title);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            PendingPlayback pending = PendingPlayback.episode(title, info,
                                    startPositionMs, detail, source, episode);
                            if (info.direct) completePlayback(pending, info.url);
                            else startSniffing(pending);
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("播放失败", error); }
                    });
                }
            }
        });
    }

    private void resume(final LibraryItem item) {
        if (item.sourceId != null && item.sourceId.startsWith("drama:")) {
            openDrama(item.toSearchItem());
            return;
        }
        Toast.makeText(this, "继续播放“" + item.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String title = item.name + (item.episodeName.isEmpty()
                            ? "" : " · " + item.episodeName);
                    final PlaybackInfo info = runtime.getContentService().resolve(item.sourceId,
                            item.siteKey, item.playSource, item.episodeId, title);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            PendingPlayback pending = PendingPlayback.resume(title, info, item);
                            if (info.direct) completePlayback(pending, info.url);
                            else startSniffing(pending);
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("续播失败", error); }
                    });
                }
            }
        });
    }

    private void startSniffing(PendingPlayback pending) {
        if (pending.info.sniffUrl.isEmpty()) {
            showError("播放失败", new IllegalArgumentException(pending.info.error.isEmpty()
                    ? "配置没有可用解析器" : pending.info.error));
            return;
        }
        pendingPlayback = pending;
        Intent intent = new Intent(this, SniffingActivity.class);
        intent.putExtra(SniffingActivity.EXTRA_URL, pending.info.sniffUrl);
        intent.putExtra(SniffingActivity.EXTRA_USER_AGENT,
                header(pending.info.headers, "User-Agent"));
        startActivityForResult(intent, REQUEST_SNIFF_PLAYBACK);
    }

    private void completePlayback(PendingPlayback pending, String url) {
        if (pending.detail != null) {
            runtime.getMediaLibrary().start(pending.detail, pending.source.name,
                    pending.episode.id, pending.episode.name);
        } else if (pending.resume != null) {
            runtime.getMediaLibrary().start(pending.resume, pending.resume.playSource,
                    pending.resume.episodeId, pending.resume.episodeName);
        }
        runtime.getPlayerController().play(this, url, pending.title,
                pending.info.headers, pending.startPositionMs);
        renderHome();
    }

    private static String header(java.util.Map<String, String> headers, String name) {
        if (headers == null) return "";
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    private static void mergeSniffHeader(java.util.Map<String, String> headers, String name,
                                         Intent data, String extra) {
        if (headers == null || data == null || !header(headers, name).isEmpty()) return;
        String value = safe(data.getStringExtra(extra));
        if (!value.isEmpty()) headers.put(name, value);
    }

    private static final class PendingPlayback {
        String title;
        PlaybackInfo info;
        int startPositionMs;
        MediaDetail detail;
        MediaDetail.PlaySource source;
        MediaDetail.Episode episode;
        LibraryItem resume;

        static PendingPlayback episode(String title, PlaybackInfo info, int startPositionMs,
                                       MediaDetail detail, MediaDetail.PlaySource source,
                                       MediaDetail.Episode episode) {
            PendingPlayback value = new PendingPlayback();
            value.title = title;
            value.info = info;
            value.startPositionMs = startPositionMs;
            value.detail = detail;
            value.source = source;
            value.episode = episode;
            return value;
        }

        static PendingPlayback resume(String title, PlaybackInfo info, LibraryItem item) {
            PendingPlayback value = new PendingPlayback();
            value.title = title;
            value.info = info;
            value.startPositionMs = item.positionMs;
            value.resume = item;
            return value;
        }
    }

    private void bindFavoriteShortcut(MediaCardView card, final SearchItem item) {
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                boolean added = runtime.getMediaLibrary().toggleFavorite(item);
                Toast.makeText(MainActivity.this, added ? "已加入收藏" : "已取消收藏",
                        Toast.LENGTH_SHORT).show();
                renderHome();
                return true;
            }
        });
    }

    private void refreshSources() {
        AppLog.i("片源", "开始刷新全部配置源");
        refreshSourcesButton.setEnabled(false);
        refreshSourcesButton.setText("正在刷新…");
        runtime.getTvBoxRepository().refreshAllAsync(new com.nukacast.app.tvbox.TvBoxRepository.RefreshListener() {
            @Override public void onSourceRefreshed(int configs, int sites) {
                runtime.contentChanged();
            }
            @Override public void onRefreshComplete(final int configs, final int sites) {
                AppLog.i("片源", "配置源刷新完成：" + configs + " 个配置，" + sites + " 个站点");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshSourcesButton.setEnabled(true);
                        refreshSourcesButton.setText("刷新全部配置源");
                        Toast.makeText(MainActivity.this, "片源刷新完成", Toast.LENGTH_SHORT).show();
                        loadHome(true);
                    }
                });
            }
        });
    }

    private void stopActivePlayback() {
        if (runtime.getAirPlayReceiver().snapshot().sessionActive
                || "AirPlay 镜像".equals(runtime.getState().getActiveMedia())) {
            runtime.getAirPlayReceiver().disconnectSession();
        } else {
            runtime.getPlayerController().stop();
        }
    }

    private void scanStorage() {
        if (!ensureStoragePermission()) return;
        if (runtime.getStorageLibrary().isScanning()) return;
        scanStorageButton.setEnabled(false);
        scanStorageButton.setText(R.string.scanning_library);
        runtime.getStorageLibrary().scanAllAsync(new com.nukacast.app.storage.StorageLibrary.ScanListener() {
            @Override public void onComplete(final int mounts, final int files) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        scanStorageButton.setEnabled(true);
                        scanStorageButton.setText(R.string.scan_library);
                        storageSummary.setText(getString(R.string.storage_summary, mounts, files));
                        homeLoaded = false;
                        loadHome(true);
                    }
                });
            }
        });
    }

    private void startReceiverService() {
        Intent service = new Intent(this, NukaCastService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private boolean ensureStoragePermission() {
        if (hasStoragePermission()) return true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            } catch (RuntimeException unavailable) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_MANAGE_STORAGE);
            }
            Toast.makeText(this, "请允许 NukaCast 访问本机和 U 盘媒体文件",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return false;
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        AppState state = runtime.getState();
        DeviceProfile profile = runtime.getDeviceProfile();
        serviceStatus.setText(state.getStatusMessage());
        String address = runtime.getWebAddress();
        String host = address.replace("http://", "").replace(":" + NukaRuntime.CONTROL_PORT, "");
        networkStatus.setText("0.0.0.0".equals(host) ? "网络未连接" : "已联网 · " + host);
        webAddress.setText(address);
        deviceSummary.setText(profile.displaySummary());
        codecSummary.setText(profile.codecSummary());
        sourceSummary.setText(String.format(Locale.CHINA, "%d 个配置源 · %d 个站点",
                state.getSourceCount(), state.getEnabledSiteCount()));
        storageSummary.setText(String.format(Locale.CHINA, "%d 个挂载 · %d 个媒体文件",
                runtime.getStorageLibrary().mounts().size(),
                runtime.getStorageLibrary().entries().size()));
        themeToggleButton.setText(TvTheme.isLight(this) ? "切换为深色" : "切换为浅色");

        AirPlayReceiver.Snapshot airplay = runtime.getAirPlayReceiver().snapshot();
        airplayState.setText(airplay.sessionActive ? "正在接收镜像" : castState(airplay));
        boolean hasMedia = state.getActiveMedia() != null && !state.getActiveMedia().isEmpty();
        boolean airplayMedia = airplay.sessionActive
                || "AirPlay 镜像".equals(state.getActiveMedia());
        boolean overlayWasVisible = castPlaybackOverlay.getVisibility() == View.VISIBLE;
        appShell.setVisibility(hasMedia ? View.GONE : View.VISIBLE);
        videoSurface.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        castPlaybackOverlay.setVisibility(airplayMedia ? View.VISIBLE : View.GONE);
        if (airplayMedia && !overlayWasVisible) {
            castStopOverlay.requestFocus();
        } else if (!airplayMedia && overlayWasVisible) {
            findViewById(R.id.navCast).requestFocus();
        }
    }

    private String castState(AirPlayReceiver.Snapshot snapshot) {
        if ("error".equals(snapshot.state)) return "启动失败 · " + safe(snapshot.error);
        if ("ready".equals(snapshot.state)) return "等待连接";
        if ("starting".equals(snapshot.state) || "restarting".equals(snapshot.state)) {
            return "正在准备";
        }
        if ("stopped".equals(snapshot.state)) return "接收器已停止";
        return safe(snapshot.state);
    }

    private MediaCardView card(SearchItem item, int positionMs, int durationMs) {
        MediaCardView card = new MediaCardView(this, item, positionMs, durationMs, images);
        card.setPreviewListener(new MediaCardView.PreviewListener() {
            @Override public void onPreview(SearchItem focused) {
                if (PAGE_HOME.equals(currentPage)) updateFeatured(focused);
            }
        });
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(164), dp(292));
        params.setMargins(dp(3), dp(4), dp(13), dp(4));
        return params;
    }

    private TextView sectionTitle(String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(TvTheme.primary(this));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        return title;
    }

    private TextView bodyText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TvTheme.secondary(this));
        text.setTextSize(14);
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private Button actionButton(String label, int widthDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TvTheme.primary(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackgroundDrawable(TvTheme.focusable(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(42));
        params.setMargins(0, 0, dp(8), dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private void setFavoriteLabel(Button button, MediaDetail detail) {
        boolean favorite = runtime.getMediaLibrary()
                .isFavorite(detail.sourceId, detail.siteKey, detail.vodId);
        button.setText(favorite ? "已收藏" : "加入收藏");
    }

    private String joinMeta(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (result.length() > 0) result.append(" · ");
            result.append(value.trim());
        }
        return result.toString();
    }

    private void showError(String prefix, Throwable error) {
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        AppLog.e("界面", prefix + "：" + detail, error);
        Toast.makeText(this, prefix + "：" + detail, Toast.LENGTH_LONG).show();
    }

    private void showLogViewer() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));
        root.setBackgroundResource(R.drawable.bg_panel);

        TextView title = sectionTitle("错误日志 · 最近 500 条");
        root.addView(title);

        final TextView logText = bodyText("");
        logText.setTextSize(13);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(10), dp(12), dp(18));

        final AppLog.Level[] selected = new AppLog.Level[] {null};
        final List<Button> filters = new ArrayList<Button>();
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"全部", "调试", "信息", "警告", "错误"};
        final AppLog.Level[] levels = {null, AppLog.Level.DEBUG, AppLog.Level.INFO,
                AppLog.Level.WARN, AppLog.Level.ERROR};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button filter = actionButton(labels[i], 92);
            filter.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selected[0] = levels[index];
                    updateLogText(logText, selected[0]);
                    for (int j = 0; j < filters.size(); j++) {
                        filters.get(j).setSelected(j == index);
                    }
                }
            });
            filters.add(filter);
            filterBar.addView(filter);
        }
        filters.get(0).setSelected(true);
        root.addView(filterBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_focusable);
        scroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, dp(8), 0, dp(12));
        root.addView(scroll, scrollParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        final AlertDialog[] holder = new AlertDialog[1];
        Button export = actionButton("导出诊断包", 150);
        Button clear = actionButton("清空", 110);
        Button close = actionButton("关闭", 110);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                exportDiagnostics();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppLog.clear();
                updateLogText(logText, selected[0]);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (holder[0] != null) holder[0].dismiss();
            }
        });
        actions.addView(export);
        actions.addView(clear);
        actions.addView(close);
        root.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        updateLogText(logText, null);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        holder[0] = dialog;
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                Window window = holder[0].getWindow();
                if (window != null) window.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                filters.get(0).requestFocus();
            }
        });
        dialog.show();
    }

    /**
     * Writes the same diagnostic bundle the web console downloads next to the app's files, and
     * shows the full path so it can be pulled with adb or a file manager on the TV.
     */
    private void exportDiagnostics() {
        final File target = com.nukacast.app.diagnostics.DiagnosticsReport.write(this, runtime, AppLog.Level.ERROR);
        if (target == null) {
            Toast.makeText(this, "导出失败，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }
        AppLog.i("诊断", "已导出诊断包：" + target.getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle("诊断包已导出")
                .setMessage("日志与设备状态已写入：\n" + target.getAbsolutePath()
                        + "\n\n可以用文件管理器打开，或在电脑上执行：\n"
                        + "adb pull " + target.getAbsolutePath())
                .setPositiveButton("知道了", null)
                .show();
    }

    private void updateLogText(TextView view, AppLog.Level level) {
        String value = AppLog.format(level);
        view.setText(value.isEmpty() ? "当前级别暂无日志" : value);
    }

    private void showPreviousCrash() {
        final String report = CrashReporter.read(this);
        if (report.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("检测到上次崩溃")
                .setMessage(report)
                .setPositiveButton("清除记录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        CrashReporter.clear(MainActivity.this);
                    }
                })
                .setNegativeButton("保留", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemUi() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (android.os.Build.VERSION.SDK_INT >= 19) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
