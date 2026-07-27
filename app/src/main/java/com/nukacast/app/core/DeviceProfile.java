package com.nukacast.app.core;

import java.util.ArrayList;
import java.util.List;

public final class DeviceProfile {
    public String manufacturer;
    public String model;
    public String product;
    public String androidVersion;
    public int sdk;
    public String primaryAbi;
    public long totalMemoryBytes;
    public long appMemoryBytes;
    public int displayWidth;
    public int displayHeight;
    public float refreshRate;
    public boolean hasHardwareAvcDecoder;
    public String preferredAvcDecoder;
    public final List<String> avcDecoders = new ArrayList<String>();
    public final List<String> warnings = new ArrayList<String>();

    public String displaySummary() {
        return model + " · Android " + androidVersion + " · " + primaryAbi;
    }

    public String codecSummary() {
        if (avcDecoders.isEmpty()) {
            return "未发现 H.264 解码器";
        }
        String mode = hasHardwareAvcDecoder ? "硬解" : "仅软件解码";
        return mode + " · " + preferredAvcDecoder + " · " + displayWidth + "×" + displayHeight;
    }
}
