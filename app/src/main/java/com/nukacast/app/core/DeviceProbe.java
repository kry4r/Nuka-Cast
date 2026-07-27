package com.nukacast.app.core;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Locale;

public final class DeviceProbe {
    private DeviceProbe() {}

    public static DeviceProfile inspect(Context context) {
        DeviceProfile profile = new DeviceProfile();
        profile.manufacturer = safe(Build.MANUFACTURER);
        profile.model = safe(Build.MODEL);
        profile.product = safe(Build.PRODUCT);
        profile.androidVersion = safe(Build.VERSION.RELEASE);
        profile.sdk = Build.VERSION.SDK_INT;
        profile.primaryAbi = safe(Build.CPU_ABI);
        profile.appMemoryBytes = Runtime.getRuntime().maxMemory();

        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memoryInfo);
            profile.totalMemoryBytes = memoryInfo.totalMem;
        }

        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            profile.displayWidth = metrics.widthPixels;
            profile.displayHeight = metrics.heightPixels;
            profile.refreshRate = windowManager.getDefaultDisplay().getRefreshRate();
        }

        inspectAvc(profile);
        if (profile.sdk != 17) {
            profile.warnings.add("目标设备为 API 17，当前检测到 API " + profile.sdk);
        }
        if (!profile.hasHardwareAvcDecoder) {
            profile.warnings.add("1080p30 镜像需要硬件 H.264 解码器");
        }
        return profile;
    }

    private static void inspectAvc(DeviceProfile profile) {
        try {
            int codecCount = MediaCodecList.getCodecCount();
            for (int i = 0; i < codecCount; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(type)) {
                        continue;
                    }
                    String name = info.getName();
                    profile.avcDecoders.add(name);
                    boolean software = isSoftwareCodec(name);
                    if (!software && !profile.hasHardwareAvcDecoder) {
                        profile.hasHardwareAvcDecoder = true;
                        profile.preferredAvcDecoder = name;
                    } else if (profile.preferredAvcDecoder == null) {
                        profile.preferredAvcDecoder = name;
                    }
                }
            }
        } catch (RuntimeException error) {
            profile.warnings.add("读取编解码器失败: " + error.getClass().getSimpleName());
        }
    }

    private static boolean isSoftwareCodec(String codecName) {
        String name = codecName.toLowerCase(Locale.US);
        return name.startsWith("omx.google.") || name.contains("ffmpeg") || name.contains("software");
    }

    private static String safe(String value) {
        return value == null || value.length() == 0 ? "unknown" : value;
    }
}
