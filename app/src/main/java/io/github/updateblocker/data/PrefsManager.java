package io.github.updateblocker.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PrefsManager {
    public static final String PREF_NAME = "update_blocker_prefs";
    public static final String KEY_BLOCKED_PACKAGES = "blocked_packages";

    private static PrefsManager instance;
    private final SharedPreferences prefs;

    private PrefsManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        fixWorldReadable(context);
    }

    public static synchronized PrefsManager getInstance(Context context) {
        if (instance == null) {
            instance = new PrefsManager(context);
        }
        return instance;
    }

    public Set<String> getBlockedPackages() {
        Set<String> set = prefs.getStringSet(KEY_BLOCKED_PACKAGES, Collections.emptySet());
        return new HashSet<>(set);
    }

    public boolean isPackageBlocked(String packageName) {
        if (packageName == null) return false;
        Set<String> set = prefs.getStringSet(KEY_BLOCKED_PACKAGES, Collections.emptySet());
        return set.contains(packageName);
    }

    public boolean setPackageBlocked(Context context, String packageName, boolean blocked) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        Set<String> current = new HashSet<>(prefs.getStringSet(KEY_BLOCKED_PACKAGES, Collections.emptySet()));
        boolean changed;
        if (blocked) {
            changed = current.add(packageName.trim());
        } else {
            changed = current.remove(packageName.trim());
        }

        if (changed) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, current).commit();
            fixWorldReadable(context);
        }
        return changed;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void fixWorldReadable(Context context) {
        try {
            File dataDir = context.getDataDir();
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefFile = new File(prefsDir, PREF_NAME + ".xml");

            if (dataDir.exists()) {
                dataDir.setReadable(true, false);
                dataDir.setExecutable(true, false);
            }
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false);
                prefsDir.setExecutable(true, false);
            }
            if (prefFile.exists()) {
                prefFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
