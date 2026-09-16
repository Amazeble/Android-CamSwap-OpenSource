package io.github.zensu357.camswap;

import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.libxposed.api.XposedInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * WebView/GeckoView Hook Handler - Spoofs WebRTC getUserMedia() permissions
 * 
 * This handler injects JavaScript into WebView/GeckoView instances to mock
 * navigator.mediaDevices.getUserMedia() API, making it always succeed with
 * a fake media stream. This is useful for browsers like Mozilla Firefox
 * (org.mozilla.fenix) that use WebRTC instead of native camera APIs.
 * 
 * Supports both:
 * - Standard Android WebView (android.webkit.WebView)
 * - GeckoView (org.mozilla.geckoview.GeckoView) used by Firefox
 */
public class WebViewHandler implements ICameraHandler {
    
    private static final String TAG = "【CS】[WebView]";
    
    // JavaScript to spoof getUserMedia - injected into all web views
    private static final String SPOOF_GET_USER_MEDIA_JS = 
        "(function() {" +
        "  if (window.navigator.mediaDevices && window.navigator.mediaDevices.getUserMedia) {" +
        "    const originalGetUserMedia = window.navigator.mediaDevices.getUserMedia;" +
        "    window.navigator.mediaDevices.getUserMedia = function(constraints) {" +
        "      console.log('[CamSwap] getUserMedia called with:', JSON.stringify(constraints));" +
        "      // Create a fake success response" +
        "      return new Promise(function(resolve, reject) {" +
        "        console.log('[CamSwap] Spoofing getUserMedia success...');" +
        "        // Try to get the real stream first, if fails, create fake" +
        "        originalGetUserMedia.call(window.navigator.mediaDevices, constraints)" +
        "          .then(function(stream) {" +
        "            console.log('[CamSwap] Real stream obtained'); " +
        "            resolve(stream);" +
        "          })" +
        "          .catch(function(err) {" +
        "            console.log('[CamSwap] Creating fake stream due to:', err);" +
        "            // Create fake MediaStream with fake tracks" +
        "            var fakeStream = new MediaStream();" +
        "            var hasVideo = constraints && constraints.video;" +
        "            var hasAudio = constraints && constraints.audio;" +
        "            if (hasVideo) {" +
        "              var canvas = document.createElement('canvas');" +
        "              canvas.width = 640; canvas.height = 480;" +
        "              var ctx = canvas.getContext('2d');" +
        "              ctx.fillStyle = '#000000';" +
        "              ctx.fillRect(0, 0, canvas.width, canvas.height);" +
        "              ctx.fillStyle = '#00FF00';" +
        "              ctx.font = '20px Arial';" +
        "              ctx.fillText('CamSwap Fake Video', 180, 240);" +
        "              var stream = canvas.captureStream(30);" +
        "              var videoTrack = stream.getVideoTracks()[0];" +
        "              if (videoTrack) fakeStream.addTrack(videoTrack);" +
        "            }" +
        "            if (hasAudio) {" +
        "              var audioContext = new (window.AudioContext || window.webkitAudioContext)();" +
        "              var oscillator = audioContext.createOscillator();" +
        "              var gainNode = audioContext.createGain();" +
        "              gainNode.gain.value = 0;" +
        "              oscillator.connect(gainNode);" +
        "              gainNode.connect(audioContext.destination);" +
        "              oscillator.start();" +
        "              var dest = audioContext.createMediaStreamDestination();" +
        "              gainNode.connect(dest);" +
        "              var audioTrack = dest.stream.getAudioTracks()[0];" +
        "              if (audioTrack) fakeStream.addTrack(audioTrack);" +
        "            }" +
        "            resolve(fakeStream);" +
        "          });" +
        "      });" +
        "    };" +
        "    console.log('[CamSwap] getUserMedia spoofed successfully');" +
        "  }" +
        "})();" ;

    @Override
    public void init(final Api101PackageContext packageContext) {
        final ClassLoader classLoader = packageContext.classLoader;
        final String packageName = packageContext.packageName;
        
        LogUtil.log(TAG + " 初始化 WebView Hook for: " + packageName);
        
        // Hook standard Android WebView
        hookWebViewLoadUrl(classLoader, packageName);
        hookWebViewAddJavascriptInterface(classLoader, packageName);
        hookWebChromeClientOnPermissionRequest(classLoader, packageName);
        
        // Hook GeckoView (used by Firefox)
        hookGeckoView(classLoader, packageName);
        
        LogUtil.log(TAG + " WebView Hook 初始化完成");
    }
    
    /**
     * Hook WebView.loadUrl() to inject spoofing JavaScript after page load
     */
    private void hookWebViewLoadUrl(ClassLoader classLoader, String packageName) {
        try {
            Method loadUrlMethod = classLoader.loadClass("android.webkit.WebView")
                .getDeclaredMethod("loadUrl", String.class);
            
            Api101Runtime.requireModule().hook(loadUrlMethod).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                Object result = chain.proceed(args);
                
                try {
                    if (chain.getThisObject() instanceof WebView) {
                        WebView webView = (WebView) chain.getThisObject();
                        injectJavaScript(webView, packageName);
                    }
                } catch (Throwable t) {
                    LogUtil.log(TAG + " loadUrl hook 异常：" + t);
                }
                
                return result;
            });
            
