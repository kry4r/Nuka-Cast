package com.nukacast.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The bundled roots are what lets an Android 4.4 box talk to hosts the 2013 platform store has never
 * heard of, and a bundle that loads nothing is indistinguishable from a host that cannot be reached.
 *
 * <p>This is the regression test for exactly that: on API 19
 * {@code CertificateFactory.generateCertificates()} answered the Mozilla-format file with an empty
 * collection and no error, so the app held three roots while believing it held 124.
 */
public class PemBundleTest {

    private static InputStream resource(String path) {
        InputStream input = PemBundleTest.class.getResourceAsStream(path);
        assertTrue("找不到资源 " + path, input != null);
        return input;
    }

    @Test
    public void theMozillaBundleLoadsEveryCertificate() throws Exception {
        List<X509Certificate> certificates =
                PemBundle.read(resource("/com/nukacast/app/net/mozilla_ca_bundle.pem"));
        // The file carries 121 certificates; anything less means blocks are being skipped again.
        assertTrue("只解析出 " + certificates.size() + " 张证书",
                certificates.size() >= 120);
        for (X509Certificate certificate : certificates) {
            assertTrue(certificate.getSubjectX500Principal().getName().length() > 0);
        }
    }

    @Test
    public void aKnownRootIsAmongThem() throws Exception {
        List<X509Certificate> certificates =
                PemBundle.read(resource("/com/nukacast/app/net/mozilla_ca_bundle.pem"));
        boolean found = false;
        for (X509Certificate certificate : certificates) {
            if (certificate.getSubjectX500Principal().getName()
                    .contains("Sectigo Public Server Authentication Root E46")) {
                found = true;
            }
        }
        // This is the root GitHub's chain needs, which is how the missing bundle was noticed.
        assertTrue("内置根证书里没有 Sectigo E46", found);
    }

    @Test
    public void commentsAndHeadersAroundTheBlocksAreIgnored() throws Exception {
        String pem = "##\n## Bundle of CA Root Certificates\n##\n\nExample Root CA\n"
                + "==================\n"
                + "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
        // A block that is not valid base64 is skipped rather than failing the whole file.
        assertEquals(0, PemBundle.read(new ByteArrayInputStream(pem.getBytes("US-ASCII"))).size());
    }

    @Test
    public void base64DecodingCoversTheAlphabetAndPadding() {
        assertEquals("hello", new String(PemBundle.decode("aGVsbG8=")));
        assertEquals("a", new String(PemBundle.decode("YQ==")));
        assertEquals("ab", new String(PemBundle.decode("YWI=")));
        assertEquals("abc", new String(PemBundle.decode("YWJj")));
        // Whitespace inside a block must not change the result.
        assertEquals("abc", new String(PemBundle.decode("YW Jj\n")));
        assertEquals(0, PemBundle.decode("====").length);
    }
}
