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

                LogUtil.log(
                    TAG + " Found GeckoView evaluateJS method: "
                        + method.toGenericString()
                );

                /*
                 * Most GeckoView versions expose evaluateJS(String).
                 */
                if (parameterTypes.length == 1
                        && parameterTypes[0] == String.class) {
                    method.setAccessible(true);
                    method.invoke(geckoSession, js);

                    LogUtil.log(
                        TAG + " GeckoView JavaScript injection succeeded"
                    );
                    return;
                }

                /*
                 * Some GeckoView versions expose evaluateJS(String, boolean).
                 */
                if (parameterTypes.length == 2
                        && parameterTypes[0] == String.class
                        && parameterTypes[1] == boolean.class) {
                    method.setAccessible(true);
                    method.invoke(geckoSession, js, false);

                    LogUtil.log(
                        TAG + " GeckoView JavaScript injection succeeded "
                            + "using evaluateJS(String, boolean)"
                    );
                    return;
                }
            }

            LogUtil.log(
                TAG + " No supported GeckoView evaluateJS overload was found"
            );
        } catch (Throwable t) {
            LogUtil.log(
                TAG + " GeckoView JavaScript injection failed: " + t
            );
        }
    }
}
