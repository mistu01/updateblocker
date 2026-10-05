package io.github.updateblocker.hook;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemServerHook {
    private static final String TAG = "[UpdateBlocker-SystemServer]";
    private static final String PLAY_STORE_PKG = "com.android.vending";
    private static final int SPOOFED_VERSION_CODE = 999999999;
    private static final long SPOOFED_LONG_VERSION_CODE = 999999999999L;
    private static final String SPOOFED_VERSION_NAME = "999.999.999";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " Injecting hooks into System Framework");

        // 1. Hook PackageManagerService to spoof queries originating from Google Play Store
        hookPackageManagerService(lpparam.classLoader);

        // 2. Hook PackageInstallerSession to reject installations from Google Play Store
        hookPackageInstallerSession(lpparam.classLoader);
    }

    private static void hookPackageManagerService(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = XposedHelpers.findClassIfExists("com.android.server.pm.PackageManagerService", classLoader);
            if (pmsClass == null) return;

            for (Method method : pmsClass.getDeclaredMethods()) {
                String name = method.getName();

                if ("getPackageInfo".equals(name) || "getPackageInfoInternal".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isCallerPlayStore(param.thisObject)) return;

                            String pkgName = extractPackageName(param.args);
                            if (pkgName != null && PreferencesBridge.isBlocked(pkgName, null)) {
                                PackageInfo info = (PackageInfo) param.getResult();
                                if (info != null) {
                                    spoofPackageInfo(info);
                                    param.setResult(info);
                                    XposedBridge.log(TAG + " Spoofed PackageInfo for Play Store caller: " + pkgName);
                                }
                            }
                        }
                    });
                } else if ("getInstalledPackages".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isCallerPlayStore(param.thisObject)) return;
                            handleInstalledPackagesResult(param.getResult());
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Note: PackageManagerService hook error: " + t.getMessage());
        }
    }

    private static boolean isCallerPlayStore(Object pmsInstance) {
        try {
            int callingUid = Binder.getCallingUid();
            if (callingUid < 10000) return false; // System / root UIDs

            String[] packages = (String[]) XposedHelpers.callMethod(pmsInstance, "getPackagesForUid", callingUid);
            if (packages != null) {
                for (String pkg : packages) {
                    if (PLAY_STORE_PKG.equals(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String extractPackageName(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return null;
        if (args[0] instanceof String) {
            return (String) args[0];
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void handleInstalledPackagesResult(Object result) {
        if (result == null) return;
        List<PackageInfo> list = null;

        if (result instanceof List) {
            list = (List<PackageInfo>) result;
        } else if (result.getClass().getName().contains("ParceledListSlice")) {
            try {
                list = (List<PackageInfo>) XposedHelpers.callMethod(result, "getList");
            } catch (Throwable ignored) {
            }
        }

        if (list != null) {
            for (PackageInfo info : list) {
                if (info != null && PreferencesBridge.isBlocked(info.packageName, null)) {
                    spoofPackageInfo(info);
                }
            }
        }
    }

    private static void spoofPackageInfo(PackageInfo info) {
        info.versionCode = SPOOFED_VERSION_CODE;
        info.versionName = SPOOFED_VERSION_NAME;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                info.setLongVersionCode(SPOOFED_LONG_VERSION_CODE);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookPackageInstallerSession(ClassLoader classLoader) {
        try {
            Class<?> sessionClass = XposedHelpers.findClass("com.android.server.pm.PackageInstallerSession", classLoader);
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

                            if (PLAY_STORE_PKG.equals(installerPackage) && targetPackage != null) {
                                if (PreferencesBridge.isBlocked(targetPackage, null)) {
                                    XposedBridge.log(TAG + " Blocked system install of " + targetPackage + " from " + installerPackage);
                                    throw new SecurityException("UpdateBlocker: Update blocked by user for " + targetPackage);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Note: validateInstallLocked hook error: " + t.getMessage());
        }
    }
}
