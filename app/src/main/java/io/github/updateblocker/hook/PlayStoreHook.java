package io.github.updateblocker.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;

import java.io.IOException;
import java.lang.reflect.Method;
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
        XposedBridge.log(TAG + " Injecting hooks into Google Play Store (" + lpparam.packageName + ")");

        // 1. Hook Application to get Context
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
            XposedBridge.log(TAG + " Failed to hook Application.attachBaseContext: " + t.getMessage());
        }

        // 2. Hook ApplicationPackageManager.getPackageInfo
        hookPackageManager(lpparam.classLoader);

        // 3. Hook PackageInstaller.createSession
        hookPackageInstaller(lpparam.classLoader);
    }

    private static void hookPackageManager(ClassLoader classLoader) {
        try {
            Class<?> appPmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader);

            // Hook getPackageInfo(String, int)
            try {
                XposedHelpers.findAndHookMethod(
                        appPmClass,
                        "getPackageInfo",
                        String.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String packageName = (String) param.args[0];
                                handlePackageInfo(packageName, param);
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Failed to hook getPackageInfo(String, int): " + t.getMessage());
            }

            // Hook getPackageInfo(VersionedPackage, int)
            try {
                XposedHelpers.findAndHookMethod(
                        appPmClass,
                        "getPackageInfo",
                        VersionedPackage.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                VersionedPackage vp = (VersionedPackage) param.args[0];
                                if (vp != null) {
                                    handlePackageInfo(vp.getPackageName(), param);
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                // Ignore if not present
            }

            // Hook Android 13+ PackageInfoFlags overloads if present
            if (Build.VERSION.SDK_INT >= 33) {
                for (Method method : appPmClass.getDeclaredMethods()) {
                    if ("getPackageInfo".equals(method.getName()) && method.getParameterCount() == 2) {
                        Class<?>[] params = method.getParameterTypes();
                        if (params[0] == String.class && "android.content.pm.PackageManager$PackageInfoFlags".equals(params[1].getName())) {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    String packageName = (String) param.args[0];
                                    handlePackageInfo(packageName, param);
                                }
                            });
                        }
                    }
                }
            }

            // Hook getInstalledPackages(int)
            try {
                XposedHelpers.findAndHookMethod(
                        appPmClass,
                        "getInstalledPackages",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                handleInstalledPackages(param);
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Failed to hook getInstalledPackages(int): " + t.getMessage());
            }

            // Hook Android 13+ getInstalledPackages(PackageInfoFlags)
            if (Build.VERSION.SDK_INT >= 33) {
                for (Method method : appPmClass.getDeclaredMethods()) {
                    if ("getInstalledPackages".equals(method.getName()) && method.getParameterCount() == 1) {
                        Class<?>[] params = method.getParameterTypes();
                        if ("android.content.pm.PackageManager$PackageInfoFlags".equals(params[0].getName())) {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    handleInstalledPackages(param);
                                }
                            });
                        }
                    }
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ApplicationPackageManager: " + t.getMessage());
        }
    }

    private static void handlePackageInfo(String packageName, XC_MethodHook.MethodHookParam param) {
        if (packageName == null) return;
        if (PreferencesBridge.isBlocked(packageName, sAppContext)) {
            PackageInfo info = (PackageInfo) param.getResult();
            if (info != null) {
                spoofPackageInfo(info);
                param.setResult(info);
                XposedBridge.log(TAG + " Spoofed version for blocked package: " + packageName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleInstalledPackages(XC_MethodHook.MethodHookParam param) {
        List<PackageInfo> list = (List<PackageInfo>) param.getResult();
        if (list == null || list.isEmpty()) return;

        for (PackageInfo info : list) {
            if (info != null && PreferencesBridge.isBlocked(info.packageName, sAppContext)) {
                spoofPackageInfo(info);
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
                                    XposedBridge.log(TAG + " Blocked Play Store createSession for: " + targetPkg);
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
