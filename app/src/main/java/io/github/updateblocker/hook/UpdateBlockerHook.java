package io.github.updateblocker.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class UpdateBlockerHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String MODULE_PKG = "io.github.updateblocker";
    private static final String PLAY_STORE_PKG = "com.android.vending";
    private static final String ANDROID_PKG = "android";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        PreferencesBridge.initZygote();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. Module Self Status Check
        if (MODULE_PKG.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "io.github.updateblocker.ui.MainActivity",
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
            } catch (Throwable t) {
                XposedBridge.log("[UpdateBlocker] Failed to hook isModuleActive: " + t.getMessage());
            }
            return;
        }

        // 2. Google Play Store Hooks
        if (PLAY_STORE_PKG.equals(lpparam.packageName)) {
            PlayStoreHook.init(lpparam);
            return;
        }

        // 3. Android System Framework Hooks
        if (ANDROID_PKG.equals(lpparam.packageName)) {
            SystemServerHook.init(lpparam);
        }
    }
}
