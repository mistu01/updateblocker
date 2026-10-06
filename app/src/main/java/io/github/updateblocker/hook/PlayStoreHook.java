package io.github.updateblocker.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.VersionedPackage;
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
 *  Layer 1 – DexKit self-update scheduler block:
 *      Play Store has a boolean method that decides whether to queue a self-update.
 *      We locate it at runtime via stable log strings and suppress update scheduling.
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
    private static final String SELF_UPDATE_FRAGMENT_1 =
            "Skipping DFE self-update check as there is an update already queued.";
    private static final String SELF_UPDATE_FRAGMENT_2 =
            "Bulk scheduling self-update with policies";

    // Anchor log string for the auto-update v2 batch scheduling entry point.
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

        // Load native library bundled by DexKit
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to load DexKit native lib: " + t.getMessage());
            return;
        }

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {

            // Layer 1 – Self-update scheduler
            boolean selfUpdateHooked = hookSelfUpdateScheduler(bridge, classLoader);
            if (!selfUpdateHooked) {
                XposedBridge.log(TAG + " [WARN] Self-update scheduler hook not installed");
            }

            // Layer 2 – Auto-update v2 batch filter
            boolean autoUpdateHooked = hookAutoUpdateV2Batch(bridge, classLoader);
            if (!autoUpdateHooked) {
                XposedBridge.log(TAG + " [WARN] Auto-update v2 batch hook not installed");
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " DexKit scan failed: " + t.getMessage());
        }
    }

    /**
     * Layer 1 – Find and hook the boolean method that decides whether to schedule a self-update.
     */
    private static boolean hookSelfUpdateScheduler(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            MethodDataList results = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .returnType("boolean")
                                            .usingStrings(
                                                    Arrays.asList(SELF_UPDATE_FRAGMENT_1, SELF_UPDATE_FRAGMENT_2),
                                                    StringMatchType.Contains
                                            )
                            )
            );

            if (results == null || results.isEmpty()) {
                XposedBridge.log(TAG + " [DexKit] Self-update scheduler: no methods found with anchor strings");
                return false;
            }

            if (results.size() > 3) {
                XposedBridge.log(TAG + " [DexKit] Self-update scheduler: too many candidates (" + results.size() + "), skipping");
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
                            Boolean result = (Boolean) param.getResult();
                            if (Boolean.TRUE.equals(result)) {
                                param.setResult(false);
                                XposedBridge.log(TAG + " [LAYER-1] Suppressed self-update scheduler download");
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
     */
    private static boolean hookAutoUpdateV2Batch(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            // Find declaring class via anchor log string
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

            // The auto-update scheduler method in anchorClass has:
            // returnType == void, 4 parameters:
            // param 0: Callback (has a void method with 1 parameter of type java.util.Set)
            // param 1: int
            // param 2: List (package names to auto-update)
            // param 3: object
            Method targetMethod = null;
            for (Method m : anchorClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (m.getReturnType() == void.class
                        && params.length == 4
                        && params[1] == int.class
                        && List.class.isAssignableFrom(params[2])
                        && hasSetCallback(params[0])) {
                    targetMethod = m;
                    break;
                }
            }

            if (targetMethod == null) {
                XposedBridge.log(TAG + " [DexKit] Auto-update v2: target scheduling method not found in anchor class");
                return false;
            }

            final Method scheduleMethod = targetMethod;
            XposedBridge.hookMethod(scheduleMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object packagesArg = param.args[2];
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

                    // Filter out blocked packages
                    ArrayList<Object> filteredList = new ArrayList<>();
                    for (Object item : packagesList) {
                        String pkg = extractPackageName(item);
                        if (pkg == null || !PreferencesBridge.isBlocked(pkg, sAppContext)) {
                            filteredList.add(item);
                        } else {
                            XposedBridge.log(TAG + " [LAYER-2] Stripped blocked package from auto-update batch: " + pkg);
                        }
                    }

                    if (!filteredList.isEmpty()) {
                        // Reassign filtered list so remaining apps update normally
                        param.args[2] = filteredList;
                        XposedBridge.log(TAG + " [LAYER-2] Modified auto-update batch, removed blocked packages");
                    } else {
                        // All apps in batch were blocked! Complete callback with empty set and cancel execution
                        try {
                            completeAutoUpdateCallback(param.args[0]);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " [LAYER-2] Failed to complete callback: " + t.getMessage());
                        }
                        param.setResult(null);
                        XposedBridge.log(TAG + " [LAYER-2] Blocked auto-update v2 batch completely — download never scheduled");
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

    private static boolean hasSetCallback(Class<?> callbackType) {
        for (Method m : callbackType.getMethods()) {
            if (m.getReturnType() == void.class
                    && m.getParameterCount() == 1
                    && Set.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return true;
            }
        }
        return false;
    }

    private static void completeAutoUpdateCallback(Object callback) throws Exception {
        if (callback == null) return;
        for (Method m : callback.getClass().getMethods()) {
            if (m.getReturnType() == void.class
                    && m.getParameterCount() == 1
                    && Set.class.isAssignableFrom(m.getParameterTypes()[0])) {
                m.invoke(callback, Collections.emptySet());
                return;
            }
        }
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
