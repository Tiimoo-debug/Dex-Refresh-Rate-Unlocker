package com.tiimoo.dexrefresh;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.hooks.DexAppHooks;
import com.tiimoo.dexrefresh.hooks.DisplayHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point.
 *
 * <p>PHASE 1 - OBSERVATION ONLY. Nothing in this module alters behaviour: no
 * hook calls setResult, replaces an argument or writes a field. The goal of
 * this pass is to find out where One UI 7 filters the high refresh rates out
 * while DeX is running; changing anything before we know that would just be the
 * APK-downgrade experiment again with extra steps.
 *
 * <p>Scope is system_server (`android`). The three DeX packages are supported
 * but not scoped by default - see app/src/main/res/values/arrays.xml.
 */
public class ProbeModule implements IXposedHookLoadPackage {

    private static final String BANNER =
            "DeX Refresh Probe " + BuildConfig.VERSION_NAME + " (observation only)";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                // The `android` package is also loaded into processes that are
                // not system_server; only the real one is interesting.
                if (!"android".equals(lpparam.processName)) {
                    return;
                }
                ProbeLog.start();
                ProbeLog.postNow(BANNER + " - attached to system_server");
                DisplayHooks.install(lpparam.classLoader);
                return;
            }
            if (DexAppHooks.isDexPackage(lpparam.packageName)) {
                ProbeLog.start();
                ProbeLog.postNow(BANNER + " - attached to " + lpparam.packageName);
                DexAppHooks.install(lpparam);
            }
        } catch (Throwable t) {
            // A probe that crashes system_server is worse than no probe.
            try {
                ProbeLog.postNow("FATAL during hook install: " + t);
                ProbeLog.postNow(android.util.Log.getStackTraceString(t));
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }
}
