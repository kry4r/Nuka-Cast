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
    /**
     * The activity currently on screen, for the layout inspector.
     *
     * <p>Finding out that a row was cut off used to require photographing the TV; the debug endpoint
     * now reports the bounds of every visible view instead.
     */
    private static java.lang.ref.WeakReference<MainActivity> onScreen;

    /** The activity currently on screen, or null. Held weakly: a static Activity field is a leak. */
    public static MainActivity onScreen() {
        return onScreen == null ? null : onScreen.get();
    }

    /** Runs {@code body} on the UI thread and waits, so a debug request sees a settled layout. */
    public <T> T onUiThreadNow(final java.util.concurrent.Callable<T> body) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try {
                return body.call();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }
        final java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<T>(body);
        runOnUiThread(task);
        try {
            return task.get(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
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
    private LinearLayout movieFilters;
    /** Category browsing state: which site and category the movies page is showing. */
    private final java.util.List<com.nukacast.app.tvbox.model.Category> browseCategories =
            new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
    private String browseSiteKey = "";
    private String browseCategoryId = "";
    private int browsePage;
    private boolean browseLoading;
    private boolean browseMode;
    private final java.util.concurrent.ExecutorService browseIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.List<com.nukacast.app.tvbox.model.Category> browseSites =
            new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
    private LinearLayout searchResults;
    private LinearLayout searchKeyboard;
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
    private com.nukacast.app.ui.PlayerHudView playerHud;
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
        onScreen = new java.lang.ref.WeakReference<MainActivity>(this);
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
        if (onScreen != null && onScreen.get() == this) onScreen = null;
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
        if (isFullScreenMedia()) {
            // Any key brings the HUD back; it fades by itself so the picture stays clean.
            if (playerHud != null) playerHud.reveal();
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                runtime.getPlayerController().toggle();
                refreshPlayerHud();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                runtime.getPlayerController().seekBy(30000);
                refreshPlayerHud();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                runtime.getPlayerController().seekBy(-10000);
                refreshPlayerHud();
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE) {
            runtime.getPlayerController().toggle();
            refreshPlayerHud();
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
        movieFilters = (LinearLayout) findViewById(R.id.movieFilters);
        if (movieFilters != null) movieFilters.setClipChildren(false);
        searchResults = (LinearLayout) findViewById(R.id.searchResults);
        searchKeyboard = (LinearLayout) findViewById(R.id.searchKeyboard);
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
        playerHud = new com.nukacast.app.ui.PlayerHudView(this);
        ((android.widget.FrameLayout) findViewById(R.id.rootFrame)).addView(playerHud,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
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

        buildMovieFilterRow();

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

    }

    private String movieFilterSelected = "";

    /** The movies page: category browsing first, then the home feed, then short dramas. */
    private void buildMovieFilterRow() {
        if (movieFilters == null) return;
        movieFilters.removeAllViews();
        addMovieFilter("分类浏览", "");
        addMovieFilter("最近更新", "首页");
        addMovieFilter("短剧", "短剧");
        setFilterSelection("");
    }

    private void addMovieFilter(String label, final String filter) {
        Button button = actionButton(label, 0);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showMovies(filter); }
        });
        movieFilters.addView(button);
    }

    /** Current page name, so the inspector can say which screen was dumped. */
    public String currentPageName() {
        return currentPage;
    }

    /** Page identifiers the debug endpoint accepts, mapped to the internal names. */
    public static java.util.List<String> pageNames() {
        return java.util.Arrays.asList("home", "movies", "search", "cast", "settings");
    }

    /** Switches page by name; used by the debug API so a screen can be inspected on demand. */
    public void showPageByName(String name) {
        if (name == null) return;
        if ("movies".equals(name)) showPage(PAGE_MOVIES);
        else if ("search".equals(name)) showPage(PAGE_SEARCH);
        else if ("cast".equals(name)) showPage(PAGE_CAST);
        else if ("settings".equals(name)) showPage(PAGE_SETTINGS);
        else showPage(PAGE_HOME);
    }

    /** Scrolls the page currently on screen; positive values scroll down. */
    public boolean scrollCurrentPage(int delta) {
        android.view.View focused = getCurrentFocus();
        android.view.ViewGroup parent = focused instanceof android.view.ViewGroup
                ? (android.view.ViewGroup) focused : null;
        android.view.View node = focused;
        android.view.View scrollable = null;
        while (node != null && scrollable == null) {
            if (node instanceof ScrollView) scrollable = node;
            else if (node.getParent() instanceof android.view.View) {
                node = (android.view.View) node.getParent();
            } else {
                node = null;
            }
        }
        if (scrollable == null) {
            for (String id : new String[]{"homeScroll", "moviesScroll", "settingsScroll"}) {
                int resource = getResources().getIdentifier(id, "id", getPackageName());
                android.view.View candidate = resource == 0 ? null : findViewById(resource);
                if (candidate instanceof ScrollView && candidate.getVisibility() == View.VISIBLE) {
                    scrollable = candidate;
                    break;
                }
            }
        }
        if (!(scrollable instanceof ScrollView)) return false;
        if (parent != null && parent != scrollable) parent.requestChildFocus(focused, focused);
        ((ScrollView) scrollable).smoothScrollBy(0, delta);
        return true;
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
        // The type filters belong to the movies page; leaving it highlighted made it look as if a
        // filter were still applied while a different page was on screen.
        if (!PAGE_MOVIES.equals(page)) setFilterSelection("");
        if (PAGE_HOME.equals(page)) renderHome();
        render();
    }

    private void showMovies(String filter) {
        currentMovieFilter = filter;
        showPage(PAGE_MOVIES);
        setFilterSelection(filter);
        if ("短剧".equals(filter)) {
            browseMode = false;
            renderDramaMovies();
            return;
        }
        if ("首页".equals(filter)) {
            browseMode = false;
            if (homeItems.isEmpty() && !homeRequestRunning) loadHome(false);
            renderMovieGrid("最近更新", homeItems);
            return;
        }
        // Default: the category browser.
        browseMode = true;
        loadCategoryBar();
    }

    /**
     * Loads the category list of every enabled CMS site and shows the browser.
     *
     * <p>This is what TVBox is used for most of the time: pick a site, pick a category, page through
     * it. Without it the movies page could only mirror the home feed, so an empty home feed made the
     * whole page look broken.
     */
    private void loadCategoryBar() {
        if (browseSites.isEmpty()) {
            moviesContent.removeAllViews();
            moviesContent.addView(sectionTitle("正在读取分类…"));
            browseIo.execute(new Runnable() {
                @Override public void run() {
                    final List<com.nukacast.app.tvbox.model.Category> sites =
                            new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
                    for (com.nukacast.app.tvbox.model.Category category : browseCategories()) {
                        sites.add(category);
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            browseSites.clear();
                            browseSites.addAll(sites);
                            if (browseSites.isEmpty()) {
                                moviesContent.removeAllViews();
                                moviesContent.addView(sectionTitle("可用分类"));
                                moviesContent.addView(bodyText(
                                        "当前片源没有提供分类。请在网页的“源管理”里体检片源，"
                                                + "或添加一个带分类的接口站点。"));
                                return;
                            }
                            if (browseSiteKey.isEmpty()) {
                                browseSiteKey = preferredSiteKey();
                            }
                            if (browseCategoryId.isEmpty()) {
                                for (com.nukacast.app.tvbox.model.Category category : browseSites) {
                                    if (category.siteKey.equals(browseSiteKey)) {
                                        browseCategoryId = category.id;
                                        break;
                                    }
                                }
                                if (browseCategoryId.isEmpty()) {
                                    browseCategoryId = browseSites.get(0).id;
                                    browseSiteKey = browseSites.get(0).siteKey;
                                }
                            }
                            renderCategoryBar();
                        }
                    });
                }
            });
            return;
        }
        renderCategoryBar();
    }

    /**
     * Every category of every enabled CMS site.
     *
     * <p>The first version built this from the site chips it had already rendered, which were empty
     * on the first call, so the page always claimed "no categories". The sites come from the
     * repository directly instead.
     */
    private List<com.nukacast.app.tvbox.model.Category> browseCategories() {
        List<com.nukacast.app.tvbox.model.Category> all =
                new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (com.nukacast.app.tvbox.model.Category site : enabledCmsSites()) {
            List<com.nukacast.app.tvbox.model.Category> categories =
                    runtime.getContentService().categories(site.sourceId, site.siteKey);
            for (com.nukacast.app.tvbox.model.Category category : categories) {
                if (seen.add(site.siteKey + "|" + category.id)) all.add(category);
            }
        }
        return all;
    }

    /** Enabled plain-API sites, as chips carrying their key and source. */
    private List<com.nukacast.app.tvbox.model.Category> enabledCmsSites() {
        List<com.nukacast.app.tvbox.model.Category> chips =
                new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
        for (com.nukacast.app.tvbox.model.TvBoxConfig.Site site
                : runtime.getTvBoxRepository().getEnabledSites()) {
            // Plugin sites cannot be browsed on this device, and live-only entries have no categories.
            if (site.type == 3) continue;
            com.nukacast.app.tvbox.model.Category chip =
                    new com.nukacast.app.tvbox.model.Category(site.key, site.name);
            chip.siteKey = site.key;
            chip.sourceId = site.sourceId;
            chips.add(chip);
        }
        return chips;
    }

    /** One chip per enabled CMS site, carrying the site key in {@code id}. */
    private List<com.nukacast.app.tvbox.model.Category> siteChips() {
        List<com.nukacast.app.tvbox.model.Category> chips =
                new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
        for (com.nukacast.app.tvbox.model.Category category : browseSites) {
            boolean known = false;
            for (com.nukacast.app.tvbox.model.Category chip : chips) {
                if (chip.id.equals(category.siteKey)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            com.nukacast.app.tvbox.model.Category chip =
                    new com.nukacast.app.tvbox.model.Category(
                            category.siteKey, category.siteName);
            chip.siteKey = category.siteKey;
            chip.sourceId = category.sourceId;
            chips.add(chip);
        }
        return chips;
    }

    /** The first site the health sweep found working, so the browser opens on something usable. */
    private String preferredSiteKey() {
        for (com.nukacast.app.tvbox.model.Category category : browseSites) {
            if (runtime.getSiteHealthStore() != null) {
                for (com.nukacast.app.tvbox.SiteHealthStore.Verdict verdict
                        : runtime.getSiteHealthStore().snapshot()) {
                    if (verdict.ok && verdict.siteKey.equals(category.siteKey)) {
                        return category.siteKey;
                    }
                }
            }
        }
        return browseSites.isEmpty() ? "" : browseSites.get(0).siteKey;
    }

    /** Site row + category row, then the first page of the selected category. */
    private void renderCategoryBar() {
        moviesContent.removeAllViews();
        moviesContent.addView(sectionTitle("片源"));
        LinearLayout siteRow = new LinearLayout(this);
        siteRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final com.nukacast.app.tvbox.model.Category chip : siteChips()) {
            Button button = actionButton(chip.name, 0);
            button.setSelected(chip.id.equals(browseSiteKey));
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    browseSiteKey = chip.id;
                    browseCategoryId = "";
                    loadCategoryBar();
                }
            });
            siteRow.addView(button);
        }
        moviesContent.addView(siteRow);

        moviesContent.addView(sectionTitle("分类"));
        android.widget.HorizontalScrollView bar = new android.widget.HorizontalScrollView(this);
        bar.setHorizontalScrollBarEnabled(false);
        LinearLayout categoryRow = new LinearLayout(this);
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final com.nukacast.app.tvbox.model.Category category : browseSites) {
            if (!category.siteKey.equals(browseSiteKey)) continue;
            Button button = actionButton(category.name, 0);
            button.setSelected(category.id.equals(browseCategoryId));
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    browseCategoryId = category.id;
                    browsePage = 0;
                    loadCategoryPage(1);
                }
            });
            categoryRow.addView(button);
        }
        bar.addView(categoryRow, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        android.widget.LinearLayout.LayoutParams barParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        barParams.bottomMargin = dp(6);
        bar.setBackgroundDrawable(null);
        moviesContent.addView(bar, barParams);

        if (!browseCategoryId.isEmpty()) {
            for (com.nukacast.app.tvbox.model.Category category : browseSites) {
                if (category.siteKey.equals(browseSiteKey) && category.id.equals(browseCategoryId)) {
                    loadCategoryPage(Math.max(1, browsePage + 1));
                    break;
                }
            }
        }
    }

    /**
     * Fetches one page of the selected category and shows it.
     *
     * <p>The category probing happens entirely on the background thread: an earlier version retried
     * from the UI callback, which repainted the grid several times per load and left half-drawn rows
     * on screen (a screenshot showed a row of empty cards above the real ones).
     */
    private void loadCategoryPage(final int page) {
        if (browseLoading) return;
        browseLoading = true;
        final String siteKey = browseSiteKey;
        final String sourceId = sourceIdOf(siteKey);
        if (page <= 1) {
            moviesContent.removeAllViews();
            moviesContent.addView(sectionTitle("加载中…"));
        }
        browseIo.execute(new Runnable() {
            @Override public void run() {
                List<SearchItem> items = new java.util.ArrayList<SearchItem>();
                String failure = "";
                String categoryId = browseCategoryId;
                int attempts = 0;
                while (true) {
                    try {
                        List<SearchItem> fetched = runtime.getContentService()
                                .browse(sourceId, siteKey, categoryId, page);
                        items = fetched == null ? new java.util.ArrayList<SearchItem>() : fetched;
                    } catch (Exception error) {
                        failure = error.getMessage() == null ? "加载失败" : error.getMessage();
                        break;
                    }
                    // Measured on real configs: some declared categories return one or two records
                    // while their neighbours return thousands, so a thin first page moves on.
                    if (page > 1 || items.size() >= 3 || attempts >= 8) break;
                    String next = nextCategoryAfter(siteKey, categoryId);
                    if (next == null || next.equals(categoryId)) break;
                    categoryId = next;
                    attempts++;
                }
                final List<SearchItem> result = items;
                final String reason = failure;
                final String chosen = categoryId;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        browseLoading = false;
                        moviesContent.removeAllViews();
                        if (!reason.isEmpty()) {
                            moviesContent.addView(sectionTitle("分类加载失败"));
                            moviesContent.addView(bodyText(reason));
                            return;
                        }
                        browseCategoryId = chosen;
                        browsePage = page;
                        for (SearchItem item : result) {
                            if (!homeItemsContains(item)) homeItems.add(item);
                        }
                        renderCategoryGrid(siteKey, chosen, page, result);
                    }
                });
            }
        });
    }

    /** The category after {@code currentId} on the same site, or null when there is none. */
    private String nextCategoryAfter(String siteKey, String currentId) {
        boolean afterCurrent = false;
        for (com.nukacast.app.tvbox.model.Category category : browseSites) {
            if (!category.siteKey.equals(siteKey)) continue;
            if (afterCurrent) return category.id;
            if (category.id.equals(currentId)) afterCurrent = true;
        }
        return null;
    }

    private boolean homeItemsContains(SearchItem item) {
        for (SearchItem existing : homeItems) {
            if (existing.vodId.equals(item.vodId) && existing.siteKey.equals(item.siteKey)) {
                return true;
            }
        }
        return false;
    }

    /** Renders the category header plus the accumulated grid and a “下一页” button. */
    private void renderCategoryGrid(String siteKey, String categoryId, int page,
                                    List<SearchItem> items) {
        String siteName = siteKey;
        String categoryName = browseCategoryId.isEmpty() ? categoryId : browseCategoryId;
        for (com.nukacast.app.tvbox.model.Category category : browseSites) {
            if (category.siteKey.equals(siteKey)) siteName = category.siteName;
            if (category.siteKey.equals(siteKey) && category.id.equals(categoryName)) {
                categoryName = category.name;
            }
        }
        if (page <= 1) {
            moviesContent.removeAllViews();
            moviesContent.addView(sectionTitle(siteName + " · " + categoryName));
        } else {
            // Remove the previous “下一页” button before appending.
            if (loadMoreRow != null) {
                moviesContent.removeView(loadMoreRow);
                loadMoreRow = null;
            }
        }
        if (items.isEmpty() && page <= 1) {
            moviesContent.addView(bodyText("该分类没有返回内容，可换一个分类或站点。"));
            return;
        }
        appendGrid(moviesContent, items, gridColumns());
        if (!items.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            loadMoreRow = row;
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button more = actionButton("下一页（第 " + (page + 1) + " 页）", 0);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { loadCategoryPage(browsePage + 1); }
            });
            row.addView(more);
            moviesContent.addView(row);
            if (page == 1) more.requestFocus();
        }
    }

    /** The “下一页” row currently shown, so it can be replaced instead of stacking up. */
    private LinearLayout loadMoreRow;
    private String sourceIdOf(String siteKey) {
        for (com.nukacast.app.tvbox.model.Category category : browseSites) {
            if (category.siteKey.equals(siteKey)) return category.sourceId;
        }
        return "";
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
        movieFilterSelected = filter == null ? "" : filter;
        if (movieFilters == null) return;
        String wanted = movieFilterSelected.isEmpty() ? "分类浏览"
                : ("首页".equals(movieFilterSelected) ? "最近更新" : movieFilterSelected);
        for (int i = 0; i < movieFilters.getChildCount(); i++) {
            View child = movieFilters.getChildAt(i);
            if (!(child instanceof Button)) continue;
            child.setSelected(wanted.contentEquals(((Button) child).getText()));
        }
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
        panel.setPadding(dp(20), dp(16), dp(16), dp(16));
        panel.setBackgroundDrawable(TvTheme.panel(this));
        panel.setClipChildren(false);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);

        featuredEyebrow = featuredText(10, TvTheme.secondary(this), true);
        featuredTitle = featuredText(16, TvTheme.primary(this), true);
        featuredTitle.setSingleLine(true);
        featuredTitle.setEllipsize(TextUtils.TruncateAt.END);
        featuredMeta = featuredText(11, TvTheme.secondary(this), false);
        featuredPlot = featuredText(11, TvTheme.secondary(this), false);
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
        // 150dp instead of 218dp: the hero used to eat the top 40% of a 1080p screen and pushed
        // every row below the fold.
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(104));
        panelParams.bottomMargin = dp(10);
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
        // Referer matters on hotlink-protected CDNs, same as the grid cards.
        images.load(item.poster, featuredPoster, refererOf(item.poster));
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
            Button button = actionButton(labels[i], 84);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showMovies(filter); }
            });
            row.addView(button);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        params.bottomMargin = dp(8);
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
                LinearLayout.LayoutParams.MATCH_PARENT, dp(172));
        params.bottomMargin = dp(6);
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

    private static String refererOf(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash + 1);
    }

    private void appendGrid(LinearLayout target, List<SearchItem> items, int columns) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(172));
                rowParams.bottomMargin = dp(6);
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
        // Column count follows the real card width, so the grid never wastes a whole column.
        return Math.max(3, (int) ((widthDp - 190f) / 104f));
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

    /** Rows of the search keyboard; every row is weighted so it always fits the panel width. */
    private static final String[][] KEYBOARD_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
            {"Z", "X", "C", "V", "B", "N", "M", "退格"},
            {"清空", "搜索"},
    };

    /**
     * Builds the on-screen keyboard as weighted rows.
     *
     * <p>The previous version placed fixed-size buttons into a {@code GridLayout}: measured through
     * {@code /api/debug/layout}, the last keys of every row ended up 6 pixels wide and invisible
     * because the row did not fit its container ("很多都有遮挡看不到"). Weighted rows cannot overflow.
     */
    private void buildSearchKeyboard() {
        searchKeyboard.removeAllViews();
        for (int rowIndex = 0; rowIndex < KEYBOARD_ROWS.length; rowIndex++) {
            String[] row = KEYBOARD_ROWS[rowIndex];
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
            rowParams.topMargin = dp(6);
            rowView.setLayoutParams(rowParams);
            for (String key : row) {
                Button button = new Button(this);
                button.setText(key);
                button.setTextSize(key.length() > 1 ? 13 : 15);
                button.setTextColor(TvTheme.primary(this));
                button.setAllCaps(false);
                // Default button padding/clipping cut the tops of the letters off.
                button.setPadding(0, 0, 0, 0);
                button.setMinWidth(0);
                button.setMinHeight(0);
                button.setIncludeFontPadding(false);
                button.setGravity(android.view.Gravity.CENTER);
                button.setFocusable(true);
                button.setBackgroundDrawable(TvTheme.focusable(this));
                // Wide keys (清空/退格/搜索) take two slots so the row still adds up to its width.
                int slots = key.length() > 1 ? 2 : 1;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, slots);
                params.setMargins(dp(3), 0, dp(3), 0);
                button.setLayoutParams(params);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { pressSearchKey(key); }
                });
                rowView.addView(button);
            }
            searchKeyboard.addView(rowView);
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
        // A site task stuck in a plugin runtime can outlive the engine's own deadline. The status
        // line therefore has its own guard: the page says what is happening instead of claiming to
        // search forever, whatever the network is doing.
        searchResults.postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != searchGeneration) return;
                searchStatus.setText("搜索仍在进行，慢站点较多（" + query.keyword + "）…");
            }
        }, 12_000L);
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
        final AlertDialog[] holder = new AlertDialog[1];
        final View[] firstFocus = new View[1];

        // Line and episode selection are rendered by DetailScreen so the layout rules (chip sizes,
        // eight episodes per row, 12sp labels) live in one place instead of inside this activity.
        final int[] selectedLine = {0};
        final LinearLayout[] container = new LinearLayout[1];

        com.nukacast.app.ui.DetailScreen.EpisodeListener episodeListener = new com.nukacast.app.ui.DetailScreen.EpisodeListener() {
            @Override public void onEpisode(String lineName, int lineIndex,
                                            com.nukacast.app.ui.DetailScreen.MediaEntry entry) {
                MediaDetail.PlaySource source = detail.playSources.get(selectedLine[0]);
                MediaDetail.Episode episode = null;
                for (MediaDetail.Episode candidate : source.episodes) {
                    if (candidate.id.equals(entry.id)) {
                        episode = candidate;
                        break;
                    }
                }
                if (episode == null) return;
                if (holder[0] != null) holder[0].dismiss();
                playEpisode(detail, source, episode, 0);
            }
        };
        com.nukacast.app.ui.DetailScreen.LineListener lineListener = new com.nukacast.app.ui.DetailScreen.LineListener() {
            @Override public void onLine(int index) {
                selectedLine[0] = index;
                renderDetailBody(container[0], detail, index, firstFocus, holder,
                        episodeListener, this);
            }
        };

        LinearLayout dialog = new LinearLayout(this);
        dialog.setOrientation(LinearLayout.VERTICAL);
        container[0] = dialog;
        renderDetailBody(dialog, detail, 0, firstFocus, holder, episodeListener, lineListener);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(dialog, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        holder[0] = new AlertDialog.Builder(this).setView(scroll).create();
        holder[0].show();
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.62f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.74f));
        }
        if (firstFocus[0] != null) firstFocus[0].requestFocus();
    }

    /** Redraws the dialog body; used for the first render and when the line changes. */
    private void renderDetailBody(LinearLayout container, MediaDetail detail, int selectedLine,
                                  View[] firstFocus, AlertDialog[] holder,
                                  com.nukacast.app.ui.DetailScreen.EpisodeListener episodeListener,
                                  com.nukacast.app.ui.DetailScreen.LineListener lineListener) {
        container.removeAllViews();
        String meta = joinMeta(detail.typeName, detail.year, detail.area, detail.siteName);
        String[] lineNames = new String[detail.playSources.size()];
        com.nukacast.app.ui.DetailScreen.MediaEntry[][] episodes = new com.nukacast.app.ui.DetailScreen.MediaEntry[detail.playSources.size()][];
        for (int i = 0; i < detail.playSources.size(); i++) {
            MediaDetail.PlaySource source = detail.playSources.get(i);
            lineNames[i] = source.episodes.size() + " 集 · " + (source.name.isEmpty() ? "线路" : source.name);
            com.nukacast.app.ui.DetailScreen.MediaEntry[] entries = new com.nukacast.app.ui.DetailScreen.MediaEntry[source.episodes.size()];
            for (int j = 0; j < source.episodes.size(); j++) {
                MediaDetail.Episode episode = source.episodes.get(j);
                entries[j] = new com.nukacast.app.ui.DetailScreen.MediaEntry(episode.id, episode.name);
            }
            episodes[i] = entries;
        }
        LinearLayout body = com.nukacast.app.ui.DetailScreen.build(this, detail.name, meta, detail.plot, lineNames,
                selectedLine, episodes, episodeListener, lineListener, firstFocus);
        container.addView(body);
        firstFocus[0] = null;
    }

    private void playEpisode(final MediaDetail detail, final MediaDetail.PlaySource source,
                             final MediaDetail.Episode episode, final int startPositionMs) {
        Toast.makeText(this, "正在解析“" + episode.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String title = detail.name + " · " + episode.name;
                    final PlaybackInfo info = runtime.getContentService().resolvePlayable(
                            detail.sourceId, detail.siteKey, source.name, episode.id, detail.vodId,
                            title);
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
                    final PlaybackInfo info = runtime.getContentService().resolvePlayable(
                            item.sourceId, item.siteKey, item.playSource, item.episodeId,
                            item.vodId, title);
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
        boolean hudWasVisible = playerHud.isShowing();
        appShell.setVisibility(hasMedia ? View.GONE : View.VISIBLE);
        videoSurface.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (!hasMedia) {
            playerHud.hideNow();
        } else if (airplayMedia) {
            // Mirroring is watched, not operated: show the notice briefly, then stay out of the way.
            com.nukacast.app.airplay.AirPlayReceiver.Snapshot mirror =
                    runtime.getAirPlayReceiver().snapshot();
            if (!hudWasVisible) {
                playerHud.show("正在投屏",
                        mirror.videoWidth > 0 ? mirror.videoWidth + "×" + mirror.videoHeight + " · AirPlay 镜像"
                                : "AirPlay 镜像",
                        "按返回键结束投屏", false);
            }
        } else {
            String subtitle = safe(runtime.getState().getActiveMedia());
            com.nukacast.app.player.PlayerController.Snapshot playback =
                    runtime.getPlayerController().snapshot();
            if ("error".equals(playback.state)) {
                playerHud.showError(safe(playback.error).isEmpty() ? "播放失败" : playback.error);
            } else if (!hudWasVisible) {
                playerHud.show(subtitle, "", "按返回键退出播放 · 按菜单键显示控制", true);
            } else {
                playerHud.setProgress(playback.positionMs, playback.durationMs);
            }
        }
    }

    /** True while the app is showing full-screen video or receiving a mirror. */
    private boolean isFullScreenMedia() {
        String active = runtime.getState().getActiveMedia();
        return active != null && !active.isEmpty();
    }

    /** Keeps the HUD in step with playback after a key press. */
    private void refreshPlayerHud() {
        if (playerHud == null || !isFullScreenMedia()) return;
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        playerHud.setProgress(playback.positionMs, playback.durationMs);
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
        // The screen is 1920x1080 at density 2.0, i.e. 960x540 dp: cards of 132x210 dp left room for
        // barely two rows, which is why rows below the fold were reported as missing. 92x150 dp fits
        // seven columns and three rows in the same area.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(96), dp(158));
        params.setMargins(dp(2), dp(3), dp(8), dp(3));
        return params;
    }

    private TextView sectionTitle(String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(TvTheme.primary(this));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(2));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        return title;
    }

    private TextView bodyText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TvTheme.secondary(this));
        text.setTextSize(12);
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    private Button actionButton(String label, int widthDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TvTheme.primary(this));
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackgroundDrawable(TvTheme.focusable(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                widthDp > 0 ? dp(widthDp) : LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        params.setMargins(0, 0, dp(8), dp(4));
        button.setLayoutParams(params);
        if (widthDp <= 0) button.setPadding(dp(16), 0, dp(16), 0);
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
