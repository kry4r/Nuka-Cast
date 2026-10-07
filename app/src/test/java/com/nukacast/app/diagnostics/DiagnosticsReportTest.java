package com.nukacast.app.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The bundle is what a user is asked to send, so it must build without a runtime and must carry the
 * sections that make a failure analysable away from the device.
 */
public final class DiagnosticsReportTest {
    @Test public void includesEverySectionWithoutARuntime() {
        String report = DiagnosticsReport.build(null, null, AppLog.Level.ERROR);

        assertTrue(report.contains("NukaCast 诊断报告"));
        for (String section : new String[]{"== 设备 ==", "== 服务 ==", "== AirPlay ==", "== HTTP 栈 ==",
                "== 直播源 ==", "== 短剧目录 ==", "== 阶段诊断 ==", "== 本机不支持的站点 ==",
                "== 上次运行 ==", "== 上次 Java 闪退 ==", "== 运行日志 =="}) {
            assertTrue("缺少小节：" + section, report.contains(section));
        }
        assertTrue(report.contains("日志级别：错误"));
    }

    @Test public void carriesTheRecordedStages() {
        StageTrace.Trace trace = StageTrace.start("airplay", "video");
        trace.stage("codec_config");
        StageTrace.componentFailure("airplay", "video", "decoder_blocked",
                new IllegalStateException("解码器反复失败"));

        String report = DiagnosticsReport.build(null, null, null);

        assertTrue(report.contains("airplay/video"));
        assertTrue(report.contains("decoder_blocked"));
    }

    @Test public void fileNamesAreSortableAndDistinct() {
        String first = DiagnosticsReport.fileName();
        assertTrue(first.startsWith("nukacast-diagnostics-"));
        assertTrue(first.endsWith(".txt"));
    }
}
