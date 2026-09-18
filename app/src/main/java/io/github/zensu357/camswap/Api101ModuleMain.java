package io.github.zensu357.camswap;

import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.api101.Camera101SessionConfig;
import io.github.zensu357.camswap.utils.LogUtil;

public class Api101ModuleMain extends XposedModule {

    private static final String TAG = "CamSwap-API101";

    private final Set<String> initializedPackages = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Shared Camera 101 session config holder.
     * HookMain reads this to apply VTCam-compatible streams.
     */
    public static final Camera101SessionConfig CAMERA101_CONFIG =
            new Camera101SessionConfig();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Api101Runtime.setModule(this);
        LogUtil.log("【CS】LibXposed 已加载 framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion()
                + " api=" + getApiVersion()
                + " process=" + param.getProcessName()
                + " props=" + getFrameworkProperties());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String processName = resolveRuntimeProcessName();
        String hostPackage = resolveHostFromProcess(processName);

        LogUtil.log("【CS】onPackageReady: package=" + param.getPackageName()
                + " process=" + processName + " host=" + hostPackage
                + " isFirst=" + param.isFirstPackage()
                + " classLoader=" + param.getClassLoader());

        String dedupKey = param.getPackageName() + "|"
                + (processName != null ? processName : "");
        if (!initializedPackages.add(dedupKey)) {
            LogUtil.log("【CS】跳过重复 onPackageReady: " + dedupKey);
            return;
        }

        try {
            new HookMain().handleLoadPackage(
                    new Api101PackageContext(this, param));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook package failed: "
                    + param.getPackageName()
                    + " process=" + processName, t);
        }
    }

    private static String resolveRuntimeProcessName() {
        try {
            Class<?> activityThread =
                    Class.forName("android.app.ActivityThread");
            Object processName = activityThread
                    .getMethod("currentProcessName").invoke(null);
            if (processName instanceof String) {
                return (String) processName;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String resolveHostFromProcess(String processName) {
        if (processName == null || processName.isEmpty()) {
            return processName;
        }
        int separator = processName.indexOf(':');
        return separator > 0
                ? processName.substring(0, separator) : processName;
    }
}
