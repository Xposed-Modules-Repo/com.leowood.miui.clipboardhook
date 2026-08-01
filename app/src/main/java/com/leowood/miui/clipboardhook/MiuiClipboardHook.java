package com.leowood.miui.clipboardhook;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Narrow LSPosed compatibility hook for the HyperOS 2 MIUI+ clipboard path.
 *
 * The phone-side investigation showed that clipboard settings and app-ops are
 * already enabled. The remaining failure is the background/provider wake path
 * and the continuity services' read of persist.sys.miui_optimization.
 *
 * This build is deliberately diagnostic and narrow:
 * - only the listed Xiaomi continuity packages are affected;
 * - the system_server hook only changes a false boolean when the call carries
 *   one of those package names;
 * - no account, pairing database, or clipboard content is modified.
 */
public final class MiuiClipboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "[MiuiClipboardHook] ";
    private static final String OPTIMIZATION_KEY = "persist.sys.miui_optimization";
    private static final Set<String> CONTINUITY_PACKAGES = new HashSet<>(Arrays.asList(
            "com.miui.mishare.connectivity",
            "com.milink.service",
            "com.xiaomi.mi_connect_service",
            "com.xiaomi.mirror"));
    private static final Set<String> HOOKED = new HashSet<>();
    private static final Set<String> LOGGED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if ("android".equals(lpparam.packageName)) {
                installSystemServerHooks(lpparam.classLoader);
                return;
            }

            if (CONTINUITY_PACKAGES.contains(lpparam.packageName)) {
                installContinuityProcessHooks(lpparam.classLoader, lpparam.packageName,
                        lpparam.processName);
            }
        } catch (Throwable error) {
            log("load failure package=" + lpparam.packageName + " error=" + describe(error));
        }
    }

    private static void installContinuityProcessHooks(
            ClassLoader loader, String packageName, String processName) {
        log("loaded continuity package=" + packageName + " process=" + processName);
        hookOptimizationProperty(loader, packageName);
    }

    private static void hookOptimizationProperty(ClassLoader loader, String packageName) {
        Class<?> properties = XposedHelpers.findClassIfExists("android.os.SystemProperties", loader);
        if (properties == null) {
            log("SystemProperties not found package=" + packageName);
            return;
        }

        hookPropertyGetter(properties, "getBoolean", packageName);
        hookPropertyGetter(properties, "get", packageName);
    }

    private static void hookPropertyGetter(Class<?> properties, String methodName, String packageName) {
        String key = properties.getName() + "#" + methodName + ":" + packageName;
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                properties,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !OPTIMIZATION_KEY.equals(String.valueOf(param.args[0]))) {
                            return;
                        }

                        if ("getBoolean".equals(methodName)
                                && param.getResult() instanceof Boolean
                                && !((Boolean) param.getResult())) {
                            param.setResult(true);
                            logOnce("property:" + packageName,
                                    "forced optimization=true package=" + packageName);
                        } else if ("get".equals(methodName)
                                && param.getResult() instanceof String
                                && "false".equalsIgnoreCase((String) param.getResult())) {
                            param.setResult("true");
                            logOnce("property-string:" + packageName,
                                    "forced optimization string=true package=" + packageName);
                        }
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void installSystemServerHooks(ClassLoader loader) {
        log("loaded system_server hook");

        String[] candidates = new String[]{
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardServiceImpl",
                "com.android.server.clipboard.ClipboardServiceI",
                "com.android.server.clipboard.MiuiClipboardService",
                "com.android.server.clipboard.ClipboardServiceStub",
                "com.android.server.clipboard.ClipboardServiceStubImpl"};

        for (String className : candidates) {
            Class<?> type = XposedHelpers.findClassIfExists(className, loader);
            if (type == null) {
                continue;
            }
            log("clipboard class found=" + className);
            hookClipboardWakeMethods(type);
        }
    }

    private static void hookClipboardWakeMethods(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("wake") && !name.contains("permissionowner")) {
                continue;
            }
            hookClipboardMethod(type, method.getName());
        }
    }

    private static void hookClipboardMethod(Class<?> type, String methodName) {
        String key = type.getName() + "#" + methodName;
        if (!HOOKED.add(key)) {
            return;
        }

        try {
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                    type,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.getResult() instanceof Boolean)
                                    || (Boolean) param.getResult()
                                    || !isContinuityCaller(param.args)) {
                                return;
                            }

                            param.setResult(true);
                            log("clipboard wake check false->true method="
                                    + methodName + " args=" + summarizeArgs(param.args));
                        }
                    });
            log("hooked clipboard method=" + key + " overloads=" + unhooks.size());
        } catch (Throwable error) {
            log("clipboard hook failed=" + key + " error=" + describe(error));
        }
    }

    private static boolean isContinuityCaller(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        return CONTINUITY_PACKAGES.contains(String.valueOf(args[0]));
    }

    private static String summarizeArgs(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String text = String.valueOf(args[i]);
            if (text.length() > 160) {
                text = text.substring(0, 160) + "…";
            }
            out.append(text);
        }
        return out.append(']').toString();
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    private static synchronized void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            log(message);
        }
    }
}
