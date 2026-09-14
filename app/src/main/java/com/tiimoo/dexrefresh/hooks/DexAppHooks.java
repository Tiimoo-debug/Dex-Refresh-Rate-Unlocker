package com.tiimoo.dexrefresh.hooks;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;
import com.tiimoo.dexrefresh.core.Throttle;

import java.util.regex.Pattern;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Optional app-side probes for the DeX packages.
 *
 * <p>Inactive unless you add these packages to the module's scope in LSPosed.
 * They answer a different question from the system_server hooks: not "what does
 * the framework allow" but "what is DeX itself asking for". If the DeX
 * launcher/UI service requests 60 Hz for its own windows, no amount of
 * framework-side unlocking will help, and the fix belongs elsewhere.
 */
public final class DexAppHooks {

    private DexAppHooks() {
    }

    private static final String[] DEX_PACKAGES = {
            "com.sec.android.app.desktoplauncher",
            "com.sec.android.app.dexsystemui",
            "com.sec.android.desktopmode.uiservice",
    };

    private static final Pattern FRAME_RATE_METHODS =
            Pattern.compile("(?i)setframerate|setrefreshrate|preferreddisplaymode|preferredrefresh");

    public static boolean isDexPackage(String packageName) {
        for (String p : DEX_PACKAGES) {
            if (p.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        HookEngine.hookMatching(cl, "android.view.Surface", FRAME_RATE_METHODS, false);
        HookEngine.hookMatching(cl, "android.view.SurfaceControl$Transaction",
                FRAME_RATE_METHODS, false);
        HookEngine.hookMatching(cl, "android.view.Display", Cfg.HOOKABLE_METHOD, true);
        hookWindowAttributes(cl);
    }

    /**
     * Log the refresh-rate preferences each DeX window is created with.
     * WindowManager.LayoutParams carries them as plain fields, which cannot be
     * hooked directly, so read them as the window is added.
     */
    private static void hookWindowAttributes(ClassLoader cl) {
        HookEngine.hookNamed(cl, "android.view.ViewRootImpl", "setView", false,
                (site, param) -> {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    Object lp = param.args[1];
                    if (lp == null) {
                        return;
                    }
                    String summary = "preferredDisplayModeId="
                            + Reflect.get(lp, "preferredDisplayModeId")
                            + " preferredRefreshRate="
                            + Reflect.get(lp, "preferredRefreshRate")
                            + " preferredMinDisplayRefreshRate="
                            + Reflect.get(lp, "preferredMinDisplayRefreshRate")
                            + " preferredMaxDisplayRefreshRate="
                            + Reflect.get(lp, "preferredMaxDisplayRefreshRate")
                            + " title=" + Dumper.describe(Reflect.get(lp, "mTitle"), true);
                    if (Throttle.changed("window-attrs", summary)
                            && Throttle.allow("window-attrs", 30, Cfg.RATE_LIMIT_WINDOW_MS)) {
                        ProbeLog.post("WINDOW %s", summary);
                    }
                });
    }
}
