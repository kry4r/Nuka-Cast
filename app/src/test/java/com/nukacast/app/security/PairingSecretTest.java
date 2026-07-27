package com.nukacast.app.security;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PairingSecretTest {
    @Test
    public void generatedCodeIsAlwaysSixDigits() {
        SecureRandom random = new SecureRandom(new byte[] {1, 2, 3, 4});
        String code = PairingSecret.generateCode(random);

        assertEquals(6, code.length());
        assertTrue(code.matches("[0-9]{6}"));
    }

    @Test
    public void matchesOnlyTheCompleteCode() {
        assertTrue(PairingSecret.matches("004217", "004217"));
        assertFalse(PairingSecret.matches("004217", "4217"));
        assertFalse(PairingSecret.matches("004217", null));
        assertFalse(PairingSecret.matches("004217", "004218"));
    }
}
