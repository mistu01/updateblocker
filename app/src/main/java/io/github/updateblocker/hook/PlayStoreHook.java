package io.github.updateblocker.hook;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.PersistableBundle;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PlayStoreHook {
    private static final String TAG = "[UpdateBlocker-PlayStore]";
    private static final int SPOOFED_VERSION_CODE = 999999999;
    private static final long SPOOFED_LONG_VERSION_CODE = 999999999999L;
    private static final String SPOOFED_VERSION_NAME = "999.999.999";

    private static Context sAppContext = null;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Injecting comprehensive update & download blocker into Google Play Store (" + lpparam.processName + ")");

        // 1. Hook Application to capture Context early (before any update/download services start)
        hookApplicationContext();

        // 2. Hook IPackageManager$Stub$Proxy (Bypasses Play Store's direct Binder IPC queries)
        hookIPackageManager(lpparam.classLoader);

        // 3. Hook ApplicationPackageManager (All overloads of getPackageInfo and getInstalledPackages)
        hookApplicationPackageManager(lpparam.classLoader);

        // 4. Hook Play Store internal download queues & workers (Stops downloads before network traffic starts)
        hookDownloadEngines(lpparam.classLoader);

        // 5. Hook JobScheduler in Play Store (Prevents background download & sync jobs for blocked apps)
        hookJobScheduler();

        // 6. Hook PackageInstaller (Secondary guard against session creation)
        hookPackageInstaller(lpparam.classLoader);
    }

    private static void hookApplicationContext() {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attachBaseContext",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sAppContext = (Context) param.args[0];
                            PreferencesBridge.updateFromContext(sAppContext);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed hook Application.attachBaseContext: " + t.getMessage());
        }

        try {
            // Also hook Application.onCreate as fallback
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

    /**
     * Google Play Store uses IPackageManager directly (via AppGlobals or ServiceManager).
     * Hooking IPackageManager$Stub$Proxy intercepts every single package query Play Store makes.
     */
    private static void hookIPackageManager(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = XposedHelpers.findClass("android.content.pm.IPackageManager$Stub$Proxy", classLoader);
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
            XposedBridge.log(TAG + " Successfully hooked IPackageManager$Stub$Proxy");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " IPackageManager$Stub$Proxy hook error: " + t.getMessage());
        }
    }

    /**
     * Hook all overloads in ApplicationPackageManager (including hidden multi-user methods).
     */
    private static void hookApplicationPackageManager(ClassLoader classLoader) {
        try {
            Class<?> appPmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader);
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
            XposedBridge.log(TAG + " Successfully hooked ApplicationPackageManager");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ApplicationPackageManager hook error: " + t.getMessage());
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
            } catch (Throwable ignored) {
            }
        }

        if (packageName != null && PreferencesBridge.isBlocked(packageName, sAppContext)) {
            PackageInfo info = (PackageInfo) param.getResult();
            if (info != null) {
                spoofPackageInfo(info);
                param.setResult(info);
                XposedBridge.log(TAG + " [GHOST VERSION] Spoofed version for: " + packageName + " -> " + SPOOFED_VERSION_CODE);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleInstalledPackagesHook(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (result == null) return;

        List<PackageInfo> list = null;

        // In AOSP IPC, getInstalledPackages returns ParceledListSlice, not java.util.List!
        if (result instanceof List) {
            list = (List<PackageInfo>) result;
        } else if (result.getClass().getName().contains("ParceledListSlice")) {
            try {
                list = (List<PackageInfo>) XposedHelpers.callMethod(result, "getList");
            } catch (Throwable ignored) {
            }
        }

        if (list != null && !list.isEmpty()) {
            for (PackageInfo info : list) {
                if (info != null && PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                    spoofPackageInfo(info);
                    XposedBridge.log(TAG + " [GHOST BATCH] Spoofed list entry for: " + info.packageName);
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

    /**
     * Intercepts Play Store's internal download mechanisms (DownloadQueue, DownloadManager, DownloadRequest)
     * so that even if a download is requested, it is dropped BEFORE network connection or disk staging starts.
     */
    private static void hookDownloadEngines(ClassLoader classLoader) {
        String[] potentialQueueClasses = new String[]{
                "com.google.android.finsky.download.DownloadQueueImpl",
                "com.google.android.finsky.download.DownloadQueue",
                "com.google.android.finsky.download.DownloadManagerImpl",
                "com.google.android.finsky.installer.InstallTask",
                "com.google.android.finsky.installer.InstallerImpl"
        };

        for (String className : potentialQueueClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) continue;

                for (Method method : clazz.getDeclaredMethods()) {
                    String name = method.getName();
                    if ("add".equals(name) || "enqueue".equals(name) || "startDownload".equals(name) || "requestInstall".equals(name)) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String blockedPkg = extractBlockedPackageFromArgs(param.args);
                                if (blockedPkg != null) {
                                    XposedBridge.log(TAG + " [DOWNLOAD PREVENTED] Dropped download request for: " + blockedPkg);
                                    param.setResult(null); // Cancel the download operation
                                }
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Inspects arguments passed to download / install scheduling methods to detect blocked package names.
     */
    private static String extractBlockedPackageFromArgs(Object[] args) {
        if (args == null || args.length == 0) return null;

        for (Object arg : args) {
            if (arg == null) continue;

            // Direct string matching
            if (arg instanceof String) {
                String str = (String) arg;
                if (PreferencesBridge.isBlocked(str, sAppContext)) {
                    return str;
                }
            }

            // Collection of package names (e.g. List<String> in auto-update jobs)
            if (arg instanceof Collection) {
                for (Object item : (Collection<?>) arg) {
                    if (item instanceof String && PreferencesBridge.isBlocked((String) item, sAppContext)) {
                        return (String) item;
                    }
                }
            }

            // Check object fields for package name
            try {
                for (Field field : arg.getClass().getDeclaredFields()) {
                    if (field.getType() == String.class) {
                        field.setAccessible(true);
                        String val = (String) field.get(arg);
                        if (val != null && PreferencesBridge.isBlocked(val, sAppContext)) {
                            return val;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Check getter methods
            try {
                Method getPkgMethod = arg.getClass().getMethod("getPackageName");
                String val = (String) getPkgMethod.invoke(arg);
                if (val != null && PreferencesBridge.isBlocked(val, sAppContext)) {
                    return val;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    /**
     * Hook JobScheduler inside Play Store to drop background update jobs for blocked apps.
     */
    private static void hookJobScheduler() {
        try {
            XposedHelpers.findAndHookMethod(
                    JobScheduler.class,
                    "schedule",
                    JobInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            JobInfo jobInfo = (JobInfo) param.args[0];
                            if (jobInfo != null && isJobForBlockedApp(jobInfo)) {
                                XposedBridge.log(TAG + " [JOB CANCELLED] Cancelled Play Store background update job");
                                param.setResult(JobScheduler.RESULT_SUCCESS); // Fake success without running
                            }
                        }
                    }
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                XposedHelpers.findAndHookMethod(
                        JobScheduler.class,
                        "enqueue",
                        JobInfo.class,
                        JobWorkItem.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                JobInfo jobInfo = (JobInfo) param.args[0];
                                if (jobInfo != null && isJobForBlockedApp(jobInfo)) {
                                    XposedBridge.log(TAG + " [JOB CANCELLED] Cancelled Play Store enqueue job");
                                    param.setResult(JobScheduler.RESULT_SUCCESS);
                                }
                            }
                        }
                );
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " JobScheduler hook error: " + t.getMessage());
        }
    }

    private static boolean isJobForBlockedApp(JobInfo jobInfo) {
        PersistableBundle extras = jobInfo.getExtras();
        if (extras == null) return false;

        for (String key : extras.keySet()) {
            Object val = extras.get(key);
            if (val instanceof String && PreferencesBridge.isBlocked((String) val, sAppContext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PackageInstaller fail-safe hook.
     */
    private static void hookPackageInstaller(ClassLoader classLoader) {
        try {
            Class<?> piClass = XposedHelpers.findClass("android.content.pm.PackageInstaller", classLoader);
            XposedHelpers.findAndHookMethod(
                    piClass,
                    "createSession",
                    PackageInstaller.SessionParams.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            PackageInstaller.SessionParams params = (PackageInstaller.SessionParams) param.args[0];
                            if (params != null) {
                                String targetPkg = null;
                                try {
                                    targetPkg = (String) XposedHelpers.getObjectField(params, "appPackageName");
                                } catch (Throwable ignored) {
                                }

                                if (targetPkg != null && PreferencesBridge.isBlocked(targetPkg, sAppContext)) {
                                    XposedBridge.log(TAG + " [INSTALL BLOCKED] Blocked Play Store createSession for: " + targetPkg);
                                    throw new IOException("UpdateBlocker: Update installation blocked for " + targetPkg);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook PackageInstaller.createSession: " + t.getMessage());
        }
    }
}
