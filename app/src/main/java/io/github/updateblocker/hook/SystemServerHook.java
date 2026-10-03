package io.github.updateblocker.hook;

import android.content.pm.PackageInstaller;

import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemServerHook {
    private static final String TAG = "[UpdateBlocker-SystemServer]";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " Injecting hooks into System Framework");

        try {
            Class<?> sessionClass = XposedHelpers.findClass("com.android.server.pm.PackageInstallerSession", lpparam.classLoader);

            // Hook install / validate in PackageInstallerSession
            XposedHelpers.findAndHookMethod(
                    sessionClass,
                    "validateInstallLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String installerPackage = null;
                            String targetPackage = null;

                            try {
                                installerPackage = (String) XposedHelpers.getObjectField(param.thisObject, "mInstallerPackageName");
                            } catch (Throwable ignored) {
                            }

                            try {
                                targetPackage = (String) XposedHelpers.getObjectField(param.thisObject, "mPackageName");
                            } catch (Throwable ignored) {
                            }

                            if ("com.android.vending".equals(installerPackage) && targetPackage != null) {
                                if (PreferencesBridge.isBlocked(targetPackage, null)) {
                                    XposedBridge.log(TAG + " Blocked system install of " + targetPackage + " from " + installerPackage);
                                    throw new SecurityException("UpdateBlocker: Update blocked by user for " + targetPackage);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Note: validateInstallLocked hook not available: " + t.getMessage());
        }
    }
}
