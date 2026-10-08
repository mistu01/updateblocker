package io.github.updateblocker.hook;

import android.app.Application;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Build;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Play Store hook – prevents blocked apps from ever being downloaded or updated.
 *
 * Strategy (outermost to innermost):
 *
 *  Layer 1 – DownloadManager.enqueue interception:
 *      Hook the system DownloadManager.enqueue() inside Play Store's process.
 *      This is an unobfuscated system API. When Play Store tries to enqueue a download
 *      for a blocked app, we cancel it by throwing an exception or returning a dummy ID.
 *
 *  Layer 2 – getInstalledPackages / getPackageInfo – HIDE blocked packages:
 *      Instead of just spoofing the version, completely remove blocked packages from
 *      the installed packages list. Play Store then thinks these apps are not installed
 *      and will never schedule a download. This is the same strategy used by Zygisk-Detach.
 *
 *  Layer 3 – DexKit auto-update v2 batch filter (best-effort):
 *      If Play Store's internal update scheduler passes a list of packages,
 *      strip blocked packages from that list.
 *
 *  Layer 4 – PackageInstaller.createSession block (last-resort guard):
 *      Prevents any installation session even if a download somehow slipped through.
 *      Manual APK installs (from file manager / ADB) bypass this because they use
 *      a different installer package name — not com.android.vending.
 */
public class PlayStoreHook {

    private static final String TAG = "[UpdateBlocker-PlayStore]";

    // Anchor log string for the auto-update v2 batch scheduling entry point.
    private static final String AUTO_UPDATE_V2_ANCHOR =
            "UChk: document %s is not qualified for auto update v2";

    private static volatile Context sAppContext = null;

    // -------------------------------------------------------------------------

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Initializing hooks for " + lpparam.processName);

        // Capture Context early
        hookApplicationAttach(lpparam);

        // Layer 2: Hide blocked packages from PackageManager queries (no DexKit needed)
        hookIPackageManager(lpparam.classLoader);
        hookApplicationPackageManager(lpparam.classLoader);

        // Layer 1: DownloadManager.enqueue – block downloads at the OS level
        hookDownloadManager(lpparam.classLoader);

