package io.github.updateblocker.data;

import android.graphics.drawable.Drawable;

public class AppInfo {
    private final String packageName;
    private final String appName;
    private final String versionName;
    private final long versionCode;
    private final boolean isSystemApp;
    private boolean isBlocked;
    private Drawable icon;

    public AppInfo(String packageName, String appName, String versionName, long versionCode, boolean isSystemApp, boolean isBlocked) {
        this.packageName = packageName;
        this.appName = appName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.isSystemApp = isSystemApp;
        this.isBlocked = isBlocked;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public String getVersionName() {
        return versionName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public boolean isBlocked() {
        return isBlocked;
    }

    public void setBlocked(boolean blocked) {
        isBlocked = blocked;
    }

    public Drawable getIcon() {
        return icon;
    }

    public void setIcon(Drawable icon) {
        this.icon = icon;
    }
}
