package com.nukacast.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.nukacast.app.net.HttpStack;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * API 19 evidence for the SHARP-class devices: the real activity layout, navigation targets, web
 * assets and the legacy TLS provider must load instead of failing Dalvik verification. This test
 * deliberately does not start {@code MainActivity}, because launching it would start the LAN
 * service and native receiver that the test runner isolates itself from.
 */
@RunWith(AndroidJUnit4.class)
public final class Api19UiLoadTest {
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void inflatesMainLayoutWithAllNavigationTargets() {
        View root = LayoutInflater.from(context()).inflate(R.layout.activity_main, null);
        assertNotNull(root.findViewById(R.id.navHome));
        assertNotNull(root.findViewById(R.id.navMovies));
        assertNotNull(root.findViewById(R.id.navCast));
        assertNotNull(root.findViewById(R.id.navSettings));
        assertNotNull(root.findViewById(R.id.filterDrama));
        assertNotNull(root.findViewById(R.id.moviesContent));
        assertNotNull(root.findViewById(R.id.searchResults));
        assertNotNull(root.findViewById(R.id.videoSurface));
    }

    @Test
    public void dramaAndAirPlayClassesVerifyWithoutLinkageErrors() throws Exception {
        Class.forName("com.nukacast.app.MainActivity");
        Class.forName("com.nukacast.app.drama.DramaService");
        Class.forName("com.nukacast.app.drama.VoteDramaCatalog");
        Class.forName("com.nukacast.app.airplay.AirPlayIdentity");
        Class.forName("com.nukacast.app.airplay.DecoderFallbackPolicy");
    }

    /**
     * F11 from the audit: the sniffer's WebViewClient references the API 21 WebResourceRequest type.
     * On API 19 the class must still verify; the new override is never invoked by the platform.
     */
    @Test
    public void snifferAndPlayerClassesVerifyOnApi19() throws Exception {
        Class.forName("com.nukacast.app.tvbox.SniffingActivity");
        Class.forName("com.nukacast.app.player.PlayerController");
    }

    @Test
    public void bundlesWebControlAssets() throws Exception {
        AssetManager assets = context().getAssets();
        InputStream index = assets.open("web/index.html");
        try {
            assertTrue(index.read() >= 0);
        } finally {
            index.close();
        }
    }

    /**
     * On API 19 the bundled Conscrypt stack must load: Android 4.4's platform TLS is the exact
     * fallback this app refuses to rely on. A degradation here is a product bug, not a test detail,
     * so the assertion stays strict.
     */
    @Test
    public void legacyTlsStackLoadsInsteadOfDegradingToPlatformTls() {
        String reason = HttpStack.initError();
        assertFalse("旧版 TLS 初始化失败，已回退平台 TLS：" + reason, HttpStack.degraded());
        assertNotNull(HttpStack.client());
        assertNotNull(HttpStack.client().sslSocketFactory());
    }
}
