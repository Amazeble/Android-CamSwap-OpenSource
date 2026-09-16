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

                    LogUtil.log(
                        TAG + " Injected spoof script during onPageStart"
                    );
                } catch (Throwable t) {
                    LogUtil.log(
                        TAG + " onPageStart injection failed: " + t
                    );
                }

                /*
                 * Inject again after page scripts have initialized.
                 */
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);

                        GeckoSessionHelper.injectJavaScript(
                            geckoSession,
                            SPOOF_GET_USER_MEDIA_JS
                        );

                        LogUtil.log(
                            TAG + " Re-injected spoof script after page load"
                        );
                    } catch (Throwable t) {
                        LogUtil.log(
                            TAG + " Delayed GeckoView injection failed: " + t
                        );
                    }
                }).start();
            }

            return result;
        });

    LogUtil.log(
        TAG + " Hooked GeckoSession.ProgressDelegate.onPageStart()"
    );
}
