package com.nukacast.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.nukacast.app.airplay.AirPlayReceiver;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.DeviceProfile;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.library.LibraryItem;
import com.nukacast.app.player.PlayerController;
import com.nukacast.app.service.NukaCastService;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.tvbox.model.SearchResponse;
import com.nukacast.app.ui.MediaCardView;
import com.nukacast.app.ui.PosterImageLoader;
import com.nukacast.app.ui.TvTheme;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements AppState.Listener, SurfaceHolder.Callback {
    private static final String PAGE_HOME = "home";
    private static final String PAGE_MOVIES = "movies";
    private static final String PAGE_CAST = "cast";
    private static final String PAGE_SETTINGS = "settings";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final PosterImageLoader images = new PosterImageLoader();
    private final List<SearchItem> homeItems = new ArrayList<SearchItem>();
    private NukaRuntime runtime;
    private View appShell;
    private View homePage;
    private View moviesPage;
    private View castPage;
    private View settingsPage;
    private LinearLayout homeContent;
    private LinearLayout moviesContent;
    private TextView homeLoading;
    private TextView serviceStatus;
    private TextView networkStatus;
    private TextView webAddress;
    private TextView pairingCode;
    private TextView airplayState;
    private TextView airplayStats;
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
    private Button refreshSourcesButton;
    private Button scanStorageButton;
    private Button themeToggleButton;
    private String currentPage = PAGE_HOME;
    private String currentMovieFilter = "";
    private boolean homeRequestRunning;
    private boolean homeLoaded;
    private int lastSiteCount = -1;

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
        startService(new Intent(this, NukaCastService.class));
        showPage(PAGE_HOME);
        render();
        loadHome(false);
        findViewById(R.id.navHome).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        render();
        if (PAGE_HOME.equals(currentPage)) renderHome();
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
            runtime.getPlayerController().stop();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSearchDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            runtime.getPlayerController().stop();
            showPage(PAGE_SETTINGS);
            findViewById(R.id.navSettings).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        castPage = findViewById(R.id.castPage);
        settingsPage = findViewById(R.id.settingsPage);
        homeContent = (LinearLayout) findViewById(R.id.homeContent);
        moviesContent = (LinearLayout) findViewById(R.id.moviesContent);
        homeLoading = (TextView) findViewById(R.id.homeLoading);
        serviceStatus = (TextView) findViewById(R.id.serviceStatus);
        networkStatus = (TextView) findViewById(R.id.networkStatus);
        webAddress = (TextView) findViewById(R.id.webAddress);
        pairingCode = (TextView) findViewById(R.id.pairingCode);
        airplayState = (TextView) findViewById(R.id.airplayState);
        airplayStats = (TextView) findViewById(R.id.airplayStats);
        deviceSummary = (TextView) findViewById(R.id.deviceSummary);
        codecSummary = (TextView) findViewById(R.id.codecSummary);
        sourceSummary = (TextView) findViewById(R.id.sourceSummary);
        storageSummary = (TextView) findViewById(R.id.storageSummary);
        refreshSourcesButton = (Button) findViewById(R.id.refreshSourcesButton);
        scanStorageButton = (Button) findViewById(R.id.scanStorageButton);
        themeToggleButton = (Button) findViewById(R.id.themeToggleButton);
        videoSurface = (SurfaceView) findViewById(R.id.videoSurface);
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
            @Override public void onClick(View view) { showSearchDialog(); }
        });

        bindFilter(R.id.filterAll, "");
        bindFilter(R.id.filterMovie, "电影");
        bindFilter(R.id.filterSeries, "电视剧");
        bindFilter(R.id.filterVariety, "综艺");
        bindFilter(R.id.filterAnime, "动漫");

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
        findViewById(R.id.clearPairingButton).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                runtime.getPairingManager().revokeAll();
                Toast.makeText(MainActivity.this, "已清除网页配对", Toast.LENGTH_SHORT).show();
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
        List<SearchItem> filtered = filter(homeItems, filter);
        renderMovieGrid(filter.isEmpty() ? "最近更新" : filter, filtered);
    }

    private void setFilterSelection(String filter) {
        findViewById(R.id.filterAll).setSelected(filter.isEmpty());
        findViewById(R.id.filterMovie).setSelected("电影".equals(filter));
        findViewById(R.id.filterSeries).setSelected("电视剧".equals(filter));
        findViewById(R.id.filterVariety).setSelected("综艺".equals(filter));
        findViewById(R.id.filterAnime).setSelected("动漫".equals(filter));
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

        featuredEyebrow = featuredText(12, TvTheme.accent(this), true);
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
            featuredEyebrow.setText("NUKACAST");
            featuredTitle.setText("片库准备中");
            featuredMeta.setText(runtime.getState().getStatusMessage());
            featuredPlot.setText("");
            featuredPoster.setImageDrawable(null);
            return;
        }
        featuredEyebrow.setText(safe(item.siteName).isEmpty() ? "精选推荐" : item.siteName);
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
        List<CardEntry> entries = new ArrayList<CardEntry>();
        int count = Math.min(16, items.size());
        for (int i = 0; i < count; i++) {
            LibraryItem library = items.get(i);
            entries.add(new CardEntry(library.toSearchItem(), library, resume,
                    library.positionMs, library.durationMs));
        }
        addTrack(horizontalTrack(entries));
    }

    private void addMediaSection(String title, List<SearchItem> items, int limit) {
        homeContent.addView(sectionTitle(title));
        List<CardEntry> entries = new ArrayList<CardEntry>();
        int count = Math.min(limit, items.size());
        for (int i = 0; i < count; i++) {
            entries.add(new CardEntry(items.get(i), null, false, 0, 0));
        }
        addTrack(horizontalTrack(entries));
    }

    private void addTrack(HorizontalGridView grid) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(312));
        params.bottomMargin = dp(26);
        homeContent.addView(grid, params);
    }

    private HorizontalGridView horizontalTrack(List<CardEntry> entries) {
        HorizontalGridView grid = new HorizontalGridView(this);
        grid.setHorizontalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setNumRows(1);
        grid.setRowHeight(dp(296));
        grid.setItemSpacing(dp(16));
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_LOW_EDGE);
        grid.setWindowAlignmentOffset(dp(8));
        grid.setPadding(dp(8), dp(10), dp(42), dp(8));
        grid.setAdapter(new CardAdapter(entries));
        return grid;
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
        int columns = gridColumns();
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(302));
                rowParams.bottomMargin = dp(14);
                moviesContent.addView(row, rowParams);
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

    private void showSearchDialog() {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText keyword = new EditText(this);
        keyword.setSingleLine(true);
        keyword.setHint("片名、演员或关键词");
        keyword.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(keyword, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        final String[] types = {"全部类型", "电影", "电视剧", "综艺", "动漫"};
        final Spinner type = spinner(types);
        addField(content, "类型", type);

        int year = Calendar.getInstance().get(Calendar.YEAR);
        String[] years = {"全部年份", String.valueOf(year), String.valueOf(year - 1),
                String.valueOf(year - 2), String.valueOf(year - 3), "2020", "2010"};
        final Spinner yearSpinner = spinner(years);
        addField(content, "年份", yearSpinner);

        final String[] regions = {"全部地区", "中国大陆", "中国香港", "中国台湾", "美国", "日本", "韩国"};
        final Spinner region = spinner(regions);
        addField(content, "地区", region);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("全站搜索")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String value = keyword.getText().toString().trim();
                        if (value.isEmpty()) {
                            keyword.setError("请输入搜索关键词");
                            return;
                        }
                        SearchQuery query = new SearchQuery();
                        query.keyword = value;
                        query.contentType = selected(type, types, "全部类型");
                        query.year = selected(yearSpinner, null, "全部年份");
                        query.region = selected(region, regions, "全部地区");
                        dialog.dismiss();
                        performSearch(query);
                    }
                });
                keyword.requestFocus();
            }
        });
        dialog.getWindow();
        dialog.show();
    }

    private void performSearch(final SearchQuery query) {
        showPage(PAGE_MOVIES);
        currentMovieFilter = "";
        setFilterSelection("");
        moviesContent.removeAllViews();
        moviesContent.addView(bodyText("正在全站搜索“" + query.keyword + "”…"));
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final SearchResponse response = runtime.getSearchEngine().search(query);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String title = "“" + query.keyword + "” · " + response.items.size()
                                    + " 个结果 · " + response.searchedSites + " 个站点";
                            renderMovieGrid(title, response.items);
                            if (response.partial) {
                                Toast.makeText(MainActivity.this, "部分站点超时或不可用", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("搜索失败", error); }
                    });
                }
            }
        });
    }

    private void openMedia(final SearchItem item) {
        Toast.makeText(this, "正在加载“" + item.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
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
                    if (!info.direct) throw new IllegalArgumentException(
                            info.error.isEmpty() ? "无法解析播放地址" : info.error);
                    runtime.getMediaLibrary().start(detail, source.name, episode.id, episode.name);
                    runtime.getPlayerController().play(MainActivity.this, info.url, title,
                            info.headers, startPositionMs);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { renderHome(); }
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
        Toast.makeText(this, "继续播放“" + item.name + "”", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String title = item.name + (item.episodeName.isEmpty()
                            ? "" : " · " + item.episodeName);
                    final PlaybackInfo info = runtime.getContentService().resolve(item.sourceId,
                            item.siteKey, item.playSource, item.episodeId, title);
                    if (!info.direct) throw new IllegalArgumentException(
                            info.error.isEmpty() ? "无法解析播放地址" : info.error);
                    runtime.getMediaLibrary().start(item, item.playSource, item.episodeId, item.episodeName);
                    runtime.getPlayerController().play(MainActivity.this, info.url, title,
                            info.headers, item.positionMs);
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showError("续播失败", error); }
                    });
                }
            }
        });
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
        refreshSourcesButton.setEnabled(false);
        refreshSourcesButton.setText("正在刷新…");
        runtime.getTvBoxRepository().refreshAllAsync(new com.nukacast.app.tvbox.TvBoxRepository.RefreshListener() {
            @Override public void onRefreshComplete(final int configs, final int sites) {
                runtime.getState().updateSources(configs, sites);
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

    private void scanStorage() {
        if (runtime.getStorageLibrary().isScanning()) return;
        scanStorageButton.setEnabled(false);
        scanStorageButton.setText("正在扫描…");
        runtime.getStorageLibrary().scanAllAsync(new com.nukacast.app.storage.StorageLibrary.ScanListener() {
            @Override public void onComplete(final int mounts, final int files) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        scanStorageButton.setEnabled(true);
                        scanStorageButton.setText("扫描片库");
                        storageSummary.setText(mounts + " 个挂载 · " + files + " 个媒体文件");
                        homeLoaded = false;
                        loadHome(true);
                    }
                });
            }
        });
    }

    private void render() {
        AppState state = runtime.getState();
        DeviceProfile profile = runtime.getDeviceProfile();
        serviceStatus.setText(state.getStatusMessage());
        String address = runtime.getWebAddress();
        String host = address.replace("http://", "").replace(":" + NukaRuntime.CONTROL_PORT, "");
        networkStatus.setText("0.0.0.0".equals(host) ? "网络未连接" : "已联网 · " + host);
        webAddress.setText(address);
        pairingCode.setText("配对码 " + runtime.getPairingManager().getPairingCode());
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
        airplayStats.setText(String.format(Locale.CHINA,
                "端口 %d · 视频 %,d 帧（丢弃 %,d）· 音频 %,d 包（丢弃 %,d）",
                airplay.port, airplay.videoFrames, airplay.videoDrops,
                airplay.audioPackets, airplay.audioDrops));

        boolean hasMedia = state.getActiveMedia() != null && !state.getActiveMedia().isEmpty();
        appShell.setVisibility(hasMedia ? View.GONE : View.VISIBLE);
        videoSurface.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
    }

    private String castState(AirPlayReceiver.Snapshot snapshot) {
        if ("error".equals(snapshot.state)) return "启动失败 · " + safe(snapshot.error);
        if ("ready".equals(snapshot.state)) return "无 PIN · 1080p30 · 等待设备连接";
        return "接收器" + safe(snapshot.state);
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

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final List<CardEntry> entries;

        CardAdapter(List<CardEntry> entries) {
            this.entries = entries;
            setHasStableIds(true);
        }

        @Override public CardHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final CardEntry entry = entries.get(viewType);
            final SearchItem item = entry.item;
            MediaCardView card = card(item, entry.positionMs, entry.durationMs);
            // Leanback converts generic params to its required GridLayoutManager.LayoutParams.
            card.setLayoutParams(new ViewGroup.LayoutParams(dp(164), dp(292)));
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (entry.resume && entry.library != null && !entry.library.episodeId.isEmpty()) {
                        resume(entry.library);
                    } else {
                        openMedia(item);
                    }
                }
            });
            bindFavoriteShortcut(card, item);
            return new CardHolder(card);
        }

        @Override public void onBindViewHolder(CardHolder holder, int position) {}
        @Override public int getItemCount() { return entries.size(); }
        @Override public int getItemViewType(int position) { return position; }
        @Override public long getItemId(int position) { return entries.get(position).item.dedupeKey().hashCode(); }
    }

    private static final class CardHolder extends RecyclerView.ViewHolder {
        CardHolder(View itemView) { super(itemView); }
    }

    private static final class CardEntry {
        final SearchItem item;
        final LibraryItem library;
        final boolean resume;
        final int positionMs;
        final int durationMs;

        CardEntry(SearchItem item, LibraryItem library, boolean resume, int positionMs, int durationMs) {
            this.item = item;
            this.library = library;
            this.resume = resume;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
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

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setFocusable(true);
        return spinner;
    }

    private void addField(LinearLayout parent, String label, Spinner spinner) {
        TextView title = bodyText(label);
        title.setPadding(0, dp(12), 0, dp(4));
        parent.addView(title);
        parent.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
    }

    private String selected(Spinner spinner, String[] ignored, String allLabel) {
        Object value = spinner.getSelectedItem();
        String text = value == null ? "" : value.toString();
        return allLabel.equals(text) ? "" : text;
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
        Toast.makeText(this, prefix + "：" + detail, Toast.LENGTH_LONG).show();
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
