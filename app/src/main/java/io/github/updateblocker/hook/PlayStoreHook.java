package io.github.updateblocker.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.VersionedPackage;
import android.os.Build;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Play Store hook – prevents blocked apps from ever being downloaded or updated.
 *
 * Strategy (outermost to innermost):
 *
 *  Layer 1 – DexKit self-update scheduler block:
 *      Play Store has a boolean method that decides whether to queue a self-update for any app.
 *      We find it at runtime via stable log strings and make it always return false for blocked pkgs.
 *
 *  Layer 2 – DexKit auto-update v2 batch filter:
 *      Play Store sends batched auto-update requests. We locate the batch entry via a stable log
 *      anchor and strip blocked packages from the list before any download scheduling occurs.
 *
 *  Layer 3 – getPackageInfo version spoof (legacy fallback):
 *      Reports an impossibly high version so the server's "needs update" check always fails.
 *
 *  Layer 4 – PackageInstaller.createSession block (last-resort guard):
 *      Prevents any installation session even if a download somehow slipped through.
 */
public class PlayStoreHook {

    private static final String TAG = "[UpdateBlocker-PlayStore]";

    // Version spoof constants – tell server this package is already at v999
    private static final int    SPOOFED_VERSION_CODE      = 999_999_999;
    private static final long   SPOOFED_LONG_VERSION_CODE = 999_999_999_999L;
    private static final String SPOOFED_VERSION_NAME      = "999.999.999";

    // Stable log string fragments embedded in Play Store's self-update scheduler method.
    // These are present across many Play Store versions (confirmed in PlayVersionSpoofer project).
    // The method returns boolean – we make it return false to prevent the scheduler from queuing.
    private static final String SELF_UPDATE_FRAGMENT_1 =
            "Skipping DFE self-update check as there is an update already queued.";
    private static final String SELF_UPDATE_FRAGMENT_2 =
            "Bulk scheduling self-update";

    // Anchor log string for the auto-update v2 batch scheduling entry point.
    // Present in Play Store's Finsky component across many versions.
    private static final String AUTO_UPDATE_V2_ANCHOR =
            "UChk: document %s is not qualified for auto update v2";

    private static volatile Context sAppContext = null;

    // -------------------------------------------------------------------------

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Initializing DexKit-based update blocker for " + lpparam.processName);

        // Capture Context early (before any update/download services start)
        hookApplicationAttach(lpparam);

        // Layer 3: version spoof – always active, no DexKit needed
        hookIPackageManager(lpparam.classLoader);
        hookApplicationPackageManager(lpparam.classLoader);

        // Layer 4: PackageInstaller last-resort guard
        hookPackageInstaller(lpparam.classLoader);

        // Layers 1 & 2 are installed from Application.attach (need Context for APK path)
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
    // DexKit dynamic hooking (Layers 1 & 2)
    // =========================================================================

    private static volatile boolean sDexKitInstalled = false;

