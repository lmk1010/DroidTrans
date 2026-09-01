package com.mk.androidtransfer.license;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class LicenseVerifierTest {
    private static final long ISSUED_MS = 1788185371832L;
    private static final long EXPIRES_MS = 1819707671552L;

    @Test
    public void acceptsSharedCrossPlatformVector() throws Exception {
        License license = LicenseVerifier.verify(vector(), ISSUED_MS + 24 * 60 * 60 * 1000L);

        assertEquals(2, license.getVersion());
        assertEquals("droidtrans-pro", license.getProduct());
        assertEquals("year", license.getPlan());
        assertEquals("DT-V5TQ-HP5E-MHZW", license.getCode());
        assertEquals("e2e-claim2@droidtrans.test", license.getEmail());
        assertEquals("1.99.99", license.getMaxVersion());
        assertFalse(license.isLifetime());
        assertEquals(Long.valueOf(ISSUED_MS), license.issuedAtMillis());
        assertEquals(Long.valueOf(EXPIRES_MS), license.expiresAtMillis());
    }

    @Test
    public void rejectsTamperedBody() throws Exception {
        String token = vector();
        String tampered = (token.charAt(1) == 'A' ? "B" : "A") + token.substring(1);

        LicenseException error = assertThrows(
                LicenseException.class,
                () -> LicenseVerifier.verify(tampered, ISSUED_MS));
        assertEquals(LicenseException.Reason.BAD_SIGNATURE, error.getReason());
    }

    @Test
    public void rejectsMalformedToken() {
        LicenseException error = assertThrows(
                LicenseException.class,
                () -> LicenseVerifier.verify("not-a-license", ISSUED_MS));
        assertEquals(LicenseException.Reason.MALFORMED, error.getReason());
    }

    @Test
    public void marksOldSignedTokenStale() throws Exception {
        LicenseException error = assertThrows(
                LicenseException.class,
                () -> LicenseVerifier.verify(
                        vector(), ISSUED_MS + License.STALE_AFTER_MS + 1));
        assertEquals(LicenseException.Reason.STALE, error.getReason());
        assertNotNull(error.getLicense());
    }

    @Test
    public void expiryTakesPriorityOverStaleness() throws Exception {
        LicenseException error = assertThrows(
                LicenseException.class,
                () -> LicenseVerifier.verify(vector(), EXPIRES_MS + 1));
        assertEquals(LicenseException.Reason.EXPIRED, error.getReason());
        assertNotNull(error.getLicense());
    }

    @Test
    public void normalizesHumanActivationCodes() {
        assertEquals("DT-01A2-3456-789B",
                LicenseApi.normalizeCode("dt oia2 3456 789b"));
        assertTrue(LicenseApi.normalizeCode("too short").isEmpty());
    }

    @Test
    public void enforcesNewDeviceBindingsButAcceptsLegacyLicenses() throws Exception {
        License bound = new Gson().fromJson(
                "{\"deviceId\":\"device-a\"}", License.class);
        assertTrue(LicenseVerifier.deviceMatches(bound, "device-a"));
        assertFalse(LicenseVerifier.deviceMatches(bound, "device-b"));

        License legacy = LicenseVerifier.verify(
                vector(), ISSUED_MS + 24 * 60 * 60 * 1000L, "device-b");
        assertTrue(legacy.getDeviceId().isEmpty());
    }

    private String vector() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("license_vector.txt");
        assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.US_ASCII))) {
            return reader.readLine().trim();
        }
    }
}
