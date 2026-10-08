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
    private static final String PAGE_LIVE = "live";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final PosterImageLoader images = new PosterImageLoader();
    private final List<SearchItem> homeItems = new ArrayList<SearchItem>();
    private static final String DRAMA_SOURCE_PREFIX = "drama:";

    private final List<SearchItem> dramaItems = new ArrayList<SearchItem>();
    /** Which drama catalogue and which page of its listing the 短剧 tab is showing. */
    private String dramaBrowseProviderId = "";
    private int dramaBrowsePage;
    private boolean dramaBrowseRunning;
    private NukaRuntime runtime;
    private View appShell;
    private View homePage;
    private View moviesPage;
    private View searchPage;
    private View castPage;
    private View settingsPage;
    private android.view.ViewGroup pageContainer;
    private LinearLayout homeContent;
    private LinearLayout moviesContent;
    private LinearLayout movieFilters;
    // ---- 直播 ----------------------------------------------------------------
    private Button navLive;
    private LinearLayout livePage;
    private LinearLayout liveSourceRow;
    private LinearLayout liveGroupRow;
    private LinearLayout liveChannelGrid;
    private TextView liveStatus;
    /** Programme list line of the focused channel, on its own so nothing overwrites it. */
    private TextView liveEpgLine;
    private final java.util.concurrent.ExecutorService liveIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Programme lists are fetched on their own thread, one at a time. */
    private final java.util.concurrent.ExecutorService epgIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Channel keys whose programme list is already being fetched. */
    private final java.util.Set<String> epgPending =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final java.util.List<com.nukacast.app.live.model.LiveSourceInfo> liveSources =
            new java.util.ArrayList<com.nukacast.app.live.model.LiveSourceInfo>();
    private com.nukacast.app.live.model.LiveCatalog liveCatalog;
    private String liveSourceId = "";
    private String liveGroupName = "";
    /** Channels of the group being watched, so up/down can zap during playback. */
    private final java.util.List<com.nukacast.app.live.model.LiveCatalog.Channel> livePlaying =
            new java.util.ArrayList<com.nukacast.app.live.model.LiveCatalog.Channel>();
    private int livePlayingIndex = -1;
    /** How many lines may be tried for one failed episode. */
    private static final int MAX_LINE_ATTEMPTS = 3;
    /** Channel pages are 120 entries: large playlists are far too big for one screen. */
    private int liveChannelPage;
    /** EPG lines per channel, so walking up and down the list does not refetch constantly. */
    private final java.util.Map<String, String> liveEpgLines = new java.util.HashMap<String, String>();
    /** Channel the viewer is currently on, so a late EPG answer cannot label the wrong one. */
    private String liveFocusedChannelId = "";
    /** Sources that failed this session, so the page can skip them. */
    private final java.util.Set<String> liveFailedSources = new java.util.HashSet<String>();
    private String liveLastError = "";
    /** Channel search: its own panel, query text and on-screen keyboard. */
    private LinearLayout liveSearchPanel;
    private LinearLayout liveSearchKeyboard;
    /** The source/group chip rows, hidden while the search keyboard is open (see toggleLiveSearch). */
    private View liveSourceRowHolder;
    private View liveGroupRowHolder;
    private TextView liveSearchQuery;
    private String liveSearchText = "";
    private boolean liveSearching;
    /** True while the live source list is being read, so the page does not start several loads. */
    private boolean liveLoading;
    /** Guards the home page category rows against concurrent loads. */
    private boolean homeCategoryLoading;
    /** Where the home page category rows are rendered. */
    private LinearLayout homeCategoryContainer;
    private long liveSwitchAt;
    /** When the source list was last read, so re-entering the page does not refetch constantly. */
    private long liveLoadedAt;
    /** Consecutive automatic channel switches after failures, reset on a successful play. */
    private int liveAutoSwitch;
    /** Category browsing state: which site and category the movies page is showing. */
    private final java.util.List<com.nukacast.app.tvbox.model.Category> browseCategories =
            new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
    private String browseSiteKey = "";
    private String browseCategoryId = "";
    /** 年份/地区/语言 selection of the browse page (matched on the device, not by the site). */
    private com.nukacast.app.tvbox.BrowseFilter browseFilter = new com.nukacast.app.tvbox.BrowseFilter();
    private int browsePage;
    private boolean browseLoading;
    private boolean browseMode;
    private final java.util.concurrent.ExecutorService browseIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.List<com.nukacast.app.tvbox.model.Category> browseSites =
            new java.util.ArrayList<com.nukacast.app.tvbox.model.Category>();
    private LinearLayout searchResults;
    private LinearLayout searchKeyboard;
    private LinearLayout searchRecentRow;
    /** Recent keywords, newest first; kept in preferences so they survive a restart. */
    private final java.util.List<String> recentSearches = new java.util.ArrayList<String>();
    private EditText searchKeyword;
    private TextView searchStatus;
    private TextView homeLoading;
    private TextView serviceStatus;
    private TextView networkStatus;
    private TextView webAddress;
    private TextView airplayState;
    private TextView dlnaState;
    private TextView deviceSummary;
    private TextView codecSummary;
    private TextView sourceSummary;
    private TextView storageSummary;
    private TextView updateSummary;
    private Button checkUpdateButton;
    private Button openReleaseButton;
    private View featuredPanel;
    /** The title the hero panel is showing, so a click on it can open that title. */
    private SearchItem featuredItem;
    private TextView featuredEyebrow;
    private TextView featuredTitle;
    private TextView featuredMeta;
    private TextView featuredPlot;
    private ImageView featuredPoster;
    private SurfaceView videoSurface;
    private com.nukacast.app.ui.PlayerHudView playerHud;
    /** 字幕行：独立于 HUD，HUD 自动隐藏时字幕仍然显示。 */
    private com.nukacast.app.ui.SubtitleOverlay subtitleOverlay;
    private Button refreshSourcesButton;
    private Button scanStorageButton;
    private Button themeToggleButton;
    private Button autoNextButton;
    private Button qualityButton;
    private Button decoderButton;
    /**
     * Playback choices (auto-next, quality) shown on the settings page.
     *
     * <p>Created on demand: an Activity field initialiser runs before the base context is attached, and
     * touching it there crashed the app on startup (ContextWrapper.getApplicationContext() on a null
     * base).
     */
    private com.nukacast.app.player.PlaybackSettings playbackSettings;

    private com.nukacast.app.player.PlaybackSettings playbackSettings() {
        if (playbackSettings == null) {
            playbackSettings = new com.nukacast.app.player.PlaybackSettings(this);
        }
        return playbackSettings;
    }
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

    /** Bumped whenever the channel grid is rebuilt, to ignore focus events of removed buttons. */
    private int liveListGeneration;

    /** Digits typed on the live page, waiting to be turned into a channel number. */
    private String liveJumpDigits = "";
    private final Handler liveJumpHandler = new Handler();

    private final Runnable commitLiveJump = new Runnable() {
        @Override public void run() {
            jumpToLiveChannel(liveJumpDigits);
        }
    };

    private static boolean isDigitKey(int keyCode) {
        return (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                || (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9);
    }

    private static String digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return String.valueOf(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }
        return String.valueOf(keyCode - KeyEvent.KEYCODE_0);
    }

    /**
     * Jumps to the n-th channel of the list being shown (the number keys on a TV remote).
     *
     * <p>Waiting briefly for more digits means 1 3 reaches channel 13 rather than channel 1 followed by
     * channel 3 — the same behaviour a set-top box has.
     */
    private boolean handleLiveDigits(int keyCode) {
        // Works on the live page and while a channel is playing: a set-top box lets you type a channel
        // number at any time, and that is exactly when the numbers are reached for.
        if (!PAGE_LIVE.equals(currentPage) && livePlayingIndex < 0) return false;
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            liveJumpHandler.removeCallbacks(commitLiveJump);
            liveJumpDigits = "";
            showLiveJumpNotice("");
            return true;
        }
        if (!isDigitKey(keyCode)) return false;
        if (liveJumpDigits.length() >= 4) liveJumpDigits = "";
        liveJumpDigits = liveJumpDigits + digitOf(keyCode);
        showLiveJumpNotice("跳到频道：" + liveJumpDigits);
        liveJumpHandler.removeCallbacks(commitLiveJump);
        liveJumpHandler.postDelayed(commitLiveJump, 1500L);
        return true;
    }

    /**
     * Shows or hides the rows used to browse the catalog.
     *
     * <p>The on-screen keyboard needs most of the height, so while it is open the source and group rows
     * plus the programme line give way to the results grid, which is what the viewer is looking at.
     */
    private void setLiveBrowsingRowsVisible(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        if (liveSourceRowHolder != null) liveSourceRowHolder.setVisibility(state);
        if (liveGroupRowHolder != null) liveGroupRowHolder.setVisibility(state);
        if (liveEpgLine != null) liveEpgLine.setVisibility(state);
    }

    /** Number feedback: on screen while watching, on the list otherwise. */
    private void showLiveJumpNotice(String text) {
        if (isFullScreenMedia() && playerHud != null) {
            if (text.isEmpty()) playerHud.setTitleSubtitle("");
            else playerHud.showSeek(text);
            return;
        }
        if (liveEpgLine != null) liveEpgLine.setText(text);
    }

    /** Selects a 1-based position in the visible channel list and starts playing it. */
    private void jumpToLiveChannel(String digits) {
        liveJumpHandler.removeCallbacks(commitLiveJump);
        liveJumpDigits = "";
        if (digits == null || digits.isEmpty()) return;
        int number;
        try {
            number = Integer.parseInt(digits);
        } catch (NumberFormatException error) {
            return;
        }
        List<com.nukacast.app.live.model.LiveCatalog.Channel> channels = visibleChannelList();
        if (channels.isEmpty()) {
            showLiveJumpNotice("先选一个直播源");
            return;
        }
        if (number < 1 || number > channels.size()) {
            String message = "没有第 " + number + " 个频道（当前 " + channels.size() + " 个）";
            showLiveJumpNotice(message);
            if (!isFullScreenMedia()) {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        playLiveChannel(channels.get(number - 1));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleLiveDigits(keyCode)) return true;
        if (isFullScreenMedia()) {
            // Any key brings the HUD back; it fades by itself so the picture stays clean.
            if (playerHud != null) playerHud.reveal();
            // Live TV zaps channels with up/down, the way a set-top box does.
            if (livePlayingIndex >= 0 && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                switchLiveChannel(-1);
                return true;
            }
            if (livePlayingIndex >= 0 && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                switchLiveChannel(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                runtime.getPlayerController().toggle();
                refreshPlayerHud();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekFromKey(30000);
                refreshPlayerHud();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekFromKey(-10000);
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
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            // While something is playing the menu key opens the player menu, which is what a viewer
            // expects; on the live page it shows that channel's programme list; elsewhere settings.
            if (isFullScreenMedia() && playerHud != null) {
                togglePlayerMenu();
                return true;
            }
            if (PAGE_LIVE.equals(currentPage)) {
                showChannelGuide(focusedLiveChannel());
                return true;
            }
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
        pageContainer = (android.view.ViewGroup) homePage.getParent();
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
        searchRecentRow = (LinearLayout) findViewById(R.id.searchRecent);
        loadRecentSearches();
        renderRecentSearches();
        searchKeyword = (EditText) findViewById(R.id.searchKeyword);
        searchStatus = (TextView) findViewById(R.id.searchStatus);
        homeLoading = (TextView) findViewById(R.id.homeLoading);
        serviceStatus = (TextView) findViewById(R.id.serviceStatus);
        networkStatus = (TextView) findViewById(R.id.networkStatus);
        webAddress = (TextView) findViewById(R.id.webAddress);
        airplayState = (TextView) findViewById(R.id.airplayState);
        dlnaState = (TextView) findViewById(R.id.dlnaState);
        deviceSummary = (TextView) findViewById(R.id.deviceSummary);
        codecSummary = (TextView) findViewById(R.id.codecSummary);
        sourceSummary = (TextView) findViewById(R.id.sourceSummary);
        storageSummary = (TextView) findViewById(R.id.storageSummary);
        updateSummary = (TextView) findViewById(R.id.updateSummary);
        checkUpdateButton = (Button) findViewById(R.id.checkUpdateButton);
        openReleaseButton = (Button) findViewById(R.id.openReleaseButton);
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
        // Subtitle lines come from the player whenever the stream carries them. The overlay is separate
        // from the HUD, which hides itself after a few seconds — subtitles must not.
        subtitleOverlay = new com.nukacast.app.ui.SubtitleOverlay(this);
        ((android.widget.FrameLayout) findViewById(R.id.rootFrame))
                .addView(subtitleOverlay, subtitleOverlay.layoutParams());
        runtime.getPlayerController().setCueListener(new com.nukacast.app.player.PlayerController.CueListener() {
            @Override public void onCue(String text) {
                if (subtitleOverlay != null) subtitleOverlay.setLine(text);
            }
        });
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
        buildLivePage();

        refreshSourcesButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refreshSources(); }
        });
        scanStorageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { scanStorage(); }
        });
        checkUpdateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { checkForUpdates(true); }
        });
        openReleaseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showReleaseAddress(); }
        });
        // The band shows the running version immediately; the feed is asked in the background.
        if (updateSummary != null) {
            updateSummary.setText("当前版本 " + runtime.versionName());
            checkForUpdates(false);
        }
        themeToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                TvTheme.toggle(MainActivity.this);
                recreate();
            }
        });
        viewLogsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showLogViewer(); }
        });
        autoNextButton = (Button) findViewById(R.id.autoNextButton);
        autoNextButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                playbackSettings().setAutoNextEpisode(!playbackSettings().autoNextEpisode());
                renderPlaybackSettings();
            }
        });
        qualityButton = (Button) findViewById(R.id.qualityButton);
        qualityButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                playbackSettings().nextQuality();
                renderPlaybackSettings();
            }
        });
        decoderButton = (Button) findViewById(R.id.decoderButton);
        if (decoderButton != null) {
            decoderButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    // Switching between automatic and forced software decoding: the player reads this
                    // preference whenever it starts a stream.
                    if (com.nukacast.app.player.DecoderPreference.prefersSoftware(MainActivity.this)) {
                        com.nukacast.app.player.DecoderPreference.clear(MainActivity.this);
                    } else {
                        com.nukacast.app.player.DecoderPreference.preferSoftware(MainActivity.this);
                    }
                    renderPlaybackSettings();
                }
            });
        }
        renderPlaybackSettings();

    }

    private String movieFilterSelected = "";

    /** The movies page: category browsing first, then the home feed, then short dramas. */
    /** Reflects the stored playback settings on the settings page. */
    private void renderPlaybackSettings() {
        if (autoNextButton != null) {
            boolean enabled = playbackSettings().autoNextEpisode();
            autoNextButton.setText(enabled
                    ? getString(R.string.playback_auto_next_button_on)
                    : getString(R.string.playback_auto_next_button_off));
            TextView summary = (TextView) findViewById(R.id.autoNextSummary);
            if (summary != null) {
                summary.setText(enabled
                        ? getString(R.string.playback_auto_next_on)
                        : getString(R.string.playback_auto_next_off));
            }
        }
        if (qualityButton != null) {
            qualityButton.setText(getString(R.string.playback_quality_button,
                    com.nukacast.app.player.PlaybackSettings.qualityLabel(playbackSettings().quality())));
        }
        if (decoderButton != null) {
            boolean software = com.nukacast.app.player.DecoderPreference.prefersSoftware(this);
            decoderButton.setText(getString(R.string.playback_decoder_button,
                    getString(software ? R.string.decoder_software : R.string.decoder_auto)));
        }
        TextView qualitySummary = (TextView) findViewById(R.id.qualitySummary);
        if (qualitySummary != null) {
            boolean software = com.nukacast.app.player.DecoderPreference.prefersSoftware(this);
            qualitySummary.setText(getString(R.string.playback_quality_note,
                    com.nukacast.app.player.PlaybackSettings.qualityLabel(playbackSettings().quality()),
                    getString(software ? R.string.decoder_software : R.string.decoder_auto)));
        }
    }

    /**
     * Seeks by {@code offsetMs} and says so on screen.
     *
     * <p>Mainstream players show the jump and the resulting time; without it a press on 快进 is
     * indistinguishable from a key that did nothing.
     */
    private void seekFromKey(int offsetMs) {
        runtime.getPlayerController().seekBy(offsetMs);
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        long target = playback.positionMs + offsetMs;
        if (playback.durationMs > 0) target = Math.min(target, playback.durationMs);
        target = Math.max(0, target);
        String label = (offsetMs >= 0 ? "快进 " : "快退 ")
                + Math.abs(offsetMs / 1000) + " 秒"
                + (playback.durationMs > 0
                        ? "　" + com.nukacast.app.ui.PlayerHudView.formatTime(target)
                          + " / " + com.nukacast.app.ui.PlayerHudView.formatTime(playback.durationMs)
                        : "");
        if (playerHud != null) playerHud.showSeek(label);
    }

    /** Re-renders the settings page after a change made from the web console. */
    public void refreshPlaybackSettings() {
        runOnUiThread(new Runnable() {
            @Override public void run() { renderPlaybackSettings(); }
        });
    }

    /** Reads/writes the playback settings for the debug API and the web console. */
    public java.util.Map<String, Object> playbackSettingsForDebug() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        values.put("autoNextEpisode", playbackSettings().autoNextEpisode());
        values.put("quality", playbackSettings().quality());
        values.put("qualityLabel", com.nukacast.app.player.PlaybackSettings
                .qualityLabel(playbackSettings().quality()));
        values.put("softDecoder", com.nukacast.app.player.DecoderPreference.prefersSoftware(this));
        return values;
    }

    /** Applies one setting by name; used by the debug API and the web console. */
    public java.util.Map<String, Object> applyPlaybackSetting(String name, String value) {
        if ("autoNextEpisode".equals(name)) {
            playbackSettings().setAutoNextEpisode(!"0".equals(value) && !"false".equals(value));
        } else if ("quality".equals(name)) {
            playbackSettings().setQuality(value);
        } else if ("softDecoder".equals(name)) {
            if ("0".equals(value) || "false".equals(value)) {
                com.nukacast.app.player.DecoderPreference.clear(this);
            } else {
                com.nukacast.app.player.DecoderPreference.preferSoftware(this);
            }
        }
        runOnUiThread(new Runnable() {
            @Override public void run() { renderPlaybackSettings(); }
        });
        return playbackSettingsForDebug();
    }

    private void buildMovieFilterRow() {
        if (movieFilters == null) return;
        movieFilters.removeAllViews();
        addMovieFilter("分类浏览", "");
        addMovieFilter("最近更新", "首页");
        addMovieFilter("短剧", "短剧");
        addMovieFilter("收藏", "收藏");
        setFilterSelection("");
    }

    /** Renders the favourites, with a way to remove one without leaving the page. */
    private void renderFavoriteGrid() {
        moviesContent.removeAllViews();
        List<LibraryItem> favorites = runtime.getMediaLibrary().favorites();
        moviesContent.addView(sectionTitle("我的收藏 · " + favorites.size() + " 部"));
        if (favorites.isEmpty()) {
            moviesContent.addView(bodyText("还没有收藏。在影视页选中影片后按菜单键即可收藏。"));
            return;
        }
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        moviesContent.addView(grid);
        int columns = gridColumns();
        LinearLayout row = null;
        for (int i = 0; i < favorites.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(178));
                rowParams.bottomMargin = dp(8);
                grid.addView(row, rowParams);
            }
            final LibraryItem library = favorites.get(i);
            MediaCardView card = card(library.toSearchItem(), 0, 0);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { openMedia(library.toSearchItem()); }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View view) {
                    runtime.getMediaLibrary().toggleFavorite(library.toSearchItem());
                    Toast.makeText(MainActivity.this, "已取消收藏：" + library.name,
                            Toast.LENGTH_SHORT).show();
                    renderFavoriteGrid();
                    return true;
                }
            });
            row.addView(card, cardParams());
        }
        // Removing is a long press on the card, the convention on TV remotes.
        moviesContent.addView(bodyText("长按卡片可取消收藏"));
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
        if ("live".equals(name)) showPage(PAGE_LIVE);
        else if ("movies".equals(name)) showPage(PAGE_MOVIES);
        else if ("search".equals(name)) showPage(PAGE_SEARCH);
        else if ("cast".equals(name)) showPage(PAGE_CAST);
        else if ("settings".equals(name)) showPage(PAGE_SETTINGS);
        else showPage(PAGE_HOME);
    }

    /** Switches the movies page to one of its views; used by the debug API to inspect them. */
    public void selectMovieFilterByName(String filter) {
        String wanted = movieFilterSelected.isEmpty() ? "分类浏览" : movieFilterSelected;
        if (filter != null && !filter.trim().isEmpty()) wanted = filter.trim();
        if ("分类浏览".equals(wanted)) {
            showMovies("分类浏览");
        } else if ("最近更新".equals(wanted)) {
            showMovies("首页");
        } else if ("短剧".equals(wanted)) {
            showMovies("短剧");
        } else if ("收藏".equals(wanted)) {
            showMovies("收藏");
        }
    }

    /** The movies view currently selected, for the debug API's answer. */
    public String currentMovieFilterName() {
        return movieFilterSelected.isEmpty() ? "分类浏览" : movieFilterSelected;
    }

    /** Opens a detail screen on the UI thread; used by the debug API to inspect that screen. */
    public void openDetailForDebug(final MediaDetail detail) {
        if (detail == null) return;
        showDetail(detail);
    }

    /**
     * Starts an episode of {@code detail} on the given line, from any thread.
     *
     * <p>Used by the web console so its play button behaves exactly like picking an episode on the TV.
     *
     * @return true when playback was started (the episode exists), false otherwise.
     */
    public boolean playDetailEpisodeForDebug(final MediaDetail detail, final String lineName,
                                             final String episodeId) {
        if (detail == null) return false;
        MediaDetail.PlaySource line =
                com.nukacast.app.player.LinePicker.lineOf(detail, lineName);
        if (line == null) return false;
        MediaDetail.Episode episode = null;
        for (MediaDetail.Episode candidate : line.episodes) {
            if (candidate.id.equals(episodeId)) episode = candidate;
        }
        if (episode == null) {
            episode = com.nukacast.app.player.LinePicker.episodeOf(line, episodeId);
        }
        if (episode == null) return false;
        final MediaDetail.Episode chosen = episode;
        final MediaDetail.PlaySource chosenLine = line;
        onUiThreadNow(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() {
                beginUserPlayback();
                playEpisode(detail, chosenLine, chosen, 0);
                return true;
            }
        });
        return true;
    }

    /**
     * Opens the live channel search with {@code query} typed in, and opens the page if needed.
     *
     * <p>Driven by the debug API so the search can be exercised without an on-screen keyboard.
     */
    public int liveSearchForDebug(final String query) {
        if (!PAGE_LIVE.equals(currentPage)) showPage(PAGE_LIVE);
        if (query == null || query.isEmpty()) {
            // An empty query leaves search mode: the debug API and the smoke test need a way back to
            // the group list, since the search state otherwise persists across runs.
            liveSearching = false;
            liveSearchText = "";
            if (liveSearchPanel != null) liveSearchPanel.setVisibility(View.GONE);
            renderLiveGroups();
            renderLiveChannels();
            return visibleChannelList().size();
        }
        if (liveSearchPanel == null || liveSearchPanel.getVisibility() != View.VISIBLE) {
            toggleLiveSearch();
        }
        liveSearchText = query;
        // Typing one key at a time exercises the same path the keyboard uses.
        renderLiveSearchResults();
        List<com.nukacast.app.live.model.LiveCatalog.Channel> hits =
                com.nukacast.app.live.ChannelSearch.find(liveCatalog, liveSearchText);
        return hits.size();
    }

    /** What the live page is showing right now: source, group or search, focused channel (debug API). */
    public java.util.Map<String, Object> livePageStateForDebug() {
        List<com.nukacast.app.live.model.LiveCatalog.Channel> visible = visibleChannelList();
        com.nukacast.app.live.model.LiveCatalog.Channel focused = focusedLiveChannel();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("sourceId", liveSourceId);
        payload.put("sourceName", liveCatalog == null ? "" : liveCatalog.sourceName);
        payload.put("group", liveGroupName);
        payload.put("searching", liveSearching);
        payload.put("searchText", liveSearchText);
        payload.put("visible", visible.size());
        payload.put("focusedChannel", focused == null ? "" : focused.name);
        payload.put("focusedChannelId", focused == null ? "" : focused.id);
        payload.put("watching", livePlayingIndex);
        // The first few entries of the list the number keys count through, so a caller can check what
        // "the 3rd channel" means without reading the screen.
        List<com.nukacast.app.live.model.LiveCatalog.Channel> channels = visibleChannelList();
        payload.put("channelCount", channels.size());
        List<String> firstChannels = new java.util.ArrayList<String>();
        for (int i = 0; i < channels.size() && i < 8; i++) {
            firstChannels.add(channels.get(i).name);
        }
        payload.put("firstChannels", firstChannels);
        return payload;
    }

    /** True once the live page has a catalog to search (or has finished trying). */
    /** Selects a live source by name fragment (debug API); returns its name or an empty string. */
    public String selectLiveSourceForDebug(final String fragment) {
        if (!PAGE_LIVE.equals(currentPage)) showPage(PAGE_LIVE);
        if (liveSources.isEmpty()) {
            loadLive();
            return "";
        }
        for (com.nukacast.app.live.model.LiveSourceInfo info : liveSources) {
            if (fragment == null || info.name == null) continue;
            if (info.name.contains(fragment)) {
                if (!info.id.equals(liveSourceId)) {
                    liveSourceId = info.id;
                    liveCatalog = null;
                    liveGroupName = "";
                    renderLiveSourceRow();
                    loadLiveCatalog(liveSourceId);
                }
                return info.name;
            }
        }
        return "";
    }

    /** Names of the live sources currently known to the page (debug API). */
    public List<String> liveSourceNamesForDebug() {
        List<String> names = new java.util.ArrayList<String>();
        for (com.nukacast.app.live.model.LiveSourceInfo info : liveSources) names.add(info.name);
        return names;
    }

    public boolean liveCatalogReadyForDebug() {
        if (liveSources.isEmpty()) {
            if (!liveLoading) loadLive();
            // Nothing to search until the source list arrives; the caller polls.
            return false;
        }
        return liveCatalog != null;
    }

    /**
     * Called after each DLNA control action.
     *
     * <p>A phone casting to the TV is watched, not operated: the screen shows what is playing and the
     * return key ends the session, exactly as for AirPlay.
     */
    public void onDlnaAction(String action) {
        final com.nukacast.app.dlna.DlnaRenderer renderer = runtime.getDlnaRenderer();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if ("Stop".equals(action) || renderer.currentUri().isEmpty()) {
                    if (playerHud != null) playerHud.hideNow();
                    return;
                }
                if (playerHud == null) return;
                String title = renderer.title();
                playerHud.show(title.isEmpty() ? "正在投屏" : title, "DLNA 投屏",
                        "按返回键结束投屏", false);
            }
        });
    }

    /** The modal currently on screen, if any (only one is ever shown at a time). */
    private AlertDialog activeDialog;

    /**
     * Shows a dialog and remembers it.
     *
     * <p>A modal holds the input focus, so an automated check that cannot close it will find every later
     * key press ignored; remembering it gives the debug API a way to dismiss it.
     */
    private void showDialog(AlertDialog dialog) {
        activeDialog = dialog;
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) {
                activeDialog = null;
            }
        });
        dialog.show();
    }

    /** The window the debug layout reader should inspect: the modal when one is up, else the page. */
    public android.view.View inspectableRootForDebug() {
        AlertDialog dialog = activeDialog;
        if (dialog != null && dialog.isShowing() && dialog.getWindow() != null
                && dialog.getWindow().getDecorView() != null) {
            return dialog.getWindow().getDecorView();
        }
        return getWindow() == null ? null : getWindow().getDecorView();
    }

    /** Closes the dialog on screen, if there is one (debug API). */
    public boolean closeTopDialogForDebug() {
        AlertDialog dialog = activeDialog;
        if (dialog == null || !dialog.isShowing()) return false;
        dialog.dismiss();
        return true;
    }

    /** Re-renders the movies page, so a change made from the web console shows up on the TV. */
    public void refreshMoviesPage() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (PAGE_MOVIES.equals(currentPage)) showMovies(currentMovieFilter);
            }
        });
    }

    /**
     * Types into the on-screen keyboard of a page, key by key.
     *
     * <p>Goes through the same handlers the remote uses ({@code pressSearchKey} /
     * {@code pressLiveSearchKey}), so a check here covers the keyboard itself, not just the search
     * engine behind it.
     *
     * @param target "search" or "live"
     * @return the text that ended up in the box
     */
    public String typeForDebug(final String target, final String text) {
        if ("live".equals(target)) {
            if (!PAGE_LIVE.equals(currentPage)) showPage(PAGE_LIVE);
            if (liveSearchPanel == null || liveSearchPanel.getVisibility() != View.VISIBLE) {
                toggleLiveSearch();
            }
            liveSearchText = "";
            for (int i = 0; i < text.length(); i++) {
                pressLiveSearchKey(text.substring(i, i + 1));
            }
            return liveSearchText;
        }
        if (!PAGE_SEARCH.equals(currentPage)) showPage(PAGE_SEARCH);
        searchKeyword.setText("");
        for (int i = 0; i < text.length(); i++) {
            pressSearchKey(text.substring(i, i + 1));
        }
        return searchKeyword.getText().toString();
    }

    /** Keywords shown on the search page, newest first (debug API). */
    public List<String> recentSearchesForDebug() {
        return new java.util.ArrayList<String>(recentSearches);
    }

    /**
     * Applies a browse filter from the debug API, through the same path the chips use.
     *
     * @return the filter label that is now in effect
     */
    public String applyBrowseFilterForDebug(final String year, final String area, final String lang) {
        browseFilter = new com.nukacast.app.tvbox.BrowseFilter(year, area, lang);
        if (browseSites.isEmpty() || browseSiteKey.isEmpty()) {
            // The category bar has not been loaded yet (the page was never opened): load it, and the
            // first page it fetches will already carry this filter.
            showMovies("");
        } else {
            if (!PAGE_MOVIES.equals(currentPage)) showMovies("");
            loadCategoryPage(1);
        }
        return browseFilter.isEmpty() ? "全部" : browseFilter.label();
    }

    /** The browse filter currently in effect (debug API). */
    public String browseFilterForDebug() {
        return browseFilter.isEmpty() ? "" : browseFilter.label();
    }

    /**
     * Moves the focus to a known widget (debug API).
     *
     * <p>Focus movement itself is done by the input system before the key reaches the app, so a caller
     * that cannot inject real key events needs a way to put the focus where it wants to test from.
     */
    public String focusForDebug(String target) {
        if ("hero".equals(target) || "featured".equals(target)) {
            // The home page is rebuilt whenever its rows arrive, which replaces the panel; asking the
            // stale instance for focus quietly fails, and the caller then sees "hero-not-focusable"
            // even though the card on screen is perfectly focusable.
            View panel = featuredPanel;
            // getWindowToken() is the API 17-safe way to ask the same question as isAttachedToWindow().
            if (panel != null && panel.getWindowToken() == null) panel = null;
            if (panel == null) {
                android.view.View root = getWindow() == null ? null : getWindow().getDecorView();
                panel = findFocusablePanel(root);
                if (panel != null) featuredPanel = panel;
            }
            if (panel == null) return "no-hero";
            if (!panel.requestFocus()) return "hero-not-focusable";
            return featuredItem == null ? "hero" : "hero:" + featuredItem.name;
        }
        if ("search".equals(target)) {
            android.view.View button = findViewById(R.id.searchButton);
            return button != null && button.requestFocus() ? "search" : "search-not-focusable";
        }
        if ("nav".equals(target)) {
            android.view.View nav = findViewById(R.id.navHome);
            return nav != null && nav.requestFocus() ? "nav" : "nav-not-focusable";
        }
        return "unknown-target";
    }

    /**
     * The featured card of the home page, found by looking for the panel that owns the title view.
     *
     * <p>Only used when the field points at a detached instance (the page was just rebuilt).
     */
    private View findFocusablePanel(android.view.View view) {
        if (view == null) return null;
        if (view == featuredTitle && view.getParent() instanceof View) return (View) view.getParent();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFocusablePanel(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Runs a player-menu action on the UI thread; used by the debug API and the smoke test. */
    public String playerMenuActionForDebug(String action) {
        if (action == null) return "no-action";
        onPlayerMenuAction(action);
        return action;
    }

    /** Current aspect mode index (0 适应, 1 填充, 2 原始). */
    public int aspectModeForDebug() {
        return aspectMode;
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
        // Entering a page from the sidebar starts at the top: the view is reused, so the previous visit's
        // scroll position used to leave a half-cut row of cards above the first row (measured on the
        // device). Returning from a detail dialog does not come through here, so browsing keeps its place.
        boolean entered = !page.equals(currentPage);
        currentPage = page;
        homePage.setVisibility(PAGE_HOME.equals(page) ? View.VISIBLE : View.GONE);
        moviesPage.setVisibility(PAGE_MOVIES.equals(page) ? View.VISIBLE : View.GONE);
        searchPage.setVisibility(PAGE_SEARCH.equals(page) ? View.VISIBLE : View.GONE);
        castPage.setVisibility(PAGE_CAST.equals(page) ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(PAGE_SETTINGS.equals(page) ? View.VISIBLE : View.GONE);
        if (livePage != null) livePage.setVisibility(PAGE_LIVE.equals(page) ? View.VISIBLE : View.GONE);
        findViewById(R.id.navHome).setSelected(PAGE_HOME.equals(page));
        findViewById(R.id.navMovies).setSelected(PAGE_MOVIES.equals(page));
        findViewById(R.id.navCast).setSelected(PAGE_CAST.equals(page));
        findViewById(R.id.navSettings).setSelected(PAGE_SETTINGS.equals(page));
        if (navLive != null) navLive.setSelected(PAGE_LIVE.equals(page));
        // The type filters belong to the movies page; leaving it highlighted made it look as if a
        // filter were still applied while a different page was on screen.
        if (!PAGE_MOVIES.equals(page)) setFilterSelection("");
        if (PAGE_HOME.equals(page)) renderHome();
        if (PAGE_LIVE.equals(page)) loadLive();
        // The movies page is a container the filter chips fill in; entering it from the sidebar used to
        // show only those four chips above an empty screen until one was pressed. It now opens on the
        // category browser, which is what the page is mostly used for.
        if (PAGE_MOVIES.equals(page) && moviesContent != null && moviesContent.getChildCount() == 0
                && !showMoviesMore) {
            showMoviesMore = true;
            try {
                showMovies("分类浏览");
            } finally {
                showMoviesMore = false;
            }
        }
        if (entered) scrollPageToTop(page);
        render();
    }

    /** Guards the "open the category browser when the movies page is empty" call against recursion. */
    private boolean showMoviesMore;

    /** Puts a page's own scroll view back to the top. */
    private void scrollPageToTop(String page) {
        int id = 0;
        if (PAGE_HOME.equals(page)) id = R.id.homePage;
        else if (PAGE_MOVIES.equals(page)) id = R.id.moviesScroll;
        else if (PAGE_CAST.equals(page)) id = R.id.castPage;
        else if (PAGE_SETTINGS.equals(page)) id = R.id.settingsPage;
        if (id == 0) return;
        View view = findViewById(id);
        if (view instanceof ScrollView) ((ScrollView) view).scrollTo(0, 0);
    }

    private void showMovies(String filter) {
        currentMovieFilter = filter;
        showPage(PAGE_MOVIES);
        setFilterSelection(filter);
        if ("短剧".equals(filter)) {
            browseMode = false;
            // loadDramaBrowse sets its own running flag; setting it here first made the call return
            // immediately and the tab sat on "正在读取短剧列表…" forever.
            if (dramaItems.isEmpty() && dramaBrowsePage == 0) loadDramaBrowse(1);
            renderDramaMovies();
            return;
        }
        if ("收藏".equals(filter)) {
            browseMode = false;
            renderFavoriteGrid();
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
                            // The viewer may have switched to another view of this page (短剧/收藏) while
                            // the category list was being read; rendering now would put the browser back
                            // over the view they picked.
                            if (!browseMode || !PAGE_MOVIES.equals(currentPage)) return;
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
                    if (!category.id.equals(browseCategoryId)) resetBrowseFilter();
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
                final com.nukacast.app.tvbox.BrowseFilter filter = browseFilter;
                final com.nukacast.app.tvbox.TvBoxContentService service =
                        runtime.getContentService();
                int attempts = 0;
                try {
                    if (filter.isEmpty()) {
                        while (true) {
                            items = service.browse(sourceId, siteKey, categoryId, page);
                            if (items == null) items = new java.util.ArrayList<SearchItem>();
                            // Measured on real configs: some declared categories return one or two
                            // records while their neighbours return thousands, so a thin first page
                            // moves on to the next category.
                            if (page > 1 || items.size() >= 3 || attempts >= 8) break;
                            String next = nextCategoryAfter(siteKey, categoryId);
                            if (next == null || next.equals(categoryId)) break;
                            categoryId = next;
                            attempts++;
                        }
                    } else {
                        // Filtered: pick a category that actually has records first (one cheap request),
                        // then let the service scan it. Scanning every candidate category would mean
                        // fifteen page fetches per candidate.
                        while (true) {
                            List<SearchItem> firstPage = service.browse(sourceId, siteKey, categoryId, 1);
                            boolean thin = firstPage == null || firstPage.size() < 3;
                            if (!thin) {
                                items = service.browseFiltered(sourceId, siteKey, categoryId, page,
                                        filter);
                                if (items == null) items = new java.util.ArrayList<SearchItem>();
                                break;
                            }
                            String next = attempts >= 8 ? null : nextCategoryAfter(siteKey, categoryId);
                            if (next == null || next.equals(categoryId)) break;
                            categoryId = next;
                            attempts++;
                        }
                        if (items.isEmpty() && !service.filterScanComplete(
                                sourceId, siteKey, categoryId, filter)) {
                            failure = "扫描中";
                        }
                    }
                } catch (Exception error) {
                    failure = error.getMessage() == null ? "加载失败" : error.getMessage();
                }
                final List<SearchItem> result = items;
                final String reason = failure;
                final String chosen = categoryId;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        browseLoading = false;
                        moviesContent.removeAllViews();
                        if ("扫描中".equals(reason)) {
                            moviesContent.addView(sectionTitle(browseFilter.label() + " · 已匹配 "
                                    + runtime.getContentService().filterMatchCount(
                                            sourceId, siteKey, chosen, browseFilter) + " 条"));
                            moviesContent.addView(bodyText(
                                    "站点不支持按年份/地区筛选，正在已抓取的记录里继续匹配：稍后再点一次即可看到更多。"));
                            LinearLayout again = new LinearLayout(MainActivity.this);
                            again.setOrientation(LinearLayout.HORIZONTAL);
                            Button scan = actionButton("继续扫描", 0);
                            scan.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View view) { loadCategoryPage(1); }
                            });
                            again.addView(scan);
                            moviesContent.addView(again);
                            scan.requestFocus();
                            return;
                        }
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

    /** Resets the filter, which belongs to one category on one site. */
    private void resetBrowseFilter() {
        if (browseFilter.isEmpty()) return;
        browseFilter = new com.nukacast.app.tvbox.BrowseFilter();
    }

    /**
     * The 年份/地区/语言 bar.
     *
     * <p>The sites ignore these parameters (a live MacCMS site returned identical results for
     * {@code &year=2024}, {@code &area=大陆} and nothing at all), so the values select records by
     * matching what the site already returned.
     */
    private void renderBrowseFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        rulesRow(bar, "年份", com.nukacast.app.tvbox.BrowseFilter.YEARS, browseFilter.year, "year");
        rulesRow(bar, "地区", com.nukacast.app.tvbox.BrowseFilter.AREAS, browseFilter.area, "area");
        rulesRow(bar, "语言", com.nukacast.app.tvbox.BrowseFilter.LANGS, browseFilter.lang, "lang");
        moviesContent.addView(bar);
    }

    private void rulesRow(LinearLayout bar, String label, List<String> values,
                          String selected, final String kind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bodyText(label);
        title.setTextSize(12);
        title.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(30)));
        row.addView(title);
        Button all = actionButton("全部", 0);
        all.setSelected(selected.isEmpty());
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { applyBrowseFilter(kind, ""); }
        });
        row.addView(all);
        for (final String value : values) {
            Button chip = actionButton(value, 0);
            chip.setSelected(value.equals(selected));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { applyBrowseFilter(kind, value); }
            });
            row.addView(chip);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        params.bottomMargin = dp(2);
        bar.addView(scroll, params);
    }

    /** Applies one filter value and reloads the first page. */
    private void applyBrowseFilter(String kind, String value) {
        if ("year".equals(kind)) browseFilter = browseFilter.withYear(value);
        else if ("area".equals(kind)) browseFilter = browseFilter.withArea(value);
        else browseFilter = browseFilter.withLang(value);
        AppLog.i("影视", "分类筛选：" + (browseFilter.isEmpty() ? "全部" : browseFilter.label()));
        loadCategoryPage(1);
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
            moviesContent.addView(sectionTitle(siteName + " · " + categoryName
                    + (browseFilter.isEmpty() ? "" : " · " + browseFilter.label())));
            renderBrowseFilterBar();
        } else {
            // Remove the previous “下一页” button before appending.
            if (loadMoreRow != null) {
                moviesContent.removeView(loadMoreRow);
                loadMoreRow = null;
            }
        }
        if (items.isEmpty() && page <= 1) {
            moviesContent.addView(bodyText(browseFilter.isEmpty()
                    ? "该分类没有返回内容，可换一个分类或站点。"
                    : "这个筛选条件在已抓取的记录里没有匹配，取消筛选或换一个分类试试。"));
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

    // --------------------------------------------------------------------------------------
    // 直播：电视端独立页面（源 → 分组 → 频道），与 TVBox 的直播用法对齐
    // --------------------------------------------------------------------------------------

    /** Builds the live page in code: the other pages come from the XML layout, this one is dynamic. */
    private void buildLivePage() {
        // The sidebar entry comes from the layout, next to the other four, so all five share the column
        // evenly and look alike (icon above the label, same focus background).
        navLive = (Button) findViewById(R.id.navLive);
        if (navLive != null) {
            navLive.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showPage(PAGE_LIVE); }
            });
        }

        livePage = new LinearLayout(this);
        livePage.setOrientation(LinearLayout.VERTICAL);
        livePage.setVisibility(View.GONE);

        liveStatus = bodyText("");
        liveStatus.setPadding(0, 0, 0, dp(6));
        livePage.addView(liveStatus);

        LinearLayout liveTools = chipRow();
        Button searchToggle = actionButton("搜索频道", 0);
        searchToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleLiveSearch(); }
        });
        liveTools.addView(searchToggle);
        Button guideButton = actionButton("节目单", 0);
        guideButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showChannelGuide(focusedLiveChannel()); }
        });
        liveTools.addView(guideButton);
        livePage.addView(chipRowHolder(liveTools));

        liveSearchPanel = new LinearLayout(this);
        liveSearchPanel.setOrientation(LinearLayout.VERTICAL);
        liveSearchPanel.setVisibility(View.GONE);
        liveSearchQuery = bodyText("");
        liveSearchQuery.setTextSize(15);
        liveSearchPanel.addView(liveSearchQuery);
        liveSearchKeyboard = new LinearLayout(this);
        liveSearchKeyboard.setOrientation(LinearLayout.VERTICAL);
        liveSearchPanel.addView(liveSearchKeyboard);
        livePage.addView(liveSearchPanel);

        liveSourceRow = chipRow();
        liveSourceRowHolder = chipRowHolder(liveSourceRow);
        livePage.addView(liveSourceRowHolder);
        liveGroupRow = chipRow();
        liveGroupRowHolder = chipRowHolder(liveGroupRow);
        livePage.addView(liveGroupRowHolder);

        ScrollView channels = new ScrollView(this);
        channels.setVerticalScrollBarEnabled(false);
        liveEpgLine = bodyText("");
        liveEpgLine.setTextSize(13);
        livePage.addView(liveEpgLine);

        liveChannelGrid = new LinearLayout(this);
        liveChannelGrid.setOrientation(LinearLayout.VERTICAL);
        channels.addView(liveChannelGrid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        livePage.addView(channels, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        pageContainer.addView(livePage, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Shows or hides the on-screen keyboard used to find a channel in a large playlist. */
    private void toggleLiveSearch() {
        if (liveSearchPanel.getVisibility() == View.VISIBLE) {
            liveSearchPanel.setVisibility(View.GONE);
            setLiveBrowsingRowsVisible(true);
            liveSearchText = "";
            liveSearching = false;
            renderLiveChannels();
            return;
        }
        liveSearchPanel.setVisibility(View.VISIBLE);
        // Measured: keyboard (640px) + source/group rows + grid do not fit on a 1080p screen, and the
        // grid — where the search results appear — was pushed off it. Browsing rows step aside.
        setLiveBrowsingRowsVisible(false);
        liveSearching = true;
        liveSearchText = "";
        buildKeyboard(liveSearchKeyboard, new Runnable() {
            @Override public void run() {
                renderLiveSearchResults();
            }
        });
        liveSearchQuery.setText("输入频道名或首字母");
        if (liveSearchKeyboard.getChildCount() > 0) {
            liveSearchKeyboard.getChildAt(0).requestFocus();
        }
    }

    /** Shows the channels matching the query typed on the on-screen keyboard. */
    private void renderLiveSearchResults() {
        setLiveBrowsingRowsVisible(false);
        liveSearchQuery.setText(liveSearchText.isEmpty()
                ? "输入频道名或首字母" : "搜索：" + liveSearchText);
        List<com.nukacast.app.live.model.LiveCatalog.Channel> hits =
                com.nukacast.app.live.ChannelSearch.find(liveCatalog, liveSearchText);
        String prefix = liveCatalog == null ? "" : liveCatalog.sourceName + " · ";
        liveStatus.setText(liveSearchText.isEmpty()
                ? "输入频道名或首字母，例如“湖南”“hnws”"
                : prefix + "“" + liveSearchText + "” · " + hits.size() + " 个频道");
        renderLiveChannelList(hits, true);
    }

    /** Handles one on-screen keyboard press for the live search box. */
    private void pressLiveSearchKey(String key) {
        if ("清空".equals(key)) {
            liveSearchText = "";
        } else if ("退格".equals(key)) {
            if (!liveSearchText.isEmpty()) {
                liveSearchText = liveSearchText.substring(0, liveSearchText.length() - 1);
            }
        } else if ("搜索".equals(key)) {
            renderLiveSearchResults();
            return;
        } else {
            liveSearchText = liveSearchText + key;
        }
        // Results refresh on every key: with 11k channels that is what makes finding one possible.
        renderLiveSearchResults();
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private android.widget.HorizontalScrollView chipRowHolder(LinearLayout row) {
        android.widget.HorizontalScrollView bar = new android.widget.HorizontalScrollView(this);
        bar.setHorizontalScrollBarEnabled(false);
        bar.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        params.bottomMargin = dp(6);
        bar.setLayoutParams(params);
        return bar;
    }

    /** Loads the live sources once, then the catalog of the selected one. */
    private void loadLive() {
        // The list is re-read on every visit: sources can be added or removed from the web console
        // while the app is open, and a page that keeps showing a deleted source looks broken.
        if (System.currentTimeMillis() - liveLoadedAt < 5000L && !liveSources.isEmpty()) {
            renderLiveSourceRow();
            if (liveCatalog == null && !liveSourceId.isEmpty()) loadLiveCatalog(liveSourceId);
            return;
        }
        {
            liveFailedSources.clear();
            liveLastError = "";
            liveCatalog = null;
            liveGroupName = "";
            liveSources.clear();
            liveSourceId = "";
        }
        if (liveSources.isEmpty()) {
            if (liveLoading) return;
            liveLoading = true;
            liveStatus.setText("正在读取直播源…");
            liveIo.execute(new Runnable() {
                @Override public void run() {
                    final List<com.nukacast.app.live.model.LiveSourceInfo> found =
                            new java.util.ArrayList<com.nukacast.app.live.model.LiveSourceInfo>();
                    try {
                        found.addAll(runtime.getLiveService().sources());
                    } catch (Throwable error) {
                        AppLog.w("直播", "读取直播源失败：" + error.getClass().getSimpleName());
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            liveLoading = false;
                            liveSources.clear();
                            liveSources.addAll(found);
                            if (liveSources.isEmpty()) {
                                liveStatus.setText("还没有直播源：在网页的“直播”页添加一个 IPTV 清单，"
                                        + "或在“源管理”里添加带直播的片源。");
                                liveSourceRow.removeAllViews();
                                liveGroupRow.removeAllViews();
                                liveChannelGrid.removeAllViews();
                                return;
                            }
                            liveLoadedAt = System.currentTimeMillis();
                            if (liveSourceId.isEmpty() || liveFailedSources.contains(liveSourceId)) {
                                String next = nextUntriedLiveSource();
                                liveSourceId = next == null ? liveSources.get(0).id : next;
                            }
                            renderLiveSourceRow();
                            loadLiveCatalog(liveSourceId);
                        }
                    });
                }
            });
            return;
        }
        renderLiveSourceRow();
        if (liveCatalog == null) loadLiveCatalog(liveSourceId);
    }

    /** The first source that has not failed in this session, or null. */
    private String nextUntriedLiveSource() {
        for (com.nukacast.app.live.model.LiveSourceInfo info : liveSources) {
            if (!liveFailedSources.contains(info.id)) return info.id;
        }
        return null;
    }

    private void renderLiveSourceRow() {
        liveSourceRow.removeAllViews();
        for (final com.nukacast.app.live.model.LiveSourceInfo info : liveSources) {
            Button chip = actionButton(info.name == null ? "直播源" : info.name, 0);
            chip.setSelected(info.id.equals(liveSourceId));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (info.id.equals(liveSourceId)) return;
                    liveSourceId = info.id;
                    liveCatalog = null;
                    liveGroupName = "";
                    renderLiveSourceRow();
                    loadLiveCatalog(liveSourceId);
                }
            });
            liveSourceRow.addView(chip);
        }
    }

    private void loadLiveCatalog(final String sourceId) {
        liveStatus.setText("正在读取频道…");
        liveLastError = "";
        liveChannelGrid.removeAllViews();
        liveGroupRow.removeAllViews();
        liveIo.execute(new Runnable() {
            @Override public void run() {
                com.nukacast.app.live.model.LiveCatalog catalog = null;
                String failure = "";
                try {
                    catalog = runtime.getLiveService().catalog(sourceId);
                } catch (Throwable error) {
                    failure = error.getMessage() == null ? "直播源读取失败" : error.getMessage();
                }
                final com.nukacast.app.live.model.LiveCatalog result = catalog;
                final String reason = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (result == null || result.groups.isEmpty()) {
                            liveFailedSources.add(sourceId);
                            liveLastError = reason.isEmpty() ? "这个直播源没有频道。" : reason;
                            // Try the next source rather than leaving the page empty: playlists hosted
                            // on blocked or dead hosts are common.
                            String next = nextUntriedLiveSource();
                            if (next != null) {
                                liveSourceId = next;
                                renderLiveSourceRow();
                                loadLiveCatalog(next);
                                return;
                            }
                            liveStatus.setText(liveFailedSources.size() > 1
                                    ? "所有直播源都不可用：" + liveLastError
                                    : "直播源读取失败：" + liveLastError);
                            return;
                        }
                        liveCatalog = result;
                        if (liveGroupName.isEmpty()) liveGroupName = result.groups.get(0).name;
                        renderLiveGroups();
                        renderLiveChannels();
                    }
                });
            }
        });
    }

    /** Channels watched recently on this source, newest first (Fongmi-style 常看). */
    private List<com.nukacast.app.live.model.LiveCatalog.Channel> recentLiveChannels() {
        List<com.nukacast.app.live.model.LiveCatalog.Channel> result =
                new java.util.ArrayList<com.nukacast.app.live.model.LiveCatalog.Channel>();
        if (liveCatalog == null) return result;
        for (String id : com.nukacast.app.live.RecentChannels.list(livePrefs(), liveSourceId)) {
            for (com.nukacast.app.live.model.LiveCatalog.Group group : liveCatalog.groups) {
                boolean found = false;
                for (com.nukacast.app.live.model.LiveCatalog.Channel channel : group.channels) {
                    if (channel.id.equals(id)) {
                        result.add(channel);
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
        }
        return result;
    }

    private static final String LIVE_GROUP_RECENT = "常看";

    private android.content.SharedPreferences livePrefs() {
        return getSharedPreferences("live_page", MODE_PRIVATE);
    }

    private void renderLiveGroups() {
        liveGroupRow.removeAllViews();
        // The group row is also rendered while the playlist is still on its way (for example when the
        // debug API or the viewer reaches the live page during start-up), and there are no groups yet.
        if (liveCatalog == null) return;
        List<com.nukacast.app.live.model.LiveCatalog.Channel> recent = recentLiveChannels();
        if (!recent.isEmpty()) {
            Button chip = actionButton(LIVE_GROUP_RECENT + " (" + recent.size() + ")", 0);
            chip.setSelected(LIVE_GROUP_RECENT.equals(liveGroupName));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    liveGroupName = LIVE_GROUP_RECENT;
                    liveChannelPage = 0;
                    renderLiveGroups();
                    renderLiveChannels();
                }
            });
            liveGroupRow.addView(chip);
        }
        for (final com.nukacast.app.live.model.LiveCatalog.Group group : liveCatalog.groups) {
            Button chip = actionButton(group.name + " (" + group.channels.size() + ")", 0);
            chip.setSelected(group.name.equals(liveGroupName));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    liveGroupName = group.name;
                    liveChannelPage = 0;
                    renderLiveGroups();
                    renderLiveChannels();
                }
            });
            liveGroupRow.addView(chip);
        }
    }

    private static final int LIVE_PAGE_SIZE = 120;

    private void renderLiveChannels() {
        setLiveBrowsingRowsVisible(!liveSearching && liveSearchPanel != null
                && liveSearchPanel.getVisibility() != View.VISIBLE);
        if (liveSearching) {
            renderLiveSearchResults();
            return;
        }
        if (liveCatalog == null) {
            liveStatus.setText("正在加载直播源…");
            liveChannelGrid.removeAllViews();
            return;
        }
        if (LIVE_GROUP_RECENT.equals(liveGroupName)) {
            List<com.nukacast.app.live.model.LiveCatalog.Channel> recent = recentLiveChannels();
            liveStatus.setText(liveCatalog.sourceName + " · 常看 · " + recent.size() + " 个频道");
            liveFocusedChannelId = "";
            if (liveEpgLine != null) liveEpgLine.setText("");
            renderLiveChannelList(recent, false);
            return;
        }
        com.nukacast.app.live.model.LiveCatalog.Group group = null;
        for (com.nukacast.app.live.model.LiveCatalog.Group candidate : liveCatalog.groups) {
            if (candidate.name.equals(liveGroupName)) group = candidate;
        }
        if (group == null) return;
        liveStatus.setText(liveCatalog.sourceName + " · " + group.name + " · "
                + group.channels.size() + " 个频道");
        // A new list means a new channel is about to be focused; the old line would otherwise sit
        // there until the new EPG arrives.
        liveFocusedChannelId = "";
        if (liveEpgLine != null) liveEpgLine.setText("");
        renderLiveChannelList(group.channels, false);
    }

    /**
     * One channel in the live grid: its logo, then the name.
     *
     * <p>A playlist's channels are easier to find by their logo than by their name — that is how every
     * set-top box shows them — and half of a public list has no logo at all, so the tile falls back to
     * the channel's initial rather than an empty square.
     */
    private View channelCell(final com.nukacast.app.live.model.LiveCatalog.Channel channel,
                             String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setFocusable(true);
        cell.setBackgroundDrawable(TvTheme.focusable(this));
        cell.setPadding(dp(6), 0, dp(6), 0);
        cell.setContentDescription(label);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageBitmap(com.nukacast.app.ui.ChannelLogo.tile(channel.name));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(18)));
        if (channel.logo != null && !channel.logo.isEmpty()) {
            // The initial stays underneath, so a logo that never arrives leaves a readable tile.
            images.loadIcon(channel.logo, icon);
        }

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(12);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(TvTheme.primary(this));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(6);
        cell.addView(name, nameParams);
        return cell;
    }

    /** Renders a channel list (one group, or the hits of a search), paged. */
    private void renderLiveChannelList(
            List<com.nukacast.app.live.model.LiveCatalog.Channel> all, boolean fromSearch) {
        liveListGeneration++;
        liveChannelGrid.removeAllViews();
        int total = all.size();
        if (total == 0) {
            liveChannelGrid.addView(bodyText("没有匹配的频道。"));
            return;
        }
        int pages = Math.max(1, (total + LIVE_PAGE_SIZE - 1) / LIVE_PAGE_SIZE);
        liveChannelPage = Math.max(0, Math.min(liveChannelPage, pages - 1));
        int from = Math.min(total, liveChannelPage * LIVE_PAGE_SIZE);
        int to = Math.min(total, from + LIVE_PAGE_SIZE);
        if (pages > 1) {
            liveStatus.setText(liveStatus.getText() + "（第 " + (liveChannelPage + 1) + "/" + pages + " 页）");
        }
        // A playlist such as iptv-org's carries 11k channels; building every button at once both
        // stalls the UI thread and exhausts memory on a 1.5GB TV, so a list is paged.
        List<com.nukacast.app.live.model.LiveCatalog.Channel> channels = all.subList(from, to);
        int columns = liveColumns();
        LinearLayout row = null;
        for (int i = 0; i < channels.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
                rowParams.bottomMargin = dp(4);
                liveChannelGrid.addView(row, rowParams);
            }
            final com.nukacast.app.live.model.LiveCatalog.Channel channel = channels.get(i);
            String label = channel.name;
            if (fromSearch && channel.group != null && !channel.group.isEmpty()) {
                label = channel.group + " · " + label;
            }
            View button = channelCell(channel, label);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), 1f);
            params.setMargins(dp(2), 0, dp(2), 0);
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { playLiveChannel(channel); }
            });
            // Walking the channels shows each one's EPG, which is how a viewer picks what to watch.
            final int generation = liveListGeneration;
            button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean focused) {
                    // Rebuilding the grid removes the old buttons, and Android still reports a focus
                    // change for those; acting on it labelled the new source with the previous
                    // source's channel. A generation counter rejects exactly those callbacks.
                    if (!focused || generation != liveListGeneration) return;
                    showChannelEpg(channel);
                }
            });
            row.addView(button);
        }
        if (pages > 1) {
            LinearLayout pager = new LinearLayout(this);
            pager.setOrientation(LinearLayout.HORIZONTAL);
            Button previous = actionButton("上一页", 0);
            previous.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (liveChannelPage > 0) {
                        liveChannelPage--;
                        refreshLiveList();
                    }
                }
            });
            Button next = actionButton("下一页", 0);
            next.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    liveChannelPage++;
                    refreshLiveList();
                }
            });
            pager.addView(previous);
            pager.addView(next);
            LinearLayout.LayoutParams pagerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            pagerParams.topMargin = dp(4);
            liveChannelGrid.addView(pager, pagerParams);
        }
        // Focus stays on the keyboard while a search is open, or the viewer would have to walk back
        // to it after every letter.
        if (!liveSearching && row != null && row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    /** Re-renders whichever list the live page is currently showing. */
    private void refreshLiveList() {
        if (liveSearching) renderLiveSearchResults();
        else renderLiveChannels();
    }

    private int livePageCount() {
        for (com.nukacast.app.live.model.LiveCatalog.Group candidate : liveCatalog.groups) {
            if (candidate.name.equals(liveGroupName)) {
                return Math.max(1,
                        (candidate.channels.size() + LIVE_PAGE_SIZE - 1) / LIVE_PAGE_SIZE);
            }
        }
        return 1;
    }

    /** One line under the channel grid: what is on now and next. */
    /**
     * The full programme list of one channel, the way a set-top box's guide page shows it.
     *
     * <p>Opened with the 节目单 chip or the MENU key while a channel is focused; the programme airing now
     * is marked and the dialog scrolls through the rest of the day.
     */
    private void showChannelGuide(final com.nukacast.app.live.model.LiveCatalog.Channel channel) {
        final String source = liveSourceId;
        if (channel == null || source.isEmpty()) {
            toast("先选一个直播源与频道");
            return;
        }
        toast(channel.name + "：正在读取节目单…");
        com.nukacast.app.diagnostics.AppLog.d("直播", "读取节目单：" + channel.name
                + "（源 " + source + "）");
        epgIo.execute(new Runnable() {
            @Override public void run() {
                final List<String> lines = new java.util.ArrayList<String>();
                String failure = "";
                int liveIndex = -1;
                try {
                    com.nukacast.app.live.model.EpgSchedule schedule =
                            runtime.getLiveService().epg(source, channel.id, "");
                    long now = System.currentTimeMillis();
                    for (com.nukacast.app.live.EpgNow.Slot slot
                            : com.nukacast.app.live.EpgNow.slots(schedule)) {
                        if (slot.isPlaceholder()) continue;
                        if (slot.isLive(now)) liveIndex = lines.size();
                        lines.add((slot.isLive(now) ? "▶ " : "　") + slot.startLabel() + "–"
                                + slot.endLabel() + "　" + slot.title);
                    }
                    if (lines.isEmpty()) failure = schedule.error.isEmpty()
                            ? "这个源没有提供节目单" : schedule.error;
                } catch (Throwable error) {
                    failure = error.getMessage() == null ? "节目单读取失败" : error.getMessage();
                }
                final String problem = failure;
                final int currentIndex = liveIndex;
                com.nukacast.app.diagnostics.AppLog.d("直播", "节目单结果：" + channel.name
                        + " → " + lines.size() + " 条"
                        + (problem.isEmpty() ? "" : "（" + problem + "）"));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!source.equals(liveSourceId)) {
                            com.nukacast.app.diagnostics.AppLog.d("直播",
                                    "节目单已丢弃：源已切换（" + source + " → " + liveSourceId + "）");
                            return;
                        }
                        if (!problem.isEmpty()) {
                            // No modal for "this source has no guide for that channel": on a TV a dialog
                            // holds the focus, so a viewer who must dismiss it notices the blocking long
                            // before they notice the explanation.
                            toast(channel.name + "：" + problem);
                            if (liveFocusedChannelId.equals(channel.id)) {
                                liveEpgLine.setText(channel.name + "：" + problem);
                            }
                            return;
                        }
                        final String[] rows = lines.toArray(new String[0]);
                        AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                                .setTitle(channel.name + " 节目单")
                                .setItems(rows, null)
                                .setPositiveButton("关闭", null)
                                .create();
                        // Opening a 24-hour list at 00:00 when it is half past nine in the evening would
                        // hide the one thing the viewer asked for, so the list starts at what is on now.
                        if (currentIndex > 0) {
                            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                                @Override public void onShow(DialogInterface shown) {
                                    if (shown instanceof AlertDialog) {
                                        ((AlertDialog) shown).getListView()
                                                .setSelection(currentIndex);
                                    }
                                }
                            });
                        }
                        showDialog(dialog);
                    }
                });
            }
        });
    }

    /** The channel the viewer is on, or the first one of the visible list. */
    private com.nukacast.app.live.model.LiveCatalog.Channel focusedLiveChannel() {
        List<com.nukacast.app.live.model.LiveCatalog.Channel> channels = visibleChannelList();
        for (com.nukacast.app.live.model.LiveCatalog.Channel channel : channels) {
            if (channel.id.equals(liveFocusedChannelId)) return channel;
        }
        return channels.isEmpty() ? null : channels.get(0);
    }

    private void toast(String message) {
        if (message == null || message.isEmpty()) return;
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showChannelEpg(final com.nukacast.app.live.model.LiveCatalog.Channel channel) {
        final String source = liveSourceId;
        liveFocusedChannelId = channel.id;
        final String cacheKey = source + "|" + channel.id;
        String cached = liveEpgLines.get(cacheKey);
        if (cached != null) {
            liveEpgLine.setText(cached);
            return;
        }
        liveEpgLine.setText(channel.name + "：正在读取节目单…");
        // A slow programme list must not queue behind the channel that was looked at a second ago
        // (nor behind a catalogue download), so EPG lookups get their own thread.
        if (!epgPending.add(cacheKey)) return;
        epgIo.execute(new Runnable() {
            @Override public void run() {
                String line = "";
                String reason = "";
                try {
                    com.nukacast.app.live.model.EpgSchedule schedule =
                            runtime.getLiveService().epg(source, channel.id, "");
                    line = com.nukacast.app.live.EpgNow.label(schedule, System.currentTimeMillis());
                    if (line.isEmpty()) reason = schedule.error;
                } catch (Throwable error) {
                    line = "";
                    reason = "节目单读取失败";
                }
                final String text = !line.isEmpty() ? channel.name + "　" + line
                        : channel.name + "：" + (reason.isEmpty() ? "这个源没有提供节目单" : reason);
                epgPending.remove(cacheKey);
                liveEpgLines.put(cacheKey, text);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // A slow answer for a channel the viewer has already left must not overwrite
                        // the line of the one being looked at (nor of another source entirely).
                        if (!source.equals(liveSourceId)) return;
                        if (!channel.id.equals(liveFocusedChannelId)) return;
                        liveEpgLine.setText(text);
                    }
                });
            }
        });
    }

    private int liveColumns() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return Math.max(3, (int) ((widthDp - 150f) / 120f));
    }

    /** Starts a channel and remembers its neighbours so 上/下键 can zap. */
    private void playLiveChannel(com.nukacast.app.live.model.LiveCatalog.Channel channel) {
        List<com.nukacast.app.live.model.LiveCatalog.Channel> group = visibleChannelList();
        livePlaying.clear();
        livePlaying.addAll(group);
        livePlayingIndex = group.indexOf(channel);
        if (channel.urls.isEmpty()) {
            android.widget.Toast.makeText(this, "该频道没有播放地址",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        com.nukacast.app.live.RecentChannels.remember(livePrefs(), liveSourceId, channel.id);
        // The 常看 chip is part of the group row, so it has to be rebuilt or the channel just watched
        // would not show up there until the page was reloaded.
        if (liveCatalog != null) renderLiveGroups();
        runtime.getPlayerController().play(this, channel.urls.get(0), channel.name, channel.headers);
        render();
        if (playerHud != null) {
            playerHud.show(channel.name, (liveCatalog == null ? "" : liveCatalog.sourceName) + " · 直播",
                    "按返回键退出 · 上/下键换台 · 菜单键显示控制", true);
        }
    }

    /** Channels behind the buttons currently on screen: the zap list for 上/下键. */
    private List<com.nukacast.app.live.model.LiveCatalog.Channel> visibleChannelList() {
        List<com.nukacast.app.live.model.LiveCatalog.Channel> result =
                new java.util.ArrayList<com.nukacast.app.live.model.LiveCatalog.Channel>();
        if (liveSearching) {
            return com.nukacast.app.live.ChannelSearch.find(liveCatalog, liveSearchText);
        }
        if (liveCatalog == null) return result;
        for (com.nukacast.app.live.model.LiveCatalog.Group candidate : liveCatalog.groups) {
            if (candidate.name.equals(liveGroupName)) return candidate.channels;
        }
        return result;
    }

    public void onPlaybackTick(int positionMs, int durationMs) {
        reportPlaybackFailure();
        advanceEpisodeWhenFinished();
        if (livePlayingIndex < 0 && activeDetail != null) tryNextLine();
        if (livePlayingIndex < 0 || livePlaying.isEmpty()) return;
        if (System.currentTimeMillis() - liveSwitchAt < 6000L) return;
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        if (!"error".equals(playback.state)) return;
        if (liveAutoSwitch >= 3) {
            if (playerHud != null) {
                playerHud.showError("这个频道的地址播不了，按返回键退出或上/下键换台");
            }
            liveAutoSwitch = 0;
            return;
        }
        liveAutoSwitch++;
        AppLog.i("直播", "频道播放失败，自动换到下一个（第 " + liveAutoSwitch + " 次）");
        switchLiveChannel(1);
    }

    /** Set once the end of a series was reached, so the notice is shown only once. */
    private boolean seriesFinished;

    /**
     * Plays the next episode when one finishes.
     *
     * <p>Watching a series episode by episode is the main use of a TV box; stopping at every episode
     * end (and dropping back to the list, as this used to) is the difference between usable and not.
     */
    private void advanceEpisodeWhenFinished() {
        if (!playbackSettings().autoNextEpisode()) {
            // Auto-advance is a setting: with it off the viewer stays on the finished episode.
            return;
        }
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        if (!"ended".equals(playback.state)) {
            if (!"error".equals(playback.state)) seriesFinished = false;
            return;
        }
        if (seriesFinished) return;
        MediaDetail detail = activeDetail;
        if (detail == null || detail.playSources == null) return;
        MediaDetail.PlaySource line =
                com.nukacast.app.player.LinePicker.lineOf(detail, activeLineName);
        MediaDetail.Episode next =
                com.nukacast.app.player.LinePicker.nextEpisode(line, activeEpisodeId);
        if (next == null) {
            seriesFinished = true;
            if (playerHud != null) {
                playerHud.show("已播完最后一集", detail.name, "按返回键退出", false);
            }
            return;
        }
        beginUserPlayback();
        AppLog.i("播放器", "本集播完，自动播放下一集：" + next.name);
        if (playerHud != null) {
            playerHud.show(detail.name + " · " + next.name, "自动播放下一集", "按返回键退出", true);
        }
        playEpisode(detail, line, next, 0);
    }

    /** Steps to the previous or next episode of the current line. */
    private void stepEpisode(int delta) {
        MediaDetail detail = activeDetail;
        if (detail == null) return;
        MediaDetail.PlaySource line =
                com.nukacast.app.player.LinePicker.lineOf(detail, activeLineName);
        MediaDetail.Episode episode =
                com.nukacast.app.player.LinePicker.stepEpisode(line, activeEpisodeId, delta);
        if (episode == null) return;
        if (playerHud != null) playerHud.hideActions();
        beginUserPlayback();
        playEpisode(detail, line, episode, 0);
    }

    /** When the current failure was first seen, so the message stays up long enough to read. */
    private long failureShownAt;
    /** True once the failure was reported for the current episode. */
    private boolean failureReported;

    /**
     * Keeps a failed playback on screen with an explanation instead of silently dropping back to the
     * list. The retry chain and the line switch run first; this only reports what is left.
     */
    private void reportPlaybackFailure() {
        // Live has its own wording and its own recovery (up/down zaps on, and the next channel is tried
        // automatically); the on-demand message would otherwise sit on screen while that happens.
        if (livePlayingIndex >= 0) return;
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        if (playback.notice != null && !playback.notice.isEmpty()) {
            // The player is retrying internally; say so rather than showing a black screen.
            if (playerHud != null) playerHud.setTitleSubtitle(playback.notice);
            return;
        }
        boolean failed = "error".equals(playback.state);
        if (!failed) {
            failureReported = false;
            failureShownAt = 0L;
            return;
        }
        String reason = playback.error == null || playback.error.isEmpty()
                ? "播放失败" : friendlyPlaybackError(playback.error);
        if (!failureReported) {
            failureReported = true;
            failureShownAt = System.currentTimeMillis();
            AppLog.w("播放器", "播放失败已上报：" + reason);
        }
        if (playerHud != null) {
            boolean moreLines = lineAttempts < MAX_LINE_ATTEMPTS && activeDetail != null
                    && activeDetail.playSources != null && activeDetail.playSources.size() > 1;
            playerHud.showError(reason + (moreLines ? "\n正在尝试其他线路…" : "\n按返回键退出"));
        }
        // Give up only after the chain had its chance, and never before the message was readable.
        if (lineAttempts >= MAX_LINE_ATTEMPTS && System.currentTimeMillis() - failureShownAt > 8000L) {
            stopActivePlayback();
            android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Turns a PlaybackException name into something a viewer can act on. */
    private static String friendlyPlaybackError(String raw) {
        if (raw.contains("DECODING") || raw.contains("DECODER")) {
            return "这台电视的解码器无法播放该清晰度";
        }
        if (raw.contains("IO_") || raw.contains("InvalidResponseCode")) {
            return "片源地址无法访问（可能已失效）";
        }
        if (raw.contains("PARSING")) return "该线路返回的内容不是视频";
        return "播放失败：" + raw;
    }

    /**
     * Moves to another play line when the current one cannot be played.
     *
     * <p>Reported from the TV as "我点击主页源推荐的还是放不了": a CMS item offers several lines and
     * the first is often dead or geo-blocked. Instead of leaving the user with an error, the next line
     * is tried automatically - which is what the dedicated players do.
     */
    private void tryNextLine() {
        if (System.currentTimeMillis() - lineSwitchAt < 6000L) return;
        if (lineAttempts >= MAX_LINE_ATTEMPTS) return;
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        if (!"error".equals(playback.state)) {
            if ("playing".equals(playback.state)) lineAttempts = 0;
            return;
        }
        if (playback.notice != null && !playback.notice.isEmpty()) return;
        final MediaDetail detail = activeDetail;
        MediaDetail.PlaySource next =
                com.nukacast.app.player.LinePicker.next(detail, activeLineName);
        if (next == null) return;
        MediaDetail.Episode episode =
                com.nukacast.app.player.LinePicker.episodeOf(next, activeEpisodeId);
        if (episode == null) return;
        lineAttempts++;
        lineSwitchAt = System.currentTimeMillis();
        AppLog.i("播放器", "当前线路播放失败，自动换到“" + next.name + "”（第 " + lineAttempts + " 次）");
        if (playerHud != null) {
            playerHud.show(detail.name + " · " + episode.name, "正在换线：" + next.name,
                    "按返回键退出", true);
        }
        playEpisode(detail, next, episode, 0);
    }

    /** Zaps to the neighbouring channel while watching live TV. */
    private void switchLiveChannel(int delta) {
        if (livePlaying.isEmpty()) return;
        liveSwitchAt = System.currentTimeMillis();
        int next = livePlayingIndex + delta;
        if (next < 0) next = livePlaying.size() - 1;
        if (next >= livePlaying.size()) next = 0;
        com.nukacast.app.live.model.LiveCatalog.Channel channel = livePlaying.get(next);
        if (channel.urls.isEmpty()) return;
        livePlayingIndex = next;
        runtime.getPlayerController().play(this, channel.urls.get(0), channel.name, channel.headers);
        if (playerHud != null) {
            playerHud.show(channel.name,
                    (liveCatalog == null ? "" : liveCatalog.sourceName) + " · 直播",
                    "按返回键退出 · 上/下键换台", true);
        }
    }

    private void renderDramaMovies() {
        if (moviesContent == null) return;
        moviesContent.removeAllViews();
        List<com.nukacast.app.drama.model.DramaProviderConfig> providers =
                runtime.getDramaService().registry().enabledProviders();
        if (providers.isEmpty()) {
            moviesContent.addView(sectionTitle("短剧"));
            moviesContent.addView(bodyText("还没有短剧目录：网页「短剧」页添加后即可在这里按分类浏览。"));
            return;
        }
        if (dramaItems.isEmpty()) {
            moviesContent.addView(sectionTitle("短剧 · " + providers.get(0).name));
            // A failed load used to leave this line on screen forever: entry retries it.
            moviesContent.addView(bodyText(dramaBrowseRunning
                    ? "正在读取短剧列表…" : "短剧列表还没读到，进入本页会重试。"));
            if (!dramaBrowseRunning) loadDramaBrowse(1);
            return;
        }
        String name = providers.get(0).name;
        for (com.nukacast.app.drama.model.DramaProviderConfig provider : providers) {
            if (provider.id.equals(dramaBrowseProviderId)) name = provider.name;
        }
        moviesContent.addView(sectionTitle("短剧 · " + name + " · " + dramaItems.size() + " 部"));

        // A chip row rather than a paging footer: on a television the viewer walks the grid with the arrow
        // keys, so "more" has to be reachable without scrolling past every card.
        LinearLayout chips = chipRow();
        for (final com.nukacast.app.drama.model.DramaProviderConfig provider : providers) {
            Button chip = actionButton(provider.name, 0);
            chip.setSelected(provider.id.equals(dramaBrowseProviderId));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    dramaBrowseProviderId = provider.id;
                    dramaItems.clear();
                    dramaBrowsePage = 0;
                    renderDramaMovies();
                }
            });
            chips.addView(chip);
        }
        Button more = actionButton(dramaBrowseRunning ? "正在加载…" : "更多", 0);
        more.setEnabled(!dramaBrowseRunning);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { loadDramaBrowse(dramaBrowsePage + 1); }
        });
        chips.addView(more);
        moviesContent.addView(chipRowHolder(chips));
        appendGrid(moviesContent, dramaItems, gridColumns());
    }

    /**
     * Loads one page of a drama catalogue's own listing.
     *
     * <p>The television had no way into a short-drama catalogue except by typing a title, which means the
     * on-screen keyboard: browsing is what makes the tab usable from the sofa. Pages append, so the grid
     * keeps what is already on screen while the next page arrives.
     */
    private void loadDramaBrowse(final int page) {
        List<com.nukacast.app.drama.model.DramaProviderConfig> providers =
                runtime.getDramaService().registry().enabledProviders();
        if (providers.isEmpty() || dramaBrowseRunning) return;
        final String providerId = dramaBrowseProviderId.isEmpty()
                ? providers.get(0).id : dramaBrowseProviderId;
        dramaBrowseProviderId = providerId;
        dramaBrowseRunning = true;
        io.execute(new Runnable() {
            @Override public void run() {
                final com.nukacast.app.drama.model.DramaSearchResult result =
                        runtime.getDramaService().browse(providerId, "", page);
                final List<SearchItem> entries = new ArrayList<SearchItem>();
                if (result.ok) {
                    for (DramaItem item : result.items) entries.add(item.toSearchItem());
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        dramaBrowseRunning = false;
                        if (result.ok) {
                            dramaBrowsePage = page;
                            if (page == 1) dramaItems.clear();
                            dramaItems.addAll(entries);
                        } else {
                            Toast.makeText(MainActivity.this,
                                    result.warning.isEmpty() ? "短剧列表读取失败" : result.warning,
                                    Toast.LENGTH_SHORT).show();
                        }
                        if (PAGE_MOVIES.equals(currentPage)) renderDramaMovies();
                    }
                });
            }
        });
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

        // One row per common category, like a TV box's home page. Filled in the background.
        homeCategoryContainer = new LinearLayout(this);
        homeCategoryContainer.setOrientation(LinearLayout.VERTICAL);
        homeContent.addView(homeCategoryContainer);
        renderHomeCategoryRows();

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

    /** Category rows shown on the home page, in this order, when the site has them. */
    private static final String[] HOME_ROW_CATEGORIES = {"电影", "电视剧", "综艺", "动漫", "纪录片"};

    /** Rows already rendered, so a refresh does not duplicate them. */
    private final java.util.Map<String, LinearLayout> homeRows =
            new java.util.LinkedHashMap<String, LinearLayout>();

    private void renderHomeCategoryRows() {
        if (homeCategoryLoading) return;
        homeCategoryLoading = true;
        io.execute(new Runnable() {
            @Override public void run() {
                final java.util.List<Object[]> rows = new java.util.ArrayList<Object[]>();
                try {
                    java.util.List<com.nukacast.app.tvbox.model.Category> sites = enabledCmsSites();
                    for (String wanted : HOME_ROW_CATEGORIES) {
                        if (rows.size() >= 3) break;
                        java.util.List<SearchItem> items = loadHomeRow(wanted, sites);
                        if (items == null || items.isEmpty()) continue;
                        rows.add(new Object[]{wanted, items});
                    }
                } catch (Throwable error) {
                    AppLog.d("片源", "首页分类行加载失败：" + error.getClass().getSimpleName());
                }
                final java.util.List<Object[]> loaded = rows;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        homeCategoryLoading = false;
                        if (homeCategoryContainer == null) return;
                        // Only the rows replaced on this pass; the container also holds the rows shown
                        // before, so a re-render does not blank the page.
                        if (homeCategoryContainer.getChildCount() > 0) return;
                        for (Object[] row : loaded) {
                            @SuppressWarnings("unchecked")
                            java.util.List<SearchItem> items = (java.util.List<SearchItem>) row[1];
                            addCategoryRow(homeCategoryContainer, (String) row[0], items, 20);
                        }
                    }
                });
            }
        });
    }

    /**
     * Finds a usable category list for {@code wanted} on the enabled sites.
     *
     * <p>Some sites name a category "电影" but return a handful of records for it while another
     * category of the same site carries thousands — measured on 光速资源, where browsing by the
     * declared id was nearly empty. Several sites and several matching categories are therefore tried
     * before the row is dropped.
     */
    private java.util.List<SearchItem> loadHomeRow(String wanted,
            java.util.List<com.nukacast.app.tvbox.model.Category> sites) {
        int siteTries = 0;
        for (com.nukacast.app.tvbox.model.Category site : sites) {
            if (siteTries++ >= 3) break;
            java.util.List<com.nukacast.app.tvbox.model.Category> categories;
            try {
                categories = runtime.getContentService().categories(site.sourceId, site.siteKey);
            } catch (Throwable error) {
                continue;
            }
            int categoryTries = 0;
            for (com.nukacast.app.tvbox.model.Category category : categories) {
                if (category.name == null || !category.name.contains(wanted)) continue;
                if (categoryTries++ >= 3) break;
                try {
                    java.util.List<SearchItem> items = runtime.getContentService()
                            .browse(site.sourceId, site.siteKey, category.id, 1);
                    if (items != null && items.size() >= 6) return items;
                } catch (Throwable error) {
                    AppLog.d("片源", "首页分类行 " + wanted + " 读取失败："
                            + error.getClass().getSimpleName());
                }
            }
        }
        return null;
    }

    /** Same row style as the other home sections, but into a container of the caller's choice. */
    private void addCategoryRow(LinearLayout container, String title, List<SearchItem> items, int limit) {
        container.addView(sectionTitle(title));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(172));
        params.bottomMargin = dp(6);
        container.addView(scroll, params);
    }

    private void addFeaturedPanel(SearchItem item) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(16), dp(16));
        // The hero is the biggest thing on the home page, and it was neither focusable nor clickable —
        // a viewer could look at the recommendation but not open it. It is now a focusable card like any
        // other (the focus state keeps the panel look and adds the ring).
        panel.setBackgroundDrawable(TvTheme.focusable(this));
        panel.setFocusable(true);
        panel.setClipChildren(false);
        panel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (featuredItem != null) openMedia(featuredItem);
            }
        });

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
        featuredItem = item;
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
        buildKeyboard(searchKeyboard, new Runnable() {
            @Override public void run() { searchFromKeyboard(); }
        });
    }

    /** Builds the on-screen keyboard into {@code container}; keys type into the shared query text. */
    private void buildKeyboard(final LinearLayout container, final Runnable onSubmit) {
        container.removeAllViews();
        for (int rowIndex = 0; rowIndex < KEYBOARD_ROWS.length; rowIndex++) {
            String[] row = KEYBOARD_ROWS[rowIndex];
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
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
                    @Override public void onClick(View view) {
                        if (container == searchKeyboard) pressSearchKey(key);
                        else pressLiveSearchKey(key);
                    }
                });
                rowView.addView(button);
            }
            container.addView(rowView);
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

    /** Adds a keyword to the recent list and keeps at most twelve of them. */
    private void rememberSearch(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        if (value.isEmpty()) return;
        recentSearches.remove(value);
        recentSearches.add(0, value);
        while (recentSearches.size() > 12) recentSearches.remove(recentSearches.size() - 1);
        StringBuilder stored = new StringBuilder();
        for (String entry : recentSearches) {
            if (stored.length() > 0) stored.append('\n');
            stored.append(entry);
        }
        getSharedPreferences("search_history", MODE_PRIVATE).edit()
                .putString("recent", stored.toString()).apply();
        renderRecentSearches();
    }

    private void loadRecentSearches() {
        String stored = getSharedPreferences("search_history", MODE_PRIVATE)
                .getString("recent", "");
        recentSearches.clear();
        if (stored == null) return;
        for (String line : stored.split("\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !recentSearches.contains(value)) recentSearches.add(value);
        }
    }

    private void renderRecentSearches() {
        if (searchRecentRow == null) return;
        searchRecentRow.removeAllViews();
        if (recentSearches.isEmpty()) return;
        for (final String keyword : recentSearches) {
            Button chip = actionButton(keyword, 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    searchKeyword.setText(keyword);
                    searchFromKeyboard();
                }
            });
            Button remove = actionButton("×", 0);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    recentSearches.remove(keyword);
                    StringBuilder stored = new StringBuilder();
                    for (String entry : recentSearches) {
                        if (stored.length() > 0) stored.append('\n');
                        stored.append(entry);
                    }
                    getSharedPreferences("search_history", MODE_PRIVATE).edit()
                            .putString("recent", stored.toString()).apply();
                    renderRecentSearches();
                }
            });
            searchRecentRow.addView(chip);
            searchRecentRow.addView(remove);
        }
    }

    private void searchFromKeyboard() {
        String keyword = searchKeyword.getText().toString().trim();
        if (keyword.isEmpty()) return;
        rememberSearch(keyword);
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
                        // Deliberately not copied into dramaItems: that list backs the 短剧 tab, which
                        // browses the catalogue. Sharing it made the tab show a previous search's hits.
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
                    // The reason the television could not open a drama has to be in the log: "缺少短剧目录"
                    // says nothing without the identifier that was (or was not) carried by the card.
                    com.nukacast.app.diagnostics.AppLog.w("短剧", "打开失败 目录=" + providerId
                            + " 剧目=" + dramaId + "：" + error.getMessage(), error);
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
                beginUserPlayback();
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
        showDialog(holder[0]);
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

    /**
     * Marks the start of a playback the viewer asked for.
     *
     * <p>Only here is the line-switching budget restored: resetting it on every internal restart made
     * two dead lines alternate forever instead of giving up after a few attempts.
     */
    private void beginUserPlayback() {
        lineAttempts = 0;
        seriesFinished = false;
    }

    /** What the player is showing, so a failed line can be replaced by another one. */
    private MediaDetail activeDetail;
    private String activeLineName = "";
    private String activeEpisodeId = "";
    private String activeEpisodeName = "";
    private int lineAttempts;
    private long lineSwitchAt;

    private void playEpisode(final MediaDetail detail, final MediaDetail.PlaySource source,
                             final MediaDetail.Episode episode, final int startPositionMs) {
        activeDetail = detail;
        activeLineName = source == null ? "" : source.name;
        activeEpisodeId = episode == null ? "" : episode.id;
        activeEpisodeName = episode == null ? "" : episode.name;
        lineSwitchAt = System.currentTimeMillis();
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
        activeDetail = null;
        seriesFinished = false;
        if (playerHud != null) playerHud.hideActions();
        if (runtime.getAirPlayReceiver().snapshot().sessionActive
                || "AirPlay 镜像".equals(runtime.getState().getActiveMedia())) {
            runtime.getAirPlayReceiver().disconnectSession();
        } else {
            // A DLNA session ends with the return key as well, and the renderer has to hear about it:
            // otherwise the phone keeps showing "playing" for something the TV has stopped.
            if (!runtime.getDlnaRenderer().currentUri().isEmpty()) {
                runtime.getDlnaRenderer().stop();
            }
            runtime.getPlayerController().stop();
        }
    }

    /**
     * Asks whether a newer release exists.
     *
     * <p>Off the UI thread: the feed is a network call, and the check must never delay the settings page.
     * Nothing is installed by itself — a television that installs an update behind the viewer's back is
     * worse than one that is a version behind.
     */
    private void checkForUpdates(final boolean force) {
        if (checkUpdateButton != null) checkUpdateButton.setEnabled(false);
        if (updateSummary != null) updateSummary.setText(getString(R.string.update_checking));
        new Thread(new Runnable() {
            @Override public void run() {
                final com.nukacast.app.update.Updates.Result result =
                        runtime.getUpdateChecker().check(force);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (checkUpdateButton != null) checkUpdateButton.setEnabled(true);
                        if (updateSummary != null) updateSummary.setText(result.summary);
                        if (openReleaseButton != null) {
                            openReleaseButton.setEnabled(!result.apkUrl.isEmpty()
                                    || !result.pageUrl.isEmpty());
                        }
                    }
                });
            }
        }, "update-check").start();
    }

    /** Shows where the new build can be downloaded: a television has no browser to open it with. */
    private void showReleaseAddress() {
        com.nukacast.app.update.Updates.Result result = runtime.getUpdateChecker().last();
        String address = result != null && !result.apkUrl.isEmpty()
                ? result.apkUrl : com.nukacast.app.update.Updates.RELEASES_PAGE;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.update_address)
                .setMessage(address)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
        if (dlnaState != null) {
            String dlnaAddress = runtime.lanAddress();
            dlnaState.setText(runtime.isDlnaRunning()
                    ? "手机/电脑可投屏到此设备（DLNA）：" + (dlnaAddress.isEmpty() ? "" : dlnaAddress)
                    : "DLNA 投屏未启动（需要连接局域网）");
        }
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

    /** Speeds offered by the player menu. */
    private static final float[] PLAY_SPEEDS = {0.5f, 1f, 1.25f, 1.5f, 2f};
    /** Aspect modes: fit inside the screen, stretch, or show at the stream's own size. */
    private static final String[] ASPECT_MODES = {"适应", "填充", "原始"};
    private int aspectMode;

    private void togglePlayerMenu() {
        if (playerHud.actionsVisible()) {
            playerHud.hideActions();
            return;
        }
        final float speed = runtime.getPlayerController().speed();
        String speedLabel = "倍速 " + trimSpeed(speed) + "x";
        String aspectLabel = "画面 " + ASPECT_MODES[Math.max(0, Math.min(aspectMode, ASPECT_MODES.length - 1))];
        // Audio and subtitle entries only appear when the media actually offers them: a menu full of
        // dead entries is what a viewer notices first about a player.
        List<String> actions = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        Collections.addAll(actions, "prev", "next", "speed", "aspect");
        Collections.addAll(labels, "上一集", "下一集", speedLabel, aspectLabel);
        java.util.List<String> audioTracks = runtime.getPlayerController().audioTrackLabels();
        java.util.List<String> textTracks = runtime.getPlayerController().textTrackLabels();
        if (audioTracks.size() > 1) {
            actions.add("audio");
            labels.add("音轨 " + com.nukacast.app.player.PlayerTrackMenu.selectedName(audioTracks));
        }
        if (!textTracks.isEmpty()) {
            actions.add("subtitle");
            labels.add("字幕 " + com.nukacast.app.player.PlayerTrackMenu.selectedName(textTracks));
        }
        actions.add("exit");
        labels.add("退出");
        playerHud.showActions(labels.toArray(new String[0]), actions.toArray(new String[0]),
                runtime.getPlayerController().snapshot().playing ? "播放中" : "已暂停",
                new com.nukacast.app.ui.PlayerHudView.ActionListener() {
                    @Override public void onAction(String action) {
                        onPlayerMenuAction(action);
                    }
                });
    }

    /** The current episode or channel of the playing media. */
    private void onPlayerMenuAction(String action) {
        if ("stop".equals(action)) {
            // Leaving full-screen playback from a script: an injected BACK key event does not reach
            // onBackPressed (the framework handles it), so the debug API needs its own way out.
            stopActivePlayback();
            return;
        }
        if ("prev".equals(action)) {
            stepEpisode(-1);
            return;
        }
        if ("next".equals(action)) {
            stepEpisode(1);
            return;
        }
        if ("speed".equals(action)) {
            float current = runtime.getPlayerController().speed();
            int index = 0;
            for (int i = 0; i < PLAY_SPEEDS.length; i++) {
                if (Math.abs(PLAY_SPEEDS[i] - current) < 0.01f) index = i;
            }
            float next = PLAY_SPEEDS[(index + 1) % PLAY_SPEEDS.length];
            runtime.getPlayerController().setSpeed(next);
            AppLog.i("播放器", "倍速切换为 " + trimSpeed(next) + "x");
            togglePlayerMenu();
            togglePlayerMenu();
            return;
        }
        if ("aspect".equals(action)) {
            aspectMode = (aspectMode + 1) % ASPECT_MODES.length;
            applyAspectMode();
            togglePlayerMenu();
            togglePlayerMenu();
            return;
        }
        if ("audio".equals(action)) {
            java.util.List<String> tracks = runtime.getPlayerController().audioTrackLabels();
            int next = com.nukacast.app.player.PlayerTrackMenu.nextIndex(tracks);
            if (next >= 0) {
                runtime.getPlayerController().selectAudioTrack(next);
                AppLog.i("播放器", "切换到音轨：" + tracks.get(next));
            }
            togglePlayerMenu();
            togglePlayerMenu();
            return;
        }
        if ("subtitle".equals(action)) {
            java.util.List<String> tracks = runtime.getPlayerController().textTrackLabels();
            int next = com.nukacast.app.player.PlayerTrackMenu.nextIndex(tracks);
            runtime.getPlayerController().selectTextTrack(next);
            AppLog.i("播放器", next < 0 ? "字幕已关闭" : "切换到字幕：" + tracks.get(next));
            togglePlayerMenu();
            togglePlayerMenu();
            return;
        }
        if ("exit".equals(action)) {
            playerHud.hideActions();
            stopActivePlayback();
        }
    }

    private static String trimSpeed(float speed) {
        String text = String.valueOf(speed);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /**
     * Resizes the video surface.
     *
     * <p>Decoder output is stretched to the surface, so the surface is what decides whether the
     * picture is letterboxed, stretched or shown at its own size.
     */
    private void applyAspectMode() {
        if (videoSurface == null) return;
        android.view.ViewGroup.LayoutParams raw = videoSurface.getLayoutParams();
        if (!(raw instanceof android.widget.FrameLayout.LayoutParams)) return;
        android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) raw;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        com.nukacast.app.player.PlayerController.Snapshot playback =
                runtime.getPlayerController().snapshot();
        if (aspectMode == 1 || playback.videoWidth <= 0 || playback.videoHeight <= 0) {
            params.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
        } else {
            float scale;
            if (aspectMode == 2) {
                scale = 1f;
            } else {
                float byWidth = (float) screenWidth / playback.videoWidth;
                float byHeight = (float) screenHeight / playback.videoHeight;
                scale = Math.min(byWidth, byHeight);
            }
            params.width = Math.min(screenWidth, Math.round(playback.videoWidth * scale));
            params.height = Math.min(screenHeight, Math.round(playback.videoHeight * scale));
        }
        params.gravity = android.view.Gravity.CENTER;
        videoSurface.setLayoutParams(params);
        AppLog.i("播放器", "画面比例：" + ASPECT_MODES[aspectMode]
                + "（" + params.width + "x" + params.height + "）");
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
        // Once per distinct crash: repeating it on every start blocks the screen and swallows the
        // remote's keys until someone dismisses the dialog. The text stays for the diagnostics export.
        if (!com.nukacast.app.diagnostics.CrashPrompt.shouldPrompt(report, crashPromptSignature())) {
            return;
        }
        markCrashPrompted(report);
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

    private static final String CRASH_PROMPT_PREFS = "crash_prompt";
    private static final String CRASH_PROMPT_KEY = "shownSignature";

    private String crashPromptSignature() {
        return getSharedPreferences(CRASH_PROMPT_PREFS, MODE_PRIVATE)
                .getString(CRASH_PROMPT_KEY, "");
    }

    private void markCrashPrompted(String report) {
        getSharedPreferences(CRASH_PROMPT_PREFS, MODE_PRIVATE).edit()
                .putString(CRASH_PROMPT_KEY,
                        com.nukacast.app.diagnostics.CrashPrompt.signature(report))
                .apply();
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
