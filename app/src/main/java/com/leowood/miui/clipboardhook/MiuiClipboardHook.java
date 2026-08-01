package com.leowood.miui.clipboardhook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Narrow LSPosed compatibility hook for the HyperOS 2 MIUI+ clipboard path.
 *
 * The verified failure point is
 * ClipboardServiceStubImpl#checkProviderWakePathForClipboard(String, int,
 * ProviderInfo, int). This module only hooks that exact method and only turns
 * a false result into true for an allow-listed continuity caller/provider pair.
 */
public final class MiuiClipboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "[MiuiClipboardHook] ";
    private static final boolean DEBUG = false;
    private static final String OPTIMIZATION_KEY = "persist.sys.miui_optimization";

    private static final String CLIPBOARD_CLASS =
            "com.android.server.clipboard.ClipboardServiceStubImpl";
    private static final String CLIPBOARD_METHOD = "checkProviderWakePathForClipboard";
    private static final String EXPECTED_PROVIDER_PARAMETER = "android.content.pm.ProviderInfo";

    private static final Set<String> CONTINUITY_PACKAGES = new HashSet<>(Arrays.asList(
            "com.miui.mishare.connectivity",
            "com.milink.service",
            "com.xiaomi.mi_connect_service",
            "com.xiaomi.mirror"));

    /* Exact provider identities observed on the target HyperOS build. */
    private static final Set<String> ALLOWED_PROVIDER_IDENTITIES = new HashSet<>(Arrays.asList(
            "com.miui.phrase.input.provider",
            "com.miui.provider.InputProvider",
            "com.xiaomi.mirror.provider.CallProvider",
            "com.miui.circulate.device.service.DeviceControlProvider"));

    private static final Set<String> HOOKED = new HashSet<>();
    /* Static state is process-local under LSPosed; do not include packageName here. */
    private static final Set<String> PROPERTY_HOOKED = new HashSet<>();
    private static final Set<String> LOGGED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if ("android".equals(lpparam.packageName)) {
                installSystemServerHook(lpparam.classLoader);
            } else if (CONTINUITY_PACKAGES.contains(lpparam.packageName)) {
                installContinuityProcessHook(lpparam.classLoader, lpparam.packageName,
                        lpparam.processName);
            }
        } catch (Throwable error) {
            log("load hook failure package=" + lpparam.packageName
                    + " process=" + lpparam.processName
                    + " error=" + describe(error));
        }
    }

    private static void installContinuityProcessHook(
            ClassLoader loader, String packageName, String processName) {
        log("loaded continuity package=" + packageName + " process=" + processName);
        hookOptimizationProperty(loader, packageName, processName);
    }

    private static void hookOptimizationProperty(
            ClassLoader loader, final String packageName, final String processName) {
        Class<?> properties = XposedHelpers.findClassIfExists("android.os.SystemProperties", loader);
        if (properties == null) {
            log("SystemProperties not found package=" + packageName
                    + " process=" + processName);
            return;
        }

        hookPropertyGetter(properties, "getBoolean", packageName, processName);
        hookPropertyGetter(properties, "get", packageName, processName);
    }

    private static void hookPropertyGetter(
            Class<?> properties, final String methodName,
            final String packageName, final String processName) {
        String key = properties.getName() + "#" + methodName;
        synchronized (PROPERTY_HOOKED) {
            if (!PROPERTY_HOOKED.add(key)) {
                debug("property hook already installed method=" + key
                        + " process=" + processName);
                return;
            }
        }

        try {
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
                                logOnce("property:boolean:" + processName,
                                        "forced optimization=true package=" + packageName
                                                + " process=" + processName
                                                + " method=" + methodName);
                            } else if ("get".equals(methodName)
                                    && param.getResult() instanceof String
                                    && "false".equalsIgnoreCase((String) param.getResult())) {
                                param.setResult("true");
                                logOnce("property:string:" + processName,
                                        "forced optimization string=true package=" + packageName
                                                + " process=" + processName
                                                + " method=" + methodName);
                            }
                        }
                    });
            log("hooked property method=" + key + " overloads=" + unhooks.size()
                    + " process=" + processName);
        } catch (Throwable error) {
            log("property hook failed=" + key + " process=" + processName
                    + " error=" + describe(error));
        }
    }

    private static void installSystemServerHook(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(CLIPBOARD_CLASS, loader);
        if (type == null) {
            log("exact clipboard class not found class=" + CLIPBOARD_CLASS);
            return;
        }

        Method method = findExactClipboardMethod(type);
        if (method == null) {
            log("exact clipboard method not found class=" + CLIPBOARD_CLASS
                    + " method=" + CLIPBOARD_METHOD
                    + " expected=(String,int," + EXPECTED_PROVIDER_PARAMETER + ",int)boolean");
            return;
        }

        hookClipboardMethod(method);
    }

    private static Method findExactClipboardMethod(Class<?> type) {
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!CLIPBOARD_METHOD.equals(method.getName()) || !isExactSignature(method)) {
                continue;
            }
            if (match != null) {
                log("ambiguous exact clipboard method class=" + type.getName());
                return null;
            }
            match = method;
        }
        return match;
    }

    private static boolean isExactSignature(Method method) {
        if (Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != boolean.class) {
            return false;
        }

        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 4
                && parameters[0] == String.class
                && parameters[1] == int.class
                && EXPECTED_PROVIDER_PARAMETER.equals(parameters[2].getName())
                && parameters[3] == int.class;
    }

    private static void hookClipboardMethod(final Method method) {
        String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + methodSignature(method);
        if (!HOOKED.add(key)) {
            return;
        }

        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof Boolean)
                            || (Boolean) param.getResult()
                            || param.args == null
                            || param.args.length != 4
                            || !(param.args[0] instanceof String)
                            || !(param.args[1] instanceof Integer)
                            || !isContinuityCaller((String) param.args[0])
                            || !isAllowedProvider(param.args[2])) {
                        return;
                    }

                    String callerPackage = (String) param.args[0];
                    int callerUid = (Integer) param.args[1];
                    String providerName = providerName(param.args[2]);
                    param.setResult(true);
                    logOnce("allow:" + callerPackage + ":" + callerUid + ":" + providerName,
                            "clipboard wake check false->true callerPackage=" + callerPackage
                                    + " callerUid=" + callerUid
                                    + " providerName=" + providerName
                                    + " method=" + method.getName());
                }
            });
            log("hooked exact clipboard method=" + key);
        } catch (Throwable error) {
            log("clipboard hook failed=" + key + " error=" + describe(error));
        }
    }

    private static boolean isContinuityCaller(String callerPackage) {
        return CONTINUITY_PACKAGES.contains(callerPackage);
    }

    private static boolean isAllowedProvider(Object provider) {
        if (provider == null) {
            return false;
        }
        return ALLOWED_PROVIDER_IDENTITIES.contains(readStringField(provider, "name"))
                || ALLOWED_PROVIDER_IDENTITIES.contains(readStringField(provider, "authority"))
                || ALLOWED_PROVIDER_IDENTITIES.contains(readStringField(provider, "className"));
    }

    private static String providerName(Object provider) {
        String name = readStringField(provider, "name");
        if (name != null) {
            return name;
        }
        String authority = readStringField(provider, "authority");
        if (authority != null) {
            return authority;
        }
        String className = readStringField(provider, "className");
        return className == null ? "<unknown>" : className;
    }

    private static String readStringField(Object object, String fieldName) {
        try {
            Object value = XposedHelpers.getObjectField(object, fieldName);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String methodSignature(Method method) {
        if (!DEBUG) {
            return "";
        }
        return method.toGenericString();
    }

    private static void debug(String message) {
        if (DEBUG) {
            log(message);
        }
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
