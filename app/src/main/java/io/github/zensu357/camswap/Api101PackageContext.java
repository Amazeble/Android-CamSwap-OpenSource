package io.github.zensu357.camswap;

import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class Api101PackageContext {

    public final XposedModule module;
    public final XposedModuleInterface.PackageReadyParam param;
    public final ClassLoader classLoader;
    public final String packageName;
    public final String processName;
    public final String hostPackageName;
    public final ApplicationInfo appInfo;
    public final boolean isFirstPackage;

    /** Camera ID this module targets for virtual camera injection. */
    public final String targetCameraId;

    public Api101PackageContext(XposedModule module,
                                XposedModuleInterface.PackageReadyParam param) {
        this.module = module;
        this.param = param;
        this.classLoader = param.getClassLoader();
        this.packageName = param.getPackageName();
        this.appInfo = param.getApplicationInfo();

        // Use the runtime process name, not ApplicationInfo.processName.
        // ApplicationInfo always returns the default process name,
        // but multi-process apps (e.g. org.mozilla.firefox:tab27)
        // need the actual runtime value for correct hook targeting.
        this.processName = resolveRuntimeProcessName(
                this.appInfo, this.packageName);

        this.hostPackageName = resolveHostPackageName(
                this.packageName, this.processName);
        this.isFirstPackage = param.isFirstPackage();

        // Camera 101 is the virtual camera wrapper for the front sensor.
        this.targetCameraId = "101";
    }

    /**
     * Resolve the actual runtime process name.
     * Falls back through: ActivityThread.currentProcessName()
     *   → ApplicationInfo.processName → packageName
     */
    private static String resolveRuntimeProcessName(
            ApplicationInfo appInfo, String packageName) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object name = at.getMethod("currentProcessName").invoke(null);
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        } catch (Throwable ignored) {
        }
        if (appInfo != null && appInfo.processName != null
                && !appInfo.processName.isEmpty()) {
            return appInfo.processName;
        }
        return packageName;
    }

    private static String resolveHostPackageName(
            String packageName, String processName) {
        String resolved = processName;
        if (resolved == null || resolved.isEmpty()) {
            resolved = packageName;
        }
        if (resolved == null || resolved.isEmpty()) {
            return resolved;
        }
        int separator = resolved.indexOf(':');
        return separator > 0
                ? resolved.substring(0, separator) : resolved;
    }
}
