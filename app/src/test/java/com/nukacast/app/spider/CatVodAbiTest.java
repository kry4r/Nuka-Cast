package com.nukacast.app.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.net.OkDns;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CatVodAbiTest {
    @Test
    public void spiderMatchesTvBoxHostAbi() throws Exception {
        assertField("empty", JSONObject.class, true);
        assertField("siteKey", String.class, false);
        Field context = Spider.class.getDeclaredField("mContext");
        assertTrue(Modifier.isStatic(context.getModifiers()));
        assertTrue(Modifier.isProtected(context.getModifiers()));

        assertMethod("init", void.class, false, Context.class);
        assertMethod("init", void.class, false, Context.class, String.class);
        assertMethod("initApi", void.class, false, SpiderApi.class);
        assertMethod("categoryContent", String.class, false, String.class, String.class,
                boolean.class, HashMap.class);
        assertMethod("playerContent", String.class, false, String.class, String.class, List.class);
        assertMethod("liveContent", String.class, false, String.class);
        assertMethod("safeDns", Dns.class, true);
        assertMethod("client", OkHttpClient.class, true);
        assertMethod("cancelByTag", void.class, false);
        assertMethod("proxyLocal", Object[].class, false, Map.class);
        assertMethod("proxy", Object[].class, false, Map.class);
        assertMethod("action", String.class, false, String.class);
    }

    @Test
    public void spiderApiAndNetworkShimExposeExpectedDescriptors() throws Exception {
        assertEquals(String.class,
                SpiderApi.class.getMethod("getAddress", boolean.class).getReturnType());
        assertEquals(String.class, SpiderApi.class.getMethod("getPort").getReturnType());
        assertEquals(String.class,
                SpiderApi.class.getMethod("multiReq", com.google.gson.JsonArray.class).getReturnType());
        assertEquals(String.class,
                SpiderApi.class.getMethod("webParse", String.class, String.class).getReturnType());
        assertEquals(OkDns.class,
                com.github.catvod.net.OkHttp.class.getMethod("dns").getReturnType());
        assertTrue(Modifier.isStatic(
                com.github.catvod.net.OkHttp.class.getMethod("client").getModifiers()));
    }

    private static void assertField(String name, Class<?> type, boolean isStatic) throws Exception {
        Field field = Spider.class.getField(name);
        assertEquals(type, field.getType());
        assertEquals(isStatic, Modifier.isStatic(field.getModifiers()));
    }

    private static void assertMethod(String name, Class<?> result, boolean isStatic,
                                     Class<?>... parameters) throws Exception {
        Method method = Spider.class.getMethod(name, parameters);
        assertEquals(result, method.getReturnType());
        assertEquals(isStatic, Modifier.isStatic(method.getModifiers()));
    }
}
