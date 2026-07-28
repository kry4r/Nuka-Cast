package com.nukacast.app.tvbox;

import com.nukacast.app.tvbox.model.ConfigSource;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SourceStoreTest {
    @Test
    public void unmigratedEmptyInstallRestoresDefault() {
        List<ConfigSource> empty = new ArrayList<ConfigSource>();
        List<ConfigSource> populated = new ArrayList<ConfigSource>();
        populated.add(new ConfigSource("自定义", "https://example.com/tvbox.json"));

        assertTrue(SourceStore.shouldRestoreDefault(false, empty));
        assertFalse(SourceStore.shouldRestoreDefault(false, populated));
    }

    @Test
    public void migratedEmptyListIsPreservedAsUserChoice() {
        assertFalse(SourceStore.shouldRestoreDefault(true, new ArrayList<ConfigSource>()));
    }
}
