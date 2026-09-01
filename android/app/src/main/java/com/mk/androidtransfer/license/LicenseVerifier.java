package com.mk.androidtransfer.license;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;

/** Verifies the shared base64url(JSON).base64url(Ed25519 signature) license format. */
public final class LicenseVerifier {
    private static final String PRODUCT = "droidtrans-pro";
    private static final byte[] PUBLIC_KEY = decodeBase64Url(
            "1DoaCefyZVtNEp7mzFstWOPezLYPU6LPuUkIv1R5hqo");

    private LicenseVerifier() {
    }

    public static License verify(String token) throws LicenseException {
        return verify(token, System.currentTimeMillis());
    }

    static License verify(String token, long nowMillis) throws LicenseException {
        if (token == null) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }

        String body = token.substring(0, dot);
        byte[] signature;
        byte[] json;
        try {
            signature = decodeBase64Url(token.substring(dot + 1));
            json = decodeBase64Url(body);
        } catch (IllegalArgumentException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }
        if (signature.length != 64) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }

        try {
            EdDSAParameterSpec spec =
                    EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
            EdDSAPublicKey key = new EdDSAPublicKey(new EdDSAPublicKeySpec(PUBLIC_KEY, spec));
            Signature verifier =
                    new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
            verifier.initVerify(key);
            verifier.update(body.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE);
            }
        } catch (LicenseException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE);
        }

        final License license;
        try {
            license = new Gson().fromJson(
                    new String(json, StandardCharsets.UTF_8), License.class);
        } catch (JsonParseException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }
        if (license == null) {
            throw new LicenseException(LicenseException.Reason.MALFORMED);
        }
        if (license.getVersion() != 2 || !PRODUCT.equals(license.getProduct())) {
            throw new LicenseException(LicenseException.Reason.WRONG_PRODUCT);
        }
        if (license.isExpired(nowMillis)) {
            throw new LicenseException(LicenseException.Reason.EXPIRED, license);
        }
        if (license.isStale(nowMillis)) {
            throw new LicenseException(LicenseException.Reason.STALE, license);
        }
        return license;
    }

    /**
     * Small API-24-safe base64url decoder. java.util.Base64 is only available from API 26.
     */
    static byte[] decodeBase64Url(String input) {
        if (input == null || input.isEmpty() || input.length() % 4 == 1) {
            throw new IllegalArgumentException("bad base64url");
        }
        int outputLength = input.length() * 6 / 8;
        byte[] output = new byte[outputLength];
        int accumulator = 0;
        int bits = 0;
        int out = 0;
        for (int i = 0; i < input.length(); i++) {
            int value = base64Value(input.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("bad base64url");
            }
            accumulator = (accumulator << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (out < output.length) {
                    output[out++] = (byte) ((accumulator >> bits) & 0xff);
                }
            }
        }
        return output;
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        return -1;
    }
}