        // Layer 4: PackageInstaller last-resort guard
        hookPackageInstaller(lpparam.classLoader);
    }

    // =========================================================================
    // Application.attach – captures Context and kicks off DexKit scan
    // =========================================================================

    private static void hookApplicationAttach(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            if (ctx == null) return;
                            sAppContext = ctx;
                            PreferencesBridge.updateFromContext(ctx);

                            // Only scan in the main Play Store process
                            if (lpparam.processName.equals("com.android.vending")) {
                                installDexKitHooks(lpparam.classLoader, ctx);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook Application.attach: " + t.getMessage());
        }

        // Fallback: Application.onCreate
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sAppContext == null && param.thisObject instanceof Application) {
                                sAppContext = ((Application) param.thisObject).getApplicationContext();
                                PreferencesBridge.updateFromContext(sAppContext);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Layer 1 – DownloadManager.enqueue hook
    // =========================================================================

    private static void hookDownloadManager(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.DownloadManager",
                    classLoader,
                    "enqueue",
                    DownloadManager.Request.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            DownloadManager.Request req = (DownloadManager.Request) param.args[0];
                            if (req == null) return;

                            // Try to extract package info from the request
                            // Play Store sets the description to the app name and the title to "app name"
                            // We can also check the download URI for package patterns

                            String blockedPkg = findBlockedPackageInRequest(req);
                            if (blockedPkg != null) {
                                XposedBridge.log(TAG + " [LAYER-1] DownloadManager.enqueue BLOCKED for package: " + blockedPkg);
                                // Return -1 (failure) without performing the download
                                param.setResult(-1L);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " [LAYER-1] DownloadManager.enqueue hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-1] DownloadManager hook error: " + t.getMessage());
        }
    }

    private static String findBlockedPackageInRequest(DownloadManager.Request req) {
        if (sAppContext == null) return null;

        // Try to read internal fields from the Request object
        // These fields have been stable across Android versions
        String uri = null;
        String title = null;
        String description = null;
        String notificationPkg = null;

        try {
            Uri mUri = (Uri) XposedHelpers.getObjectField(req, "mUri");
            if (mUri != null) uri = mUri.toString();
        } catch (Throwable ignored) {}

        try {
            title = (String) XposedHelpers.getObjectField(req, "mTitle");
        } catch (Throwable ignored) {}

        try {
            description = (String) XposedHelpers.getObjectField(req, "mDescription");
        } catch (Throwable ignored) {}

        try {
            notificationPkg = (String) XposedHelpers.getObjectField(req, "mNotificationPackage");
        } catch (Throwable ignored) {}

        // Check all available strings against blocked packages
        Set<String> blocked = PreferencesBridge.getBlockedPackages(sAppContext);
        if (blocked.isEmpty()) return null;

        for (String pkg : blocked) {
            // Play Store download URLs often contain the package name
            if (uri != null && uri.contains(pkg)) return pkg;
            // Description field sometimes has the package name in older Play Store versions
            if (description != null && description.equals(pkg)) return pkg;
            // Notification package may be the app being downloaded
            if (pkg.equals(notificationPkg)) return pkg;
        }

        return null;
    }

    // =========================================================================
    // Layer 2 – Hide blocked packages from all PackageManager queries
    // =========================================================================

    private static void hookIPackageManager(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = XposedHelpers.findClass(
                    "android.content.pm.IPackageManager$Stub$Proxy", classLoader);
            for (Method method : proxyClass.getDeclaredMethods()) {
                String name = method.getName();
                if ("getPackageInfo".equals(name) || "getPackageInfoAsUser".equals(name)
                        || "getPackageInfoWithComponents".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handlePackageInfoHook(param);
                        }
                    });
                } else if ("getInstalledPackages".equals(name) || "getInstalledPackagesAsUser".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handleInstalledPackagesHook(param);
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " [LAYER-2] Hooked IPackageManager$Stub$Proxy");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-2] IPackageManager hook error: " + t.getMessage());
        }
    }

    private static void hookApplicationPackageManager(ClassLoader classLoader) {
        try {
            Class<?> appPmClass = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager", classLoader);
            for (Method method : appPmClass.getDeclaredMethods()) {
                String name = method.getName();
                if (name.startsWith("getPackageInfo")) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handlePackageInfoHook(param);
                        }
                    });
                } else if (name.startsWith("getInstalledPackages")) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handleInstalledPackagesHook(param);
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " [LAYER-2] Hooked ApplicationPackageManager");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-2] ApplicationPackageManager hook error: " + t.getMessage());
        }
    }

    /**
     * For single-package queries: if the queried package is blocked,
     * return null so Play Store thinks it's not installed.
     */
    private static void handlePackageInfoHook(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
        Object firstArg = param.args[0];
        String packageName = null;

        if (firstArg instanceof String) {
            packageName = (String) firstArg;
        } else if (firstArg instanceof VersionedPackage) {
            packageName = ((VersionedPackage) firstArg).getPackageName();
        } else {
            try {
                packageName = (String) XposedHelpers.callMethod(firstArg, "getPackageName");
            } catch (Throwable ignored) {}
        }

        if (packageName != null && PreferencesBridge.isBlocked(packageName, sAppContext)) {
            // Return null — Play Store interprets this as "app not installed"
            param.setResult(null);
            XposedBridge.log(TAG + " [LAYER-2] Hid package from Play Store query: " + packageName);
        }
    }

    /**
     * For bulk queries: remove all blocked packages from the returned list.
     * This is the same strategy as Zygisk-Detach — Play Store never sees
     * blocked apps as installed, so it never schedules downloads for them.
     */
    @SuppressWarnings("unchecked")
    private static void handleInstalledPackagesHook(XC_MethodHook.MethodHookParam param) {
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

        // Check if any blocked packages are in the list
        boolean hasBlocked = false;
        for (PackageInfo info : list) {
            if (info != null && PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                hasBlocked = true;
                break;
            }
        }
        if (!hasBlocked) return;

        // Remove blocked packages from the list entirely
        try {
            // Try to iterate and remove (may throw if list is unmodifiable)
            Iterator<PackageInfo> it = list.iterator();
            while (it.hasNext()) {
                PackageInfo info = it.next();
                if (info != null && PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                    it.remove();
                    XposedBridge.log(TAG + " [LAYER-2] Removed blocked package from installed list: " + info.packageName);
                }
            }
        } catch (UnsupportedOperationException e) {
            // List is unmodifiable — create a new filtered list and set it as result
            List<PackageInfo> filtered = new ArrayList<>();
            for (PackageInfo info : list) {
                if (info == null || !PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                    filtered.add(info);
                } else {
                    XposedBridge.log(TAG + " [LAYER-2] Filtered blocked package: " + info.packageName);
                }
            }

            if (!isParceledSlice) {
                param.setResult(filtered);
            } else {
                // Reconstruct ParceledListSlice with the filtered list
                try {
                    Object newSlice = XposedHelpers.newInstance(
                            result.getClass(), filtered);
                    param.setResult(newSlice);
                } catch (Throwable t) {
                    // Fallback: modify original in place by reflection
                    try {
                        XposedHelpers.setObjectField(result, "mList", filtered);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    // =========================================================================
    // Layer 3 – DexKit dynamic hooking (auto-update v2 batch filter)
    // =========================================================================

    private static volatile boolean sDexKitInstalled = false;

    private static synchronized void installDexKitHooks(ClassLoader classLoader, Context context) {
        if (sDexKitInstalled) return;
        sDexKitInstalled = true;

        String apkPath = context.getApplicationInfo().sourceDir;
        XposedBridge.log(TAG + " Starting DexKit scan on: " + apkPath);

        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to load DexKit native lib: " + t.getMessage());
            return;
        }

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            boolean autoUpdateHooked = hookAutoUpdateV2Batch(bridge, classLoader);
            if (!autoUpdateHooked) {
                XposedBridge.log(TAG + " [WARN] Auto-update v2 batch hook not installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " DexKit scan failed: " + t.getMessage());
        }
    }

    /**
     * Layer 3 – Find and hook Play Store's auto-update v2 batch method.
     * Strips blocked packages from the batch before any download scheduling occurs.
     */
    private static boolean hookAutoUpdateV2Batch(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            MethodDataList anchorMethods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .usingStrings(
                                                    Collections.singletonList(AUTO_UPDATE_V2_ANCHOR),
                                                    StringMatchType.Contains
                                            )
                            )
            );

            if (anchorMethods == null || anchorMethods.isEmpty()) {
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: anchor method not found");
                return false;
            }

            XposedBridge.log(TAG + " [DexKit] Auto-update v2: anchor found in " + anchorMethods.size() + " method(s)");

            Method anchorMethod = anchorMethods.get(0).getMethodInstance(classLoader);
            Class<?> anchorClass = anchorMethod.getDeclaringClass();
            XposedBridge.log(TAG + " [DexKit] Auto-update v2 class: " + anchorClass.getName());

            // Find the scheduling method in anchorClass: void method(?, int, List, ?)
            Method targetMethod = null;
            for (Method m : anchorClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (m.getReturnType() == void.class
                        && params.length >= 3
                        && params[1] == int.class
                        && List.class.isAssignableFrom(params[2])) {
                    targetMethod = m;
                    break;
                }
            }

            if (targetMethod == null) {
                // Try looser match — any method with a List parameter
                for (Method m : anchorClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (m.getReturnType() == void.class) {
                        for (Class<?> p : params) {
                            if (List.class.isAssignableFrom(p)) {
                                targetMethod = m;
                                break;
                            }
                        }
                        if (targetMethod != null) break;
                    }
                }
            }

            if (targetMethod == null) {
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: target scheduling method not found in anchor class");
                return false;
            }

            final Method scheduleMethod = targetMethod;
            final int listParamIndex = findListParamIndex(scheduleMethod);

            XposedBridge.hookMethod(scheduleMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (listParamIndex < 0 || listParamIndex >= param.args.length) return;
                    Object packagesArg = param.args[listParamIndex];
                    if (!(packagesArg instanceof List)) return;

                    List<?> packagesList = (List<?>) packagesArg;
                    boolean hasBlocked = false;
                    for (Object item : packagesList) {
                        String pkg = extractPackageName(item);
                        if (pkg != null && PreferencesBridge.isBlocked(pkg, sAppContext)) {
                            hasBlocked = true;
                            break;
                        }
                    }

                    if (!hasBlocked) return;

                    ArrayList<Object> filteredList = new ArrayList<>();
                    for (Object item : packagesList) {
                        String pkg = extractPackageName(item);
                        if (pkg == null || !PreferencesBridge.isBlocked(pkg, sAppContext)) {
                            filteredList.add(item);
                        } else {
                            XposedBridge.log(TAG + " [LAYER-3] Stripped blocked package from auto-update batch: " + pkg);
                        }
                    }

                    if (filteredList.isEmpty()) {
                        param.setResult(null);
                        XposedBridge.log(TAG + " [LAYER-3] Blocked entire auto-update batch — no downloads scheduled");
                    } else {
                        param.args[listParamIndex] = filteredList;
                        XposedBridge.log(TAG + " [LAYER-3] Modified auto-update batch, removed blocked packages");
                    }
                }
            });

            XposedBridge.log(TAG + " [DexKit] Successfully hooked AutoUpdate v2 batch method: " + scheduleMethod.getName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [DexKit] Error finding auto-update v2 batch: " + t.getMessage());
            return false;
        }
    }

    private static int findListParamIndex(Method m) {
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (List.class.isAssignableFrom(params[i])) return i;
        }
        return -1;
    }

    private static String extractPackageName(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        try {
            return (String) XposedHelpers.callMethod(obj, "getPackageName");
        } catch (Throwable ignored) {}
        try {
            return (String) XposedHelpers.getObjectField(obj, "packageName");
        } catch (Throwable ignored) {}
        try {
            return (String) XposedHelpers.getObjectField(obj, "mPackageName");
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // Layer 4 – PackageInstaller.createSession last-resort guard
    // (Only blocks Play Store installs — manual APK installs have different installer)
    // =========================================================================

    private static void hookPackageInstaller(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.pm.PackageInstaller",
                    classLoader,
                    "createSession",
                    PackageInstaller.SessionParams.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            PackageInstaller.SessionParams params =
                                    (PackageInstaller.SessionParams) param.args[0];
                            if (params == null) return;

                            String targetPkg = null;
                            try {
                                targetPkg = (String) XposedHelpers.getObjectField(params, "appPackageName");
                            } catch (Throwable ignored) {}

                            if (targetPkg != null && PreferencesBridge.isBlocked(targetPkg, sAppContext)) {
                                XposedBridge.log(TAG + " [LAYER-4] PackageInstaller.createSession blocked for: " + targetPkg);
                                throw new IOException("UpdateBlocker: installation blocked for " + targetPkg);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + " [LAYER-4] PackageInstaller.createSession hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-4] PackageInstaller hook error: " + t.getMessage());
        }
    }
}
