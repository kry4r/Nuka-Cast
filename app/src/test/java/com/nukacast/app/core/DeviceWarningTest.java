package com.nukacast.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DeviceWarningTest {
    @Test
    public void explainsMinimumSdkWithoutCallingItTheTargetDevice() {
        assertEquals("应用最低支持 API 17；当前设备 API 19",
                DeviceProbe.compatibilityWarning(19));
    }
}
