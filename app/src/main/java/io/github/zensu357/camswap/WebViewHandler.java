package io.github.zensu357.camswap;

import java.lang.reflect.Method;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.LogUtil;

public class WebViewHandler {
    private static final String TAG = "CamSwap-WebView";

    private static final String SPOOF_GET_USER_MEDIA_JS =
        "(function() {" +
        "  window.__CAMSWAP_INJECTED__ = true;" +
        "  console.log('[CamSwap] SCRIPT INJECTED INTO GECKOVIEW');" +
        "  if (!window.navigator.mediaDevices) { window.navigator.mediaDevices = {}; }" +
        "  if (!window.navigator.mediaDevices.getUserMedia) {" +
        "    window.navigator.mediaDevices.getUserMedia = function(constraints) {" +
        "      return Promise.resolve({" +
        "        getVideoTracks: function() { return []; }," +
        "        getAudioTracks: function() { return []; }," +
        "        getTracks: function() { return []; }" +
        "      });" +
        "    };" +
        "  }" +
        "  Object.defineProperty(window.navigator, 'mediaDevices', {" +
        "    value: window.navigator.mediaDevices," +
        "    configurable: true" +
        "  });" +
        "})();";

    public void init(Api101PackageContext packageContext) {
        if (packageContext == null || packageContext.classLoader == null) {
            return;
        }
        hookGeckoView(packageContext.classLoader);
    }

    private void hookGeckoView(ClassLoader classLoader) {
        try {
            Class<?> progressDelegateClass =
                Class.forName("org.mozilla.geckoview.GeckoSession$ProgressDelegate", false, classLoader);

            Method onPageStartMethod = null;

            for (Method method : progressDelegateClass.getMethods()) {
                if ("onPageStart".equals(method.getName())) {
                    onPageStartMethod = method;
                    break;
                }
            }

            if (onPageStartMethod == null) {
                LogUtil.log(TAG + " GeckoSession.ProgressDelegate.onPageStart() not found");
                return;
            }

            if (onPageStartMethod != null) {
                Api101Runtime.requireModule()
                    .hook(onPageStartMethod)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray(new Object[0]);

                        Object result = chain.proceed(args);

                        if (args.length > 0 && args[0] != null) {
                            Object geckoSession = args[0];

                            try {
                                GeckoSessionHelper.injectJavaScript(
                                    geckoSession,
                                    SPOOF_GET_USER_MEDIA_JS
                                );
                                LogUtil.log(TAG + " Injected spoof script during onPageStart");
                            } catch (Throwable t) {
                                LogUtil.log(TAG + " onPageStart injection failed: " + t);
                            }

                            new Thread(() -> {
                                try {
                                    Thread.sleep(1500);

                                    GeckoSessionHelper.injectJavaScript(
                                        geckoSession,
                                        SPOOF_GET_USER_MEDIA_JS
                                    );
                                    LogUtil.log(TAG + " Re-injected spoof script after page load");
                                } catch (Throwable t) {
                                    LogUtil.log(TAG + " Delayed GeckoView injection failed: " + t);
                                }
                            }, "CS-GeckoViewInjection").start();
                        }

                        return result;
                    });

                LogUtil.log(TAG + " Hooked GeckoSession.ProgressDelegate.onPageStart()");
            }

        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to hook GeckoView: " + t);
        }
    }

    private static class GeckoSessionHelper {
        static void injectJavaScript(Object geckoSession, String js) {
            if (geckoSession == null || js == null) {
                LogUtil.log(TAG + " GeckoSession or JavaScript is null");
                return;
            }

            try {
                Class<?> sessionClass = geckoSession.getClass();
                Method[] methods = sessionClass.getMethods();

                for (Method method : methods) {
                    if (!"evaluateJS".equals(method.getName())) {
                        continue;
                    }

                    Class<?>[] parameterTypes = method.getParameterTypes();

                    if (parameterTypes.length == 1
                        && parameterTypes[0] == String.class) {
                        method.setAccessible(true);
                        method.invoke(geckoSession, js);
                        LogUtil.log(TAG + " GeckoView JavaScript injection succeeded");
                        return;
                    }

                    if (parameterTypes.length == 2
                        && parameterTypes[0] == String.class
                        && parameterTypes[1] == boolean.class) {
                        method.setAccessible(true);
                        method.invoke(geckoSession, js, false);
                        LogUtil.log(TAG + " GeckoView JavaScript injection succeeded using evaluateJS(String, boolean)");
                        return;
                    }
                }

                LogUtil.log(TAG + " No supported GeckoView evaluateJS overload was found");
            } catch (Throwable t) {
                LogUtil.log(TAG + " GeckoView JavaScript injection failed: " + t);
            }
        }
    }
}
