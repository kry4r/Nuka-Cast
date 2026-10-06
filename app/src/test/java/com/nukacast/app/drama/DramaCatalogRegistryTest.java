package com.nukacast.app.drama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class DramaCatalogRegistryTest {
    @Test public void acceptsHttpsBaseUrl() {
        assertEquals("https://vote.252035.xyz",
                DramaCatalogRegistry.normalizeBaseUrl("https://vote.252035.xyz"));
    }

    @Test public void stripsTrailingSlashAndWhitespace() {
        assertEquals("https://vote.252035.xyz",
                DramaCatalogRegistry.normalizeBaseUrl("  https://vote.252035.xyz/  "));
    }

    @Test public void keepsSubPathForProxiedDeployments() {
        assertEquals("https://example.test/drama",
                DramaCatalogRegistry.normalizeBaseUrl("https://example.test/drama/"));
    }

    @Test public void rejectsMissingScheme() {
        try {
            DramaCatalogRegistry.normalizeBaseUrl("vote.252035.xyz");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("http"));
        }
    }

    @Test public void rejectsBlank() {
        try {
            DramaCatalogRegistry.normalizeBaseUrl("   ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test public void rejectsCredentialsInUrl() {
        try {
            DramaCatalogRegistry.normalizeBaseUrl("https://user:secret@example.test");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("账号"));
        }
    }
}
