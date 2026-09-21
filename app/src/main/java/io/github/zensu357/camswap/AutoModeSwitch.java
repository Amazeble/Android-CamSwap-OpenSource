package io.github.zensu357.camswap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * AutoModeSwitch
 *
 * Called when CaptureGate receives pre-capture onCaptureCompleted().
 *
 * Behavior:
 *   pre-capture completed
 *     -> switch passthrough -> CamSwap
 *     -> drop CaptureGate.markReady()
 *     -> CameraX continues to still capture
 *
 * If the real switch API is not found by reflection, set your own handler:
 *
 *   AutoModeSwitch.setSwitchHandler(() -> {
 *       // your real passthrough -> CamSwap switch code
 *   });
 */
public final class AutoModeSwitch {

    private static final String TAG = "【CS】【AutoSwitch】";
    private static final Object LOCK = new Object();

    private static volatile boolean switching = false;
    private static volatile Runnable customSwitchHandler = null;

    /**
     * If true, CaptureGate.markReady() is dropped automatically after switching.
     *
     * If your switch needs video first frame before capture, set this to false
     * and call CaptureGate.markReady() from your first-frame/render-ready callback.
     */
    private static volatile boolean markReadyImmediately = true;

    private AutoModeSwitch() {}

    public static void setSwitchHandler(Runnable handler) {
        customSwitchHandler = handler;
    }

    public static void setMarkReadyImmediately(boolean value) {
        markReadyImmediately = value;
    }

    public static void onPreCaptureCompleted() {
        synchronized (LOCK) {
            if (switching) {
                return;
            }
            switching = true;
        }

        LogUtil.log(TAG + "pre-capture onCaptureCompleted detected: switching passthrough -> CamSwap");

        try {
            if (customSwitchHandler != null) {
                customSwitchHandler.run();
                LogUtil.log(TAG + "custom switch handler executed");
            } else {
                boolean switched = tryReflectionSwitch();
                if (!switched) {
                    LogUtil.log(TAG + "WARNING: no known passthrough switch API found. "
                            + "Set AutoModeSwitch.setSwitchHandler(...) or edit tryReflectionSwitch().");
                }
            }
        } catch (Throwable t) {
            LogUtil.log(TAG + "switch error: " + t);
        }

        if (markReadyImmediately) {
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        LogUtil.log(TAG + "dropping CaptureGate.markReady()");
                        CaptureGate.markReady();
                    }
                });
            } catch (Throwable t) {
                LogUtil.log(TAG + "direct markReady due handler error: " + t);
                CaptureGate.markReady();
            }
        } else {
            LogUtil.log(TAG + "markReadyImmediately=false, waiting for first-frame markReady()");
        }

        synchronized (LOCK) {
            switching = false;
        }
    }

    /**
     * Tries common CamSwap passthrough/bypass switch APIs by reflection.
     *
     * If your source uses a different method, either:
     *   1. add it here, or
     *   2. call AutoModeSwitch.setSwitchHandler(...)
     */
    private static boolean tryReflectionSwitch() {
        boolean done = false;

        // HookGuards style switches
        done |= callStaticNoArg(
                "io.github.zensu357.camswap.HookGuards",
                new String[]{
                        "enableCamSwap",
                        "disableBypass",
                        "disablePassthrough",
                        "exitPassthrough",
                        "switchToCamSwap",
                        "forceCamSwap"
                }
        );

        done |= callStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                "setForceCamSwap",
                true
        );

        done |= callStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                "setCamSwapEnabled",
                true
        );

        done |= callStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                "setBypassEnabled",
                false
        );

        done |= callStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                "setPassthrough",
                false
        );

        done |= callStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                "setShouldBypass",
                false
        );

        // Field style switches
        done |= setStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                true,
                "forceCamSwap",
                "FORCE_CAMSWAP",
                "camSwapEnabled",
                "enabled",
                "camswapEnabled"
        );

        done |= setStaticBoolean(
                "io.github.zensu357.camswap.HookGuards",
                false,
                "passthrough",
                "bypassEnabled",
                "shouldBypass",
                "bypass",
                "PASS_THROUGH"
        );

        // If current video file is null, try restore last video file
        done |= restoreCurrentVideoFile();

        // HookMain / player restart style switches
        done |= callStaticNoArg(
                "io.github.zensu357.camswap.HookMain",
                new String[]{
                        "enableCamSwap",
                        "disablePassthrough",
                        "restartPlayer",
                        "restartAll"
                }
        );

        done |= callInstanceOnStaticField(
                "io.github.zensu357.camswap.HookMain",
                "playerManager",
                "restartAll"
        );

        done |= callInstanceOnStaticField(
                "io.github.zensu357.camswap.HookMain",
                "camera2Hook",
                "restartAll"
        );

        return done;
    }

    private static boolean callStaticNoArg(String className, String[] methodNames) {
        try {
            Class<?> clazz = Class.forName(className);
            for (String methodName : methodNames) {
                try {
                    Method method = clazz.getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    method.invoke(null);
                    LogUtil.log(TAG + "called " + className + "." + methodName + "()");
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean callStaticBoolean(String className, String methodName, boolean value) {
        try {
            Class<?> clazz = Class.forName(className);
            Method method = clazz.getDeclaredMethod(methodName, boolean.class);
            method.setAccessible(true);
            method.invoke(null, value);
            LogUtil.log(TAG + "called " + className + "." + methodName + "(" + value + ")");
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean setStaticBoolean(String className, boolean value, String... fieldNames) {
        try {
            Class<?> clazz = Class.forName(className);
            for (String fieldName : fieldNames) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);

                    Class<?> type = field.getType();
                    if (type == boolean.class || Boolean.class.isAssignableFrom(type)) {
                        field.set(null, value);
                        LogUtil.log(TAG + "set " + className + "." + fieldName + " = " + value);
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean restoreCurrentVideoFile() {
        try {
            Class<?> clazz = Class.forName("io.github.zensu357.camswap.HookGuards");

            Field currentField = findField(
                    clazz,
                    "currentVideoFile",
                    "mCurrentVideoFile",
                    "sCurrentVideoFile",
                    "videoFile"
            );

            if (currentField == null) {
                return false;
            }

            currentField.setAccessible(true);
            Object current = currentField.get(null);

            if (current != null) {
                return false;
            }

            Field lastField = findField(
                    clazz,
                    "lastVideoFile",
                    "mLastVideoFile",
                    "sLastVideoFile",
                    "previousVideoFile"
            );

            if (lastField == null) {
                return false;
            }

            lastField.setAccessible(true);
            Object last = lastField.get(null);

            if (last == null) {
                return false;
            }

            currentField.set(null, last);
            LogUtil.log(TAG + "restored currentVideoFile = " + last);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean callInstanceOnStaticField(String className, String fieldName, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);

            Object instance = field.get(null);
            if (instance == null) {
                return false;
            }

            Method method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            method.invoke(instance);

            LogUtil.log(TAG + "called " + className + "." + fieldName + "." + methodName + "()");
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