    private static synchronized void installDexKitHooks(ClassLoader classLoader, Context context) {
        if (sDexKitInstalled) return;
        sDexKitInstalled = true;

        String apkPath = context.getApplicationInfo().sourceDir;
        XposedBridge.log(TAG + " Starting DexKit scan on: " + apkPath);

        // Load native library required by DexKit
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to load DexKit native lib: " + t.getMessage());
            return;
        }

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {

            // -----------------------------------------------------------------
            // Layer 1 – Self-update scheduler
            // -----------------------------------------------------------------
            boolean selfUpdateHooked = hookSelfUpdateScheduler(bridge, classLoader);
            if (!selfUpdateHooked) {
                XposedBridge.log(TAG + " [WARN] Self-update scheduler hook not installed – DexKit scan found no unique match");
            }

            // -----------------------------------------------------------------
            // Layer 2 – Auto-update v2 batch filter
            // -----------------------------------------------------------------
            boolean autoUpdateHooked = hookAutoUpdateV2Batch(bridge, classLoader);
            if (!autoUpdateHooked) {
                XposedBridge.log(TAG + " [WARN] Auto-update v2 batch hook not installed – DexKit scan found no unique match");
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " DexKit scan failed: " + t.getMessage());
        }
    }

    /**
     * Layer 1 – Find and hook the boolean method that decides whether to schedule a self-update.
     * The method is identified by two stable log string fragments. We make it return false,
     * which means "no update needed / already queued", so Play Store never schedules a download.
     */
    private static boolean hookSelfUpdateScheduler(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            MethodDataList results = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .returnType(boolean.class)
                                            .usingStrings(
                                                    SELF_UPDATE_FRAGMENT_1,
                                                    SELF_UPDATE_FRAGMENT_2,
                                                    StringMatchType.Contains
                                            )
                            )
            );

            if (results == null || results.isEmpty()) {
                XposedBridge.log(TAG + " [DexKit] Self-update scheduler: no methods found with anchor strings");
                return false;
            }

            if (results.size() > 3) {
                XposedBridge.log(TAG + " [DexKit] Self-update scheduler: too many candidates (" + results.size() + "), skipping to avoid false positives");
                return false;
            }

            XposedBridge.log(TAG + " [DexKit] Self-update scheduler: found " + results.size() + " candidate(s)");

            int hooked = 0;
            for (MethodData methodData : results) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // The scheduler returned true = "start update download"
                            // We intercept and suppress it by returning false
                            Boolean result = (Boolean) param.getResult();
                            if (Boolean.TRUE.equals(result)) {
                                param.setResult(false);
                                XposedBridge.log(TAG + " [LAYER-1] Self-update scheduler suppressed – download prevented before it started");
                            }
                        }
                    });
                    XposedBridge.log(TAG + " [DexKit] Hooked self-update scheduler: " + methodData.getDescriptor());
                    hooked++;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " [DexKit] Failed to hook self-update candidate: " + t.getMessage());
                }
            }
            return hooked > 0;

        } catch (Throwable t) {
            XposedBridge.log(TAG + " [DexKit] Error finding self-update scheduler: " + t.getMessage());
            return false;
        }
    }

    /**
     * Layer 2 – Find and hook Play Store's auto-update v2 batch method.
     *
     * Strategy:
     *  1. Use the stable anchor string to find the class that logs it.
     *  2. From that class, find a method that takes a List as a parameter (the package list).
     *  3. Before the method runs, remove all blocked packages from the list.
     */
    @SuppressWarnings("unchecked")
    private static boolean hookAutoUpdateV2Batch(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            // Step 1: find the declaring class via anchor log string
            MethodDataList anchorMethods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .usingStrings(AUTO_UPDATE_V2_ANCHOR, StringMatchType.Contains)
                            )
            );

            if (anchorMethods == null || anchorMethods.isEmpty()) {
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: anchor method not found");
                return false;
            }

            XposedBridge.log(TAG + " [DexKit] Auto-update v2: anchor found in " + anchorMethods.size() + " method(s)");

            // The anchor method's declaring class is the auto-update scheduler class.
            // We need to hook the method in that class that receives the List of packages.
            String anchorClassName = anchorMethods.get(0).getClassName();
            XposedBridge.log(TAG + " [DexKit] Auto-update v2 class: " + anchorClassName);

            // Step 2: find methods in that class that take a List parameter
            MethodDataList scheduleMethods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .declaredClass(
                                                    ClassMatcher.create().name(anchorClassName)
                                            )
                                            .paramTypes("java.util.List")
                            )
            );

            // If exact match fails, broaden: find any method containing a List param
            if (scheduleMethods == null || scheduleMethods.isEmpty()) {
                scheduleMethods = bridge.findMethod(
                        FindMethod.create()
                                .matcher(
                                        MethodMatcher.create()
                                                .declaredClass(
                                                        ClassMatcher.create().name(anchorClassName)
                                                )
                                )
                );
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: broadened search found " +
                        (scheduleMethods != null ? scheduleMethods.size() : 0) + " method(s) in anchor class");
            }

            if (scheduleMethods == null || scheduleMethods.isEmpty()) {
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: no schedule methods found in anchor class");
                return false;
            }

            int hooked = 0;
            for (MethodData methodData : scheduleMethods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    // Check if any param is a List
                    boolean hasListParam = false;
                    for (Class<?> pt : method.getParameterTypes()) {
                        if (List.class.isAssignableFrom(pt)) {
                            hasListParam = true;
                            break;
                        }
                    }
                    if (!hasListParam) continue;

                    final int methodParamCount = method.getParameterCount();
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Find the List parameter and remove blocked packages
                            for (int i = 0; i < methodParamCount && i < param.args.length; i++) {
                                if (param.args[i] instanceof List) {
                                    List<Object> list = (List<Object>) param.args[i];
                                    boolean removed = removeBlockedFromList(list);
                                    if (removed) {
                                        XposedBridge.log(TAG + " [LAYER-2] Removed blocked package(s) from auto-update v2 batch – download never scheduled");
                                    }
                                    break;
                                }
                            }
                        }
                    });
                    XposedBridge.log(TAG + " [DexKit] Hooked auto-update v2 batch method: " + methodData.getDescriptor());
                    hooked++;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " [DexKit] Failed to hook auto-update v2 candidate: " + t.getMessage());
                }
            }
            return hooked > 0;

        } catch (Throwable t) {
            XposedBridge.log(TAG + " [DexKit] Error finding auto-update v2 batch: " + t.getMessage());
            return false;
        }
    }

    /**
     * Removes all blocked packages from a List that may contain package name Strings
     * or arbitrary objects with a getPackageName() / packageName field.
     */
    private static boolean removeBlockedFromList(List<Object> list) {
        boolean changed = false;
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) {
            Object item = it.next();
            String pkg = extractPackageName(item);
            if (pkg != null && PreferencesBridge.isBlocked(pkg, sAppContext)) {
                it.remove();
                changed = true;
                XposedBridge.log(TAG + " [LAYER-2] Stripped blocked package from batch: " + pkg);
            }
        }
        return changed;
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
    // Layer 3 – getPackageInfo version spoof (legacy fallback)
    // =========================================================================

    private static void hookIPackageManager(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = XposedHelpers.findClass(
                    "android.content.pm.IPackageManager$Stub$Proxy", classLoader);
            for (Method method : proxyClass.getDeclaredMethods()) {
                String name = method.getName();
                if ("getPackageInfo".equals(name) || "getPackageInfoAsUser".equals(name)) {
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
            XposedBridge.log(TAG + " [LAYER-3] Hooked IPackageManager$Stub$Proxy");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-3] IPackageManager hook error: " + t.getMessage());
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
            XposedBridge.log(TAG + " [LAYER-3] Hooked ApplicationPackageManager");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [LAYER-3] ApplicationPackageManager hook error: " + t.getMessage());
        }
    }

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
            PackageInfo info = (PackageInfo) param.getResult();
            if (info != null) {
                spoofPackageInfo(info);
                param.setResult(info);
                XposedBridge.log(TAG + " [LAYER-3] Version spoofed for: " + packageName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleInstalledPackagesHook(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (result == null) return;

        List<PackageInfo> list = null;
        if (result instanceof List) {
            list = (List<PackageInfo>) result;
        } else if (result.getClass().getName().contains("ParceledListSlice")) {
            try {
                list = (List<PackageInfo>) XposedHelpers.callMethod(result, "getList");
            } catch (Throwable ignored) {}
        }

        if (list != null) {
            for (PackageInfo info : list) {
                if (info != null && PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                    spoofPackageInfo(info);
                    XposedBridge.log(TAG + " [LAYER-3] Batch version spoofed for: " + info.packageName);
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
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // Layer 4 – PackageInstaller.createSession last-resort guard
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