            LogUtil.log(TAG + " Hooked WebView.loadUrl()");
        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to hook WebView.loadUrl(): " + t);
        }
    }
    
    /**
     * Hook WebView.evaluateJavascript() to ensure our script runs
     */
    private void hookWebViewEvaluateJavascript(ClassLoader classLoader, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        
        try {
            Method evalMethod = classLoader.loadClass("android.webkit.WebView")
                .getDeclaredMethod("evaluateJavascript", String.class, 
                    classLoader.loadClass("android.webkit.ValueCallback"));
            
            Api101Runtime.requireModule().hook(evalMethod).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                return chain.proceed(args);
            });
            
            LogUtil.log(TAG + " Hooked WebView.evaluateJavascript()");
        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to hook WebView.evaluateJavascript(): " + t);
        }
    }
    
    /**
     * Hook WebChromeClient.onPermissionRequest() to auto-grant camera/microphone permissions
     */
    private void hookWebChromeClientOnPermissionRequest(ClassLoader classLoader, String packageName) {
        try {
            Class<?> webChromeClientClass = classLoader.loadClass("android.webkit.WebChromeClient");
            Class<?> permissionRequestClass = classLoader.loadClass("android.webkit.PermissionRequest");
            
            Method onPermissionRequestMethod = webChromeClientClass.getDeclaredMethod(
                "onPermissionRequest", permissionRequestClass);
            
            Api101Runtime.requireModule().hook(onPermissionRequestMethod).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                
                try {
                    if (args[0] != null) {
                        PermissionRequest request = (PermissionRequest) args[0];
                        String[] resources = request.getResources();
                        
                        LogUtil.log(TAG + " PermissionRequest received: " + Arrays.toString(resources));
                        
                        // Check if requesting camera or microphone
                        boolean needsCamera = false;
                        boolean needsMic = false;
                        for (String resource : resources) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                needsCamera = true;
                            }
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                                needsMic = true;
                            }
                        }
                        
                        if (needsCamera || needsMic) {
                            // Auto-grant permissions
                            String[] grantedResources = resources; // Grant all requested
                            request.grant(grantedResources);
                            LogUtil.log(TAG + " Auto-granted permissions: " + Arrays.toString(grantedResources));
                            return null; // Don't call original method
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log(TAG + " onPermissionRequest hook 异常：" + t);
                }
                
                return chain.proceed(args);
            });
            
            LogUtil.log(TAG + " Hooked WebChromeClient.onPermissionRequest()");
        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to hook WebChromeClient.onPermissionRequest(): " + t);
        }
    }
    
    /**
     * Inject JavaScript into WebView to spoof getUserMedia
     */
    private void injectJavaScript(WebView webView, String packageName) {
        if (webView == null) {
            return;
        }
        
        // Enable JavaScript
        try {
            android.webkit.WebSettings settings = webView.getSettings();
            if (settings != null) {
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
            }
        } catch (Exception e) {
            LogUtil.log(TAG + " Failed to enable JavaScript: " + e);
        }
        
        // Set a custom WebChromeClient to handle permission requests
        try {
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(PermissionRequest request) {
                    String[] resources = request.getResources();
                    LogUtil.log(TAG + " Custom WebChromeClient got PermissionRequest: " + 
                        Arrays.toString(resources));
                    
                    // Auto-grant all requested resources
                    request.grant(resources);
                }
            });
        } catch (Exception e) {
            LogUtil.log(TAG + " Failed to set WebChromeClient: " + e);
        }
        
        // Inject the spoofing JavaScript
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                webView.evaluateJavascript(SPOOF_GET_USER_MEDIA_JS, null);
                LogUtil.log(TAG + " Injected getUserMedia spoof script into: " + packageName);
            } catch (Exception e) {
                LogUtil.log(TAG + " Failed to evaluateJavascript: " + e);
            }
        } else {
            // For older versions, use loadUrl with javascript: protocol
            try {
                webView.loadUrl("javascript:" + SPOOF_GET_USER_MEDIA_JS);
                LogUtil.log(TAG + " Injected getUserMedia spoof script (legacy) into: " + packageName);
            } catch (Exception e) {
                LogUtil.log(TAG + " Failed to loadUrl javascript: " + e);
            }
        }
    }
    
    /**
     * Hook WebView.addJavascriptInterface() - not used but logged
     */
    private void hookWebViewAddJavascriptInterface(ClassLoader classLoader, String packageName) {
        try {
            Method method = classLoader.loadClass("android.webkit.WebView")
                .getDeclaredMethod("addJavascriptInterface", Object.class, String.class);
            
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                LogUtil.log(TAG + " addJavascriptInterface called: " + args[1]);
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            // Ignore - not critical
        }
    }
    
    /**
     * Hook GeckoView (used by Firefox) to inject JavaScript for getUserMedia spoofing
     */
    private void hookGeckoView(ClassLoader classLoader, String packageName) {
        try {
            // Try to load GeckoView classes
            Class<?> geckoViewClass = null;
            Class<?> geckoSessionClass = null;
            
            try {
                geckoViewClass = classLoader.loadClass("org.mozilla.geckoview.GeckoView");
                geckoSessionClass = classLoader.loadClass("org.mozilla.geckoview.GeckoSession");
            } catch (ClassNotFoundException e) {
                LogUtil.log(TAG + " GeckoView not found in this app, skipping GeckoView hooks");
                return;
            }
            
            LogUtil.log(TAG + " Found GeckoView classes, setting up hooks for: " + packageName);
            
            // Hook GeckoSession.setContentDelegate to inject JS when page loads
            try {
                Class<?> contentDelegateClass = classLoader.loadClass("org.mozilla.geckoview.GeckoSession$ContentDelegate");
                
                // We'll inject JS via ProgressDelegate which is called on page load
                Class<?> progressDelegateClass = classLoader.loadClass("org.mozilla.geckoview.GeckoSession$ProgressDelegate");
                
                // Hook GeckoSession.setProgressDelegate
                Method setProgressDelegateMethod = geckoSessionClass.getDeclaredMethod(
                    "setProgressDelegate", progressDelegateClass);
                
                Api101Runtime.requireModule().hook(setProgressDelegateMethod).intercept(chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    
                    // Wrap or replace the progress delegate to inject our JS
                    if (args[0] != null) {
                        Object originalDelegate = args[0];
                        args[0] = createGeckoProgressDelegateWrapper(
                            classLoader, originalDelegate, packageName);
                    }
                    
                    return chain.proceed(args);
                });
                
                LogUtil.log(TAG + " Hooked GeckoSession.setProgressDelegate()");
            } catch (Exception e) {
                LogUtil.log(TAG + " Failed to hook GeckoSession.setProgressDelegate: " + e);
            }
            
            // Also try to hook onPageStart directly if possible
            try {
                Class<?> progressDelegateClass = classLoader.loadClass("org.mozilla.geckoview.GeckoSession$ProgressDelegate");
                
                // Find onPageStart method
                Method onPageStartMethod = null;
                for (Method m : progressDelegateClass.getDeclaredMethods()) {
                    if ("onPageStart".equals(m.getName())) {
                        onPageStartMethod = m;
                        break;
                    }
                }
                
                if (onPageStartMethod != null) {
                    Api101Runtime.requireModule().hook(onPageStartMethod).intercept(chain -> {
                        Object[] args = toArgs(chain.getArgs());
                        
                        // Call original first
                        Object result = chain.proceed(args);
                        
                        // Then inject our JS
                        try {
                            GeckoSessionHelper.injectJavaScript(args[0], SPOOF_GET_USER_MEDIA_JS);
                            LogUtil.log(TAG + " Injected getUserMedia spoof into GeckoView");
                        } catch (Exception e) {
                            LogUtil.log(TAG + " Failed to inject JS into GeckoView: " + e);
                        }
                        
                        return result;
                    });
                    
                    LogUtil.log(TAG + " Hooked GeckoSession.ProgressDelegate.onPageStart()");
                }
            } catch (Exception e) {
                LogUtil.log(TAG + " Failed to hook onPageStart: " + e);
            }
            
        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to setup GeckoView hooks: " + t);
        }
    }
    
    /**
     * Helper class for GeckoView JavaScript injection
     */
    private static class GeckoSessionHelper {
        static void injectJavaScript(Object geckoSession, String js) {
            if (geckoSession == null) return;
            
            try {
                // Use evaluateJS method if available (GeckoView 89+)
                Class<?> sessionClass = geckoSession.getClass();
                try {
                    Method evalMethod = sessionClass.getMethod("evaluateJS", String.class);
                    evalMethod.invoke(geckoSession, js);
                    return;
                } catch (NoSuchMethodException e) {
                    // Try older API
                }
                
                // For older GeckoView versions, we may need to use a different approach
                // This is a fallback - in practice, most modern Firefox versions support evaluateJS
                LogUtil.log(TAG + " GeckoView evaluateJS method not found");
            } catch (Exception e) {
                LogUtil.log(TAG + " Failed to inject JS into GeckoSession: " + e);
            }
        }
    }
    
    /**
     * Create a wrapper for GeckoView ProgressDelegate to inject JavaScript on page load
     */
    private Object createGeckoProgressDelegateWrapper(ClassLoader classLoader, 
                                                       Object originalDelegate, 
                                                       String packageName) {
        // For simplicity, we'll just return the original delegate
        // The actual injection happens in the hooked onPageStart method above
        return originalDelegate;
    }
}
