package com.nukacast.app.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.Debug;

import com.nukacast.app.CrashReporter;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.core.DeviceProfile;
import com.nukacast.app.tvbox.SiteFailureStore;
import com.nukacast.app.tvbox.model.ConfigSource;
import com.nukacast.app.tvbox.model.LivePlaylist;
import com.nukacast.app.drama.model.DramaProviderConfig;
import com.nukacast.app.live.model.LiveSourceInfo;
import com.nukacast.app.net.HttpStack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One-click diagnostic export: everything needed to analyse a failure away from the device.
 *
 * <p>The device is in a living room, not on a workbench. Instead of asking the owner to read
 * numbers off the TV, this builds a single text bundle with the log, device facts, source state,
 * stage trace, decoder counters, skipped sites and the previous run's memory curve, so it can be
 * attached to a report as-is.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {}

    public static final String FILE_PREFIX = "nukacast-diagnostics-";

    /** Suggested file name, also used by the web download. */
    public static String fileName() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return FILE_PREFIX + format.format(new Date()) + ".txt";
    }

    /** Builds the full bundle. {@code level} filters the log section; null exports every level. */
    public static String build(Context context, NukaRuntime runtime, AppLog.Level level) {
        StringBuilder report = new StringBuilder();
        header(report, context, runtime, level);
        device(report, runtime);
        serviceState(report, runtime);
        airPlay(report, runtime);
        sources(report, runtime);
        liveSources(runtime, report);
        dramaProviders(runtime, report);
        stages(report);
        skippedSites(report, runtime);
        previousRun(report);
        javaCrash(report, context);
        postMortem(report, context);
        log(report, level);
        return report.toString();
    }

    /** The platform's own log from around the previous process's death, when it could be read. */
    private static void postMortem(StringBuilder report, Context context) {
        report.append("== 上次退出时的系统日志 ==\n");
        String captured = context == null ? "" : PostMortemLog.read(context);
        if (captured.isEmpty()) {
            report.append("（未捕获。系统日志需要 root 权限，重启后也会被环形缓冲区覆盖）\n");
        } else {
            report.append(captured).append('\n');
        }
        report.append('\n');
    }

    /** Writes the bundle next to the app's own files, which needs no storage permission. */
    public static java.io.File write(Context context, NukaRuntime runtime, AppLog.Level level) {
        java.io.File directory = context.getExternalFilesDir(null);
        if (directory == null) directory = context.getFilesDir();
        if (directory != null && !directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        java.io.File target = new java.io.File(directory, fileName());
        try {
            java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(target),
                    java.nio.charset.Charset.forName("UTF-8"));
            try {
                writer.write(build(context, runtime, level));
                writer.flush();
            } finally {
                writer.close();
            }
            return target;
        } catch (Exception error) {
            AppLog.e("诊断", "导出诊断文件失败", error);
            return null;
        }
    }

    private static void header(StringBuilder report, Context context, NukaRuntime runtime,
                              AppLog.Level level) {
        report.append("NukaCast 诊断报告\n");
        report.append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        report.append("应用版本：").append(com.nukacast.app.BuildConfig.VERSION_NAME)
                .append("（").append(com.nukacast.app.BuildConfig.VERSION_CODE).append("）\n");
        report.append("日志级别：").append(level == null ? "全部" : level.label).append('\n');
        report.append("说明：本文件由电视端一键导出，包含运行日志与设备状态，可直接附在问题反馈里。\n");
        report.append('\n');
    }

    /**
     * The numbers the platform's killer uses. Included because plugin work lives in native memory:
     * a Java heap at 1% says nothing about whether the process is about to be ended.
     */
    private static void processMemory(StringBuilder report, NukaRuntime runtime) {
        Context context = runtime == null ? null : runtime.getContext();
        long totalRam = context == null ? 0L : ProcessMemory.totalRamBytes(context);
        long budget = context == null ? 0L : ProcessMemory.pluginBudgetBytes(context);
        report.append("进程内存：RSS ").append(ProcessMemory.megabytes(ProcessMemory.rssBytes()))
                .append(" · PSS ").append(ProcessMemory.megabytes(
                        context == null ? 0L : ProcessMemory.totalPssBytes(context)))
                .append(" · 虚拟 ").append(ProcessMemory.megabytes(ProcessMemory.vmSizeBytes()))
                .append(" · 线程 ").append(ProcessMemory.threadCount())
                .append(" · oom_score_adj ").append(ProcessMemory.oomScoreAdj()).append('\n');
        report.append("内存预算：插件上限 ").append(ProcessMemory.megabytes(budget))
                .append("（设备总内存 ").append(ProcessMemory.megabytes(totalRam)).append("）");
        if (runtime != null) {
            String sessions = runtime.pluginSessionSummary();
            if (sessions != null && !sessions.isEmpty()) report.append(" · ").append(sessions);
        }
        report.append('\n');
    }

    private static void device(StringBuilder report, NukaRuntime runtime) {
        report.append("== 设备 ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        DeviceProfile profile = runtime.getDeviceProfile();
        Runtime java = Runtime.getRuntime();
        report.append("厂商/型号：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("系统：Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）")
                .append(" · ABI ").append(profile == null ? "-" : profile.primaryAbi).append('\n');
        report.append("显示：").append(profile == null ? "-"
                : profile.displayWidth + "x" + profile.displayHeight
                + " @ " + String.format(Locale.US, "%.1f", profile.refreshRate) + "Hz").append('\n');
        report.append("内存：堆 ").append(megabytes(java.totalMemory() - java.freeMemory()))
                .append(" / 上限 ").append(megabytes(java.maxMemory()))
                .append(" · 原生 ").append(megabytes(Debug.getNativeHeapAllocatedSize())).append('\n');
        processMemory(report, runtime);
        report.append("H.264：").append(profile == null ? "-"
                : profile.hasHardwareAvcDecoder ? "硬解优先" : "无硬解").append('\n');
        if (profile != null && profile.warnings != null) {
            for (String warning : profile.warnings) report.append("警告：").append(warning).append('\n');
        }
        report.append('\n');
    }

    private static void serviceState(StringBuilder report, NukaRuntime runtime) {
        report.append("== 服务 ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        report.append("状态：").append(runtime.getState().getServiceState())
                .append(" · ").append(runtime.getState().getStatusMessage()).append('\n');
        // The LAN address belongs in the report: it is what a debug session (or MCP client) needs to
        // talk to the device, and typing it off a TV screen by hand is not reasonable.
        report.append("控制地址：").append(runtime.getWebAddress())
                .append(" · 调试接口 /api/debug/*\n");
        report.append("启用站点：").append(runtime.getTvBoxRepository().getEnabledSites().size())
                .append(" 个 · 搜索上限 ").append(com.nukacast.app.tvbox.SearchEngine.MAX_SEARCH_SITES)
                .append(" 个/次 · 首页插件站点上限 ")
                .append(com.nukacast.app.tvbox.TvBoxContentService.MAX_PLUGIN_HOME_SITES)
                .append(" 个\n");
        com.nukacast.app.tvbox.SiteHealthStore health = runtime.getSiteHealthStore();
        int[] healthCounts = health.counts();
        if (healthCounts[0] + healthCounts[1] > 0) {
            report.append("站点体检：可用 ").append(healthCounts[0])
                    .append(" / 不可用 ").append(healthCounts[1]).append('\n');
        }
        report.append("插件会话：").append(runtime.getSpiderManager().sessionSummary()).append('\n');
        for (ConfigSource source : runtime.getSourceStore().getSources()) {
            report.append("配置 [").append(source.name).append("] ")
                    .append(source.url).append('\n');
            report.append("    resolved=").append(source.resolvedUrl)
                    .append(" kind=").append(source.kind)
                    .append(" sites=").append(source.siteCount)
                    .append(" latency=").append(source.latencyMs).append("ms\n");
            if (source.error != null && !source.error.isEmpty()) {
                report.append("    错误：").append(source.error).append('\n');
            }
            if (source.searchError != null && !source.searchError.isEmpty()) {
                report.append("    搜索错误：").append(source.searchError).append('\n');
            }
        }
        for (SiteFailureStore.Failure failure : runtime.getContentService().homeFailures()) {
            report.append("首页失败 [").append(failure.siteName).append("] ")
                    .append(failure.error).append('\n');
        }
        report.append('\n');
    }

    private static void airPlay(StringBuilder report, NukaRuntime runtime) {
        report.append("== AirPlay ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        com.nukacast.app.airplay.AirPlayReceiver.Snapshot snapshot =
                runtime.getAirPlayReceiver().snapshot();
        report.append("状态：").append(snapshot.state)
                .append(" · 端口 ").append(snapshot.port).append('\n');
        report.append("身份：").append(snapshot.identity).append('\n');
        report.append("视频：").append(snapshot.videoFrames).append(" 包 / ")
                .append(snapshot.videoKeyFrames).append(" IDR · 丢弃 ")
                .append(snapshot.videoDrops).append('\n');
        report.append("音频：").append(snapshot.audioPackets).append(" 包 · 丢弃 ")
                .append(snapshot.audioDrops).append('\n');
        report.append("解码：").append(snapshot.decoderName)
                .append(snapshot.decoderSoftwareFallback ? "（软件回退）" : "（硬解）")
                .append(" 输入 ").append(snapshot.decoderInputs)
                .append(" / 输出 ").append(snapshot.decoderOutputs)
                .append(" 格式切换 ").append(snapshot.decoderFormatChanges).append('\n');
        if (snapshot.error != null && !snapshot.error.isEmpty()) {
            report.append("错误：").append(snapshot.error).append('\n');
        }
        report.append('\n');
    }

    private static void sources(StringBuilder report, NukaRuntime runtime) {
        report.append("== HTTP 栈 ==\n");
        report.append("旧版 TLS 回退：").append(HttpStack.degraded() ? "是" : "否").append('\n');
        if (HttpStack.degraded()) report.append("原因：").append(HttpStack.initError()).append('\n');
        report.append('\n');
    }

    private static void liveSources(NukaRuntime runtime, StringBuilder report) {
        report.append("== 直播源 ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        for (LivePlaylist playlist : runtime.getTvBoxRepository().getLiveSourceStore().all()) {
            report.append("用户源 [").append(playlist.name).append("] ")
                    .append(playlist.url)
                    .append(playlist.enabled ? " 启用" : " 停用").append('\n');
            if (playlist.error != null && !playlist.error.isEmpty()) {
                report.append("    错误：").append(playlist.error).append('\n');
            }
        }
        List<LiveSourceInfo> fromConfig = runtime.getLiveService().sources();
        for (LiveSourceInfo info : fromConfig) {
            if (info.sourceId != null && info.sourceId.startsWith("user:")) continue;
            report.append("配置源 [").append(info.name).append("] ").append(info.url).append('\n');
        }
        report.append('\n');
    }

    private static void dramaProviders(NukaRuntime runtime, StringBuilder report) {
        report.append("== 短剧目录 ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        for (DramaProviderConfig provider : runtime.getDramaService().registry().providers()) {
            report.append("[").append(provider.name).append("] ").append(provider.baseUrl)
                    .append(" kind=").append(provider.kind)
                    .append(" category=").append(provider.categoryId)
                    .append(provider.enabled ? " 启用" : " 停用").append('\n');
            if (provider.error != null && !provider.error.isEmpty()) {
                report.append("    错误：").append(provider.error).append('\n');
            }
        }
        report.append('\n');
    }

    private static void stages(StringBuilder report) {
        report.append("== 阶段诊断 ==\n");
        List<StageTrace.Record> records = StageTrace.snapshot();
        if (records.isEmpty()) report.append("（无记录）\n");
        for (StageTrace.Record record : records) {
            report.append(record.scope).append('/').append(record.subject)
                    .append(" ").append(record.stage)
                    .append(" ").append(record.result)
                    .append(record.elapsedMs > 0 ? " " + record.elapsedMs + "ms" : "");
            if (record.errorCode != null && !record.errorCode.isEmpty()) {
                report.append(" · ").append(record.errorCode);
            }
            if (record.rootCauseClass != null && !record.rootCauseClass.isEmpty()) {
                report.append(" · ").append(record.rootCauseClass);
            }
            if (record.detail != null && !record.detail.isEmpty()) {
                report.append(" · ").append(record.detail);
            }
            report.append('\n');
        }
        report.append('\n');
    }

    private static void skippedSites(StringBuilder report, NukaRuntime runtime) {
        report.append("== 本机不支持的站点 ==\n");
        if (runtime == null) {
            report.append("运行时不可用\n\n");
            return;
        }
        List<com.nukacast.app.tvbox.SiteCompatibilityStore.Issue> issues =
                runtime.getSpiderManager().compatibility().snapshot();
        if (issues.isEmpty()) report.append("（无）\n");
        for (com.nukacast.app.tvbox.SiteCompatibilityStore.Issue issue : issues) {
            report.append(issue.siteName).append("：").append(issue.reason)
                    .append(issue.permanent ? "（不再重试）" : "（冷却中）").append('\n');
        }
        report.append('\n');
    }

    private static void previousRun(StringBuilder report) {
        report.append("== 上次运行 ==\n");
        SessionMarker.Run run = SessionMarker.interruptedRun();
        if (run == null) {
            report.append("（无记录）\n\n");
            return;
        }
        report.append("开始：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(run.startedAt))).append('\n');
        report.append("时长：").append(run.durationMs() / 1000).append(" 秒 · 版本 ").append(run.version)
                .append(" · ").append(run.device).append('\n');
        report.append("退出：").append(run.endedCleanly ? "正常" : "被系统或外部结束").append('\n');
        report.append("内存峰值：").append(run.peakHeapPercent()).append('%').append('\n');
        for (SessionMarker.Sample sample : run.samples) {
            report.append("    ").append(new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date(sample.at)))
                    .append(" 堆 ").append(sample.heapPercent()).append('%')
                    .append(" 原生 ").append(megabytes(sample.nativeHeapBytes))
                    .append(" 系统可用 ").append(megabytes(sample.availableMemoryBytes))
                    .append(sample.stage == null || sample.stage.isEmpty() ? "" : " · " + sample.stage)
                    .append('\n');
        }
        report.append('\n');
    }

    private static void javaCrash(StringBuilder report, Context context) {
        report.append("== 上次 Java 闪退 ==\n");
        String crash = context == null ? "" : CrashReporter.read(context);
        report.append(crash.isEmpty() ? "（无）" : crash);
        report.append("\n\n");
    }

    private static void log(StringBuilder report, AppLog.Level level) {
        report.append("== 运行日志 ==\n");
        String text = AppLog.format(level);
        report.append(text.isEmpty() ? "（无）" : text);
        report.append('\n');
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
    }
}
