package com.mk.androidtransfer.license;

/** Verification failures are kept machine-readable so UI can present the right recovery action. */
public final class LicenseException extends Exception {
    public enum Reason {
        MALFORMED,
        BAD_SIGNATURE,
        WRONG_PRODUCT,
        DEVICE_MISMATCH,
        EXPIRED,
        STALE
    }

    private final Reason reason;
    private final License license;

    public LicenseException(Reason reason) {
        this(reason, null);
    }

    public LicenseException(Reason reason, License license) {
        super(reason.name());
        this.reason = reason;
        this.license = license;
    }

    public Reason getReason() {
        return reason;
    }

    public License getLicense() {
        return license;
    }
}
