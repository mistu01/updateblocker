package io.github.updateblocker.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class PreferencesBridge {
    public static final String MODULE_PACKAGE = "io.github.updateblocker";
    public static final String PREF_NAME = "update_blocker_prefs";
    public static final String KEY_BLOCKED_PACKAGES = "blocked_packages";
    public static final Uri PROVIDER_URI = Uri.parse("content://io.github.updateblocker.provider");

    private static XSharedPreferences xSharedPrefs;
    private static final Set<String> cachedBlockedPackages = Collections.synchronizedSet(new HashSet<>());
    private static long lastCacheUpdate = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 seconds cache

    public static void initZygote() {
        try {
            xSharedPrefs = new XSharedPreferences(MODULE_PACKAGE, PREF_NAME);
            xSharedPrefs.makeWorldReadable();
            reloadFromXPrefs();
        } catch (Throwable t) {
            XposedBridge.log("[UpdateBlocker] Error initializing XSharedPreferences: " + t.getMessage());
        }
    }

    private static synchronized void reloadFromXPrefs() {
        if (xSharedPrefs != null) {
            try {
                xSharedPrefs.reload();
                Set<String> set = xSharedPrefs.getStringSet(KEY_BLOCKED_PACKAGES, null);
                if (set != null) {
                    cachedBlockedPackages.clear();
                    cachedBlockedPackages.addAll(set);
                    lastCacheUpdate = System.currentTimeMillis();
                }
            } catch (Throwable t) {
                // Ignore reload error
            }
        }
    }

    public static synchronized void updateFromContext(Context context) {
        if (context == null) return;
        try {
            Bundle result = context.getContentResolver().call(
                    PROVIDER_URI,
                    "getBlockedList",
                    null,
                    null
            );
            if (result != null) {
                ArrayList<String> list = result.getStringArrayList("package_list");
                if (list != null) {
                    cachedBlockedPackages.clear();
                    cachedBlockedPackages.addAll(list);
                    lastCacheUpdate = System.currentTimeMillis();
                }
            }
        } catch (Throwable t) {
            // ContentProvider query failed, fallback to XSharedPreferences
            reloadFromXPrefs();
        }
    }

    public static boolean isBlocked(String packageName, Context context) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }

        // Cache expiration check
        long now = System.currentTimeMillis();
        if (now - lastCacheUpdate > CACHE_TTL_MS) {
            if (context != null) {
                updateFromContext(context);
            } else {
                reloadFromXPrefs();
            }
        }

        return cachedBlockedPackages.contains(packageName);
    }

    public static Set<String> getBlockedPackages(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastCacheUpdate > CACHE_TTL_MS) {
            if (context != null) {
                updateFromContext(context);
            } else {
                reloadFromXPrefs();
            }
        }
        return new HashSet<>(cachedBlockedPackages);
    }
}
