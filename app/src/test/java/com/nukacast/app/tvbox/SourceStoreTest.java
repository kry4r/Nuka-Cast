package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.ConfigSource;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SourceStoreTest {
    @Test
    public void removesOnlyLegacyBuiltInTreeAndKeepsUserSources() {
        ConfigSource builtIn = source("built-in", "Old built in", SourceStore.REMOVED_BUILT_IN_URL);
        builtIn.kind = ConfigSource.KIND_WAREHOUSE;
        ConfigSource child = source("child", "Child", "https://example.com/child.json");
        child.parentId = builtIn.id;
        ConfigSource user = source("user", "User", "https://example.com/user.json");

        List<ConfigSource> result = SourceStore.removeBuiltInTree(
                Arrays.asList(builtIn, child, user), SourceStore.REMOVED_BUILT_IN_URL);

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).id);
    }

    @Test
    public void mergesWarehouseChildrenByResolvedUrlAndPreservesExistingIds() {
        ConfigSource parent = source("parent", "Warehouse", "http://example.com/index/dc.json");
        parent.kind = ConfigSource.KIND_WAREHOUSE;
        ConfigSource existing = source("child-a", "Old name", "http://example.com/index/a.json");
        existing.parentId = parent.id;
        ConfigSource stale = source("stale", "Stale", "http://example.com/stale.json");
        stale.parentId = parent.id;
        ConfigSource unrelated = source("other", "Other", "https://other.example/tv");

        List<ConfigSource> merged = SourceStore.mergeChildren(
                Arrays.asList(parent, existing, stale, unrelated), parent,
                Arrays.asList(
                        new ConfigDecoder.WarehouseEntry("Renamed", "./a.json"),
                        new ConfigDecoder.WarehouseEntry("Duplicate", "./a.json"),
                        new ConfigDecoder.WarehouseEntry("Second", "https://two.example/tv")));

        assertEquals(4, merged.size());
        assertEquals("child-a", findByUrl(merged, "http://example.com/index/a.json").id);
        assertEquals("Renamed", findByUrl(merged, "http://example.com/index/a.json").name);
        assertEquals(parent.id, findByUrl(merged, "https://two.example/tv").parentId);
        assertTrue(findById(merged, "stale") == null);
        assertEquals("other", findById(merged, "other").id);
    }

    @Test
    public void removingWarehouseAlsoRemovesItsChildren() {
        ConfigSource parent = source("parent", "Warehouse", "https://example.com/dc");
        parent.kind = ConfigSource.KIND_WAREHOUSE;
        ConfigSource child = source("child", "Child", "https://example.com/child");
        child.parentId = parent.id;
        ConfigSource other = source("other", "Other", "https://other.example/tv");

        List<ConfigSource> remaining = SourceStore.removeTree(
                Arrays.asList(parent, child, other), parent.id);

        assertEquals(1, remaining.size());
        assertEquals("other", remaining.get(0).id);
    }

    private static ConfigSource source(String id, String name, String url) {
        ConfigSource source = new ConfigSource(name, url);
        source.id = id;
        return source;
    }

    private static ConfigSource findById(List<ConfigSource> sources, String id) {
        for (ConfigSource source : sources) if (id.equals(source.id)) return source;
        return null;
    }

    private static ConfigSource findByUrl(List<ConfigSource> sources, String url) {
        for (ConfigSource source : sources) if (url.equals(source.url)) return source;
        return null;
    }
}
