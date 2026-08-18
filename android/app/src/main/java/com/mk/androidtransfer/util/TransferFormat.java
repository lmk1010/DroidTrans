package com.mk.androidtransfer.util;

import android.content.Context;

import com.mk.androidtransfer.R;

/**
 * Shared size / speed / duration labels for transfer UI.
 */
public final class TransferFormat {

    private TransferFormat() {}

    public static String bytes(long size) {
        if (size <= 0) {
            return "";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.0f KB", size / 1024.0);
        }
        if (size < 1024L * 1024 * 1024) {
            double mb = size / (1024.0 * 1024.0);
            return String.format(mb >= 10 ? "%.0f MB" : "%.1f MB", mb);
        }
        return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond < 200) {
            return "";
        }
        return bytes(bytesPerSecond) + "/s";
    }

    public static String duration(Context context, long seconds) {
        if (seconds < 1) {
            return "";
        }
        if (seconds < 60) {
            return context.getString(R.string.seconds_unit, (int) seconds);
        }
        if (seconds < 3600) {
            int minutes = (int) (seconds / 60);
            int secs = (int) (seconds % 60);
            if (secs == 0) {
                return context.getString(R.string.minutes_unit, minutes);
            }
            return context.getString(R.string.minutes_seconds, minutes, secs);
        }
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        if (minutes == 0) {
            return context.getString(R.string.hours_unit, hours);
        }
        return context.getString(R.string.hours_minutes, hours, minutes);
    }

    public static String eta(Context context, long seconds) {
        String dur = duration(context, seconds);
        if (dur.isEmpty()) {
            return "";
        }
        return context.getString(R.string.eta_left, dur);
    }
}
