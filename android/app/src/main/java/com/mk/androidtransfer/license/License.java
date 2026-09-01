package com.mk.androidtransfer.license;

import com.google.gson.annotations.SerializedName;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A signed DroidTrans Pro entitlement shared by macOS, Android, and iOS. */
public final class License {
    public static final long REFRESH_AFTER_MS = 14L * 24 * 60 * 60 * 1000;
    public static final long STALE_AFTER_MS = 45L * 24 * 60 * 60 * 1000;

    @SerializedName("v")
    private int version;
    private String product;
    private String plan;
    private String code;
    private String deviceId;
    private String email;
    private String issued;
    private String expires;
    private String maxVersion;

    public int getVersion() {
        return version;
    }

    public String getProduct() {
        return product;
    }

    public String getPlan() {
        return plan == null ? "" : plan;
    }

    public String getCode() {
        return code == null ? "" : code;
    }

    public String getDeviceId() {
        return deviceId == null ? "" : deviceId;
    }

    public String getEmail() {
        return email == null ? "" : email;
    }

    public String getMaxVersion() {
        return maxVersion == null ? "" : maxVersion;
    }

    public boolean isLifetime() {
        return expires == null;
    }

    public Long issuedAtMillis() {
        return parseTimestamp(issued);
    }

    public Long expiresAtMillis() {
        return parseTimestamp(expires);
    }

    public boolean isExpired(long nowMillis) {
        Long at = expiresAtMillis();
        return at != null && at < nowMillis;
    }

    public boolean isStale(long nowMillis) {
        Long at = issuedAtMillis();
        return at != null && nowMillis - at > STALE_AFTER_MS;
    }

    public boolean needsRefresh(long nowMillis) {
        Long at = issuedAtMillis();
        return at != null && nowMillis - at > REFRESH_AFTER_MS;
    }

    private static Long parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX"
        };
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                Date date = format.parse(value);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
                // Try the timestamp form without fractional seconds.
            }
        }
        return null;
    }
}
