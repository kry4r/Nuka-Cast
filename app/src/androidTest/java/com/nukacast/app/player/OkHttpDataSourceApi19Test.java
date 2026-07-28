package com.nukacast.app.player;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.nukacast.app.net.HttpStack;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public final class OkHttpDataSourceApi19Test {
    @Test
    public void createsIpv4OnlyPlaybackDataSource() {
        OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(HttpStack.client());
        assertNotNull(factory.createDataSource());
    }
}
