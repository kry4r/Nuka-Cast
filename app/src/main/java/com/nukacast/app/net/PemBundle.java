package com.nukacast.app.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads every certificate out of a PEM file, whatever else the file contains.
 *
 * <p>Measured on Android 4.4: {@code CertificateFactory.generateCertificates()} answers a Mozilla
 * bundle (the format curl's {@code caextract} produces, with {@code ##} headers and a name/underline
 * line before each certificate) with an <em>empty</em> collection and no error at all. The app then
 * believed it carried 121 roots while holding three, and hosts like {@code api.github.com} failed with
 * "Trust anchor for certification path not found" for a root that was right there in the file.
 *
 * <p>So the markers are found by hand and each block is handed to the platform one at a time, which
 * cannot be quietly skipped.
 */
public final class PemBundle {

    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private PemBundle() {
    }

    /** Every certificate in the stream, in file order. Malformed blocks are skipped, not fatal. */
    public static List<X509Certificate> read(InputStream input) throws IOException {
        List<X509Certificate> certificates = new ArrayList<X509Certificate>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (Exception unsupported) {
            throw new IOException("系统不支持 X.509 证书", unsupported);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "US-ASCII"));
        StringBuilder block = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (block == null) {
                if (BEGIN.equals(trimmed)) block = new StringBuilder();
                continue;
            }
            if (END.equals(trimmed)) {
                X509Certificate certificate = parse(factory, block.toString());
                if (certificate != null) certificates.add(certificate);
                block = null;
                continue;
            }
            // Some bundles wrap the base64; the concatenation of the lines is the DER payload.
            block.append(trimmed);
        }
        return certificates;
    }

    private static X509Certificate parse(CertificateFactory factory, String base64) {
        try {
            byte[] der = decode(base64);
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Base64 to bytes, written out because the platform's decoders differ: {@code java.util.Base64}
     * arrived in API 26 and {@code android.util.Base64} cannot be used off-device, which would leave the
     * parsing untestable.
     */
    static byte[] decode(String base64) {
        int[] values = new int[256];
        for (int i = 0; i < values.length; i++) values[i] = -1;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) values[alphabet.charAt(i)] = i;
        byte[] out = new byte[base64.length() * 3 / 4 + 3];
        int length = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < base64.length(); i++) {
            char character = base64.charAt(i);
            int value = character < 256 ? values[character] : -1;
            if (value < 0) continue;
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[length++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        byte[] result = new byte[length];
        System.arraycopy(out, 0, result, 0, length);
        return result;
    }
}
