package com.nukacast.app.spider;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public final class JarTrustStoreApi19Test {
    @Test
    public void deletingASourceCanForgetItsFirstUseJarFingerprint() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String url = "http://example.test/" + System.nanoTime() + "/spider.jar";
        JarTrustStore store = new JarTrustStore(context);

        assertEquals(JarTrustStore.Verdict.FIRST_USE_TRUSTED, store.verify(url, "first"));
        assertEquals(JarTrustStore.Verdict.CHANGED, store.verify(url, "second"));
        store.forget(url);
        assertEquals(JarTrustStore.Verdict.FIRST_USE_TRUSTED, store.verify(url, "second"));
    }
}
