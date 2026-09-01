package com.mk.androidtransfer.license;

import android.content.Context;
import android.content.SharedPreferences;

/** Private on-device storage plus automatic 14-day renewal. */
public final class LicenseStore {
    private static final String PREFS = "droidtrans_license";
    private static final String TOKEN_KEY = "license_token";
    private static volatile LicenseStore instance;

    private final SharedPreferences preferences;
    private License license;
    private LicenseException problem;
    private boolean refreshing;

    private LicenseStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        reload();
    }

    public static LicenseStore get(Context context) {
        if (instance == null) {
            synchronized (LicenseStore.class) {
                if (instance == null) {
                    instance = new LicenseStore(context);
                }
            }
        }
        return instance;
    }

    public synchronized void reload() {
        String token = preferences.getString(TOKEN_KEY, null);
        if (token == null || token.isEmpty()) {
            license = null;
            problem = null;
            return;
        }
        try {
            license = LicenseVerifier.verify(token);
            problem = null;
        } catch (LicenseException e) {
            problem = e;
            license = e.getLicense();
            if (e.getReason() != LicenseException.Reason.EXPIRED
                    && e.getReason() != LicenseException.Reason.STALE) {
                license = null;
            }
        }
    }

    public synchronized License save(String token) throws LicenseException {
        License verified = LicenseVerifier.verify(token);
        preferences.edit().putString(TOKEN_KEY, token).apply();
        license = verified;
        problem = null;
        return verified;
    }

    public synchronized void remove() {
        preferences.edit().remove(TOKEN_KEY).apply();
        license = null;
        problem = null;
    }

    public synchronized boolean isPro() {
        return license != null && problem == null;
    }

    public synchronized License getLicense() {
        return license;
    }

    public synchronized LicenseException getProblem() {
        return problem;
    }

    public synchronized String getToken() {
        return preferences.getString(TOKEN_KEY, null);
    }

    /** Best-effort startup refresh. A network failure never removes a still-valid offline license. */
    public void refreshIfNeeded() {
        final String token;
        synchronized (this) {
            if (refreshing || license == null
                    || (!license.needsRefresh(System.currentTimeMillis())
                    && (problem == null
                    || problem.getReason() != LicenseException.Reason.STALE))) {
                return;
            }
            token = getToken();
            if (token == null) return;
            refreshing = true;
        }
        LicenseApi.refresh(token, new LicenseApi.ResultCallback() {
            @Override
            public void onSuccess(String freshToken) {
                try {
                    save(freshToken);
                } catch (LicenseException ignored) {
                    // Keep the previous signed token; a bad server response must not destroy it.
                } finally {
                    synchronized (LicenseStore.this) {
                        refreshing = false;
                    }
                }
            }

            @Override
            public void onFailure(LicenseApi.ApiException error) {
                synchronized (LicenseStore.this) {
                    refreshing = false;
                }
            }
        });
    }
}
