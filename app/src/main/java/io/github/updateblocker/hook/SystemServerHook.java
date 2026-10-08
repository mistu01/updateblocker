package io.github.updateblocker.hook;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * System Server hooks — runs in the 'android' (system_server) process.
 *
 * Intercepts PackageManagerService calls originating from Google Play Store's UID,
 * and removes blocked packages from the response. This means Play Store never
 * learns that blocked apps are installed, so it never schedules a download.
 *
 * Also blocks PackageInstallerSession.validateInstallLocked to prevent Play Store
 * from completing an install if one somehow got started.
 */
public class SystemServerHook {
    private static final String TAG = "[UpdateBlocker-SystemServer]";
    private static final String PLAY_STORE_PKG = "com.android.vending";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " Injecting hooks into System Framework");

        // Hook PackageManagerService to filter/hide blocked packages from Play Store queries
        hookPackageManagerService(lpparam.classLoader);

        // Hook PackageInstallerSession to reject Play Store installations of blocked apps
        hookPackageInstallerSession(lpparam.classLoader);
    }

    private static void hookPackageManagerService(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = XposedHelpers.findClassIfExists(
                    "com.android.server.pm.PackageManagerService", classLoader);
            if (pmsClass == null) {
                XposedBridge.log(TAG + " PackageManagerService class not found, skipping");
                return;
            }

            for (Method method : pmsClass.getDeclaredMethods()) {
                String name = method.getName();

                if ("getPackageInfo".equals(name) || "getPackageInfoInternal".equals(name)
                        || "getPackageInfoWithComponents".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isCallerPlayStore(param.thisObject)) return;

                            String pkgName = extractPackageName(param.args);
                            if (pkgName != null && PreferencesBridge.isBlocked(pkgName, null)) {
                                // Return null — Play Store treats this as "app not installed"
                                param.setResult(null);
                                XposedBridge.log(TAG + " Hid package from Play Store (system_server): " + pkgName);
                            }
                        }
                    });
                } else if ("getInstalledPackages".equals(name) || "getInstalledPackagesAsUser".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isCallerPlayStore(param.thisObject)) return;
                            filterInstalledPackages(param);
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " PackageManagerService hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " PackageManagerService hook error: " + t.getMessage());
        }
    }

    /**
     * Check if the current Binder caller is the Google Play Store.
     * Must be called from within a Binder-dispatched method (which PMS methods are).
     */
    private static boolean isCallerPlayStore(Object pmsInstance) {
        try {
            int callingUid = Binder.getCallingUid();
            if (callingUid < 10000) return false; // System / root UIDs — skip

            String[] packages = (String[]) XposedHelpers.callMethod(
                    pmsInstance, "getPackagesForUid", callingUid);
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
        if (args[0] instanceof String) return (String) args[0];
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void filterInstalledPackages(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (result == null) return;

        List<PackageInfo> list = null;
        boolean isParceledSlice = false;

        if (result instanceof List) {
            list = (List<PackageInfo>) result;
        } else if (result.getClass().getName().contains("ParceledListSlice")) {
            try {
                list = (List<PackageInfo>) XposedHelpers.callMethod(result, "getList");
                isParceledSlice = true;
            } catch (Throwable ignored) {}
        }

        if (list == null) return;

        // Check if any blocked packages are present
        boolean hasBlocked = false;
        for (PackageInfo info : list) {
            if (info != null && PreferencesBridge.isBlocked(info.packageName, null)) {
                hasBlocked = true;
                break;
            }
        }
        if (!hasBlocked) return;

        // Build a filtered list without blocked packages
        List<PackageInfo> filtered = new ArrayList<>();
        for (PackageInfo info : list) {
            if (info == null || !PreferencesBridge.isBlocked(info.packageName, null)) {
                filtered.add(info);
            } else {
                XposedBridge.log(TAG + " Removed blocked package from system list (Play Store caller): " + info.packageName);
            }
        }

        if (!isParceledSlice) {
            param.setResult(filtered);
        } else {
            // Try to reconstruct the ParceledListSlice with filtered contents
            try {
                Object newSlice = XposedHelpers.newInstance(result.getClass(), filtered);
                param.setResult(newSlice);
            } catch (Throwable t) {
                // Fallback: set the internal list field directly
                try {
                    XposedHelpers.setObjectField(result, "mList", filtered);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void hookPackageInstallerSession(ClassLoader classLoader) {
        try {
            Class<?> sessionClass = XposedHelpers.findClass(
                    "com.android.server.pm.PackageInstallerSession", classLoader);
            XposedHelpers.findAndHookMethod(
                    sessionClass,
                    "validateInstallLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String installerPackage = null;
                            String targetPackage = null;

                            try {
                                installerPackage = (String) XposedHelpers.getObjectField(
                                        param.thisObject, "mInstallerPackageName");
                            } catch (Throwable ignored) {}

                            try {
                                targetPackage = (String) XposedHelpers.getObjectField(
                                        param.thisObject, "mPackageName");
                            } catch (Throwable ignored) {}

                            if (PLAY_STORE_PKG.equals(installerPackage) && targetPackage != null) {
                                if (PreferencesBridge.isBlocked(targetPackage, null)) {
                                    XposedBridge.log(TAG + " Blocked system install of "
                                            + targetPackage + " from " + installerPackage);
                                    throw new SecurityException(
                                            "UpdateBlocker: Update blocked by user for " + targetPackage);
                                }
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " validateInstallLocked hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " validateInstallLocked hook error: " + t.getMessage());
        }
    }
}
