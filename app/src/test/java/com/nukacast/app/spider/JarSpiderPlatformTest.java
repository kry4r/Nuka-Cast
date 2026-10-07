package com.nukacast.app.spider;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JAR spiders are refused below Android 5.0.
 *
 * <p>On the affected TV, a config whose sites are all JAR spiders made the app load DexClassLoader
 * plugins until the process was killed about 50 seconds after launch — no Java exception, no log.
 * Dalvik also cannot verify those JARs, so refusing them is both the crash fix and the honest answer.
 */
public class JarSpiderPlatformTest {
    @Test
    public void refusedOnApi19AndAllowedOnApi21AndUp() {
        // Build.VERSION.SDK_INT is 0 in a plain JVM unit test, which is below the threshold.
        assertFalse(SpiderManager.jarSpidersSupported());
        assertTrue(SpiderManager.jarSpidersSupportedFor(21));
        assertTrue(SpiderManager.jarSpidersSupportedFor(30));
        assertFalse(SpiderManager.jarSpidersSupportedFor(20));
        assertFalse(SpiderManager.jarSpidersSupportedFor(19));
    }
}
