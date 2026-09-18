package io.github.zensu357.camswap.utils;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.os.Bundle;

import io.github.zensu357.camswap.ConfigManager;
import io.github.zensu357.camswap.IpcContract;
import io.github.zensu357.camswap.MediaSourceDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoManager {
    public static String video_path = "/storage/emulated/0/DCIM/Camera1/";
    public static String current_video_path = null;
    public static final String CAM_VIDEO_NAME = "Cam.mp4";
    private static final Object pathLock = new Object();
    private static boolean providerAvailable = false;
    private static final AtomicBoolean providerBackedVideo = new AtomicBoolean(false);
    private static Context toast_content;
    private static ConfigManager configManager;
    private static long lastPfdFailLogMs = 0L;
    private static long lastPfdSuccessLogMs = 0L;

    /** Supported video file extensions */
    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mov", ".avi", ".mkv" };

    /**
     * List video files in a directory, sorted by name.
     * 
     * @return sorted array of video files, or null if none found
     */
    public static File[] listVideoFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory())
            return null;
        File[] files = dir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext))
                    return true;
            }
            return false;
        });
        if (files != null && files.length > 0) {
            Arrays.sort(files);
            return files;
        }
        return null;
    }

    public static void showToast(final String message) {
        if (toast_content != null) {
            PermissionHelper.showToast(toast_content, message);
        }
    }

    public static void setContext(Context context) {
        toast_content = context;
        if (configManager != null) {
            configManager.setContext(context);
        }
    }

    public static void setConfigManager(ConfigManager manager) {
        configManager = manager;
    }

    public static ConfigManager getConfig() {
        if (configManager == null) {
            configManager = new ConfigManager();
            if (toast_content != null) {
                configManager.setContext(toast_content);
            }
        }
        // Removed auto-reload to avoid performance issues (IPC on every call).
        // Reloading should be handled by ContentObserver or explicit calls.
        return configManager;
    }

    public static ParcelFileDescriptor getVideoPFD() {
        if (toast_content == null) {
            log("【CS】getVideoPFD: toast_content is null, skip");
            return null;
        }

        // 1. First check if we have a cached private video file in app's private filesDir
        File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");
        if (privateFile.exists() && privateFile.length() > 0 && privateFile.canRead()) {
            try {
                return ParcelFileDescriptor.open(privateFile, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception e) {
                log("【CS】[Private] Failed to open private video Fd: " + e);
            }
        }

        // 2. Query ContentProvider directly (zero-copy native streaming descriptor)
        try {
            ParcelFileDescriptor pfd = toast_content.getContentResolver().openFileDescriptor(IpcContract.URI_VIDEO, "r");
            if (pfd != null) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastPfdSuccessLogMs >= 5000L) {
                    lastPfdSuccessLogMs = now;
                    log("【CS】getVideoPFD: ContentProvider 获取成功");
                }
                return pfd;
            } else {
                log("【CS】getVideoPFD: ContentProvider returned null");
            }
        } catch (Exception e) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastPfdFailLogMs >= 5000L) {
                lastPfdFailLogMs = now;
                log("【CS】getVideoPFD failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 3. Fallback: try opening direct file if permission allows
        try {
            String path = getCurrentVideoPath();
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.canRead()) {
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static void copyToPrivateDir(ParcelFileDescriptor pfd) {
        if (toast_content == null)
            return;
        File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");

        try {
            long size = pfd.getStatSize();
            if (privateFile.exists() && privateFile.length() == size) {
                log("【CS】[Private] file size consistent, skipping copy (" + size + " bytes)");
                return;
            }

            log("【CS】[Private] Starting to copy video to private directory (" + size + " bytes)...");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }
            log("【CS】[Private] Video copy complete (" + size + " bytes)");
        } catch (Exception e) {
            log("【CS】[Private] Video copy failed: " + e);
        }
    }

    /**
     * 通过 ContentProvider 获取音频文件的 PFD。
     * 使用统一的 Provider audio 路径。
     */
    public static ParcelFileDescriptor getAudioPFD() {
        if (toast_content == null) {
            return null;
        }
        try {
            ParcelFileDescriptor pfd = toast_content.getContentResolver().openFileDescriptor(IpcContract.URI_AUDIO, "r");
            if (pfd != null) {
                log("【CS】getAudioPFD: 成功获取音频 PFD");
            }
            return pfd;
        } catch (Exception e) {
            log("【CS】getAudioPFD failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * 将音频文件从 Provider 拷贝到 app 私有目录。
     * @return 拷贝后的私有目录音频文件路径，failedreturned null
     */
    public static String copyAudioToPrivateDir() {
        if (toast_content == null) return null;

        String selectedAudio = getConfig().getString(
                ConfigManager.KEY_SELECTED_AUDIO, null);
        if (selectedAudio == null || selectedAudio.isEmpty()) {
            log("【CS】[Private] no selected audio file, skipping audio copy");
            return null;
        }

        // 使用固定的私有文件名避免特殊字符问题
        File privateAudio = new File(toast_content.getFilesDir(), "vcam_private_audio");

        ParcelFileDescriptor pfd = null;
        try {
            pfd = getAudioPFD();
            if (pfd == null) {
                log("【CS】[Private] 无法获取音频 PFD");
                return null;
            }

            long size = pfd.getStatSize();
            if (privateAudio.exists() && privateAudio.length() == size) {
                log("【CS】[Private] 音频file size consistent, skipping copy (" + size + " bytes)");
                return privateAudio.getAbsolutePath();
            }

            log("【CS】[Private] starting to copy audio to private directory (" + size + " bytes)...");
            java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(privateAudio);

            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.close();
            fis.close();
            log("【CS】[Private] 音频拷贝完成 (" + size + " bytes)");
            return privateAudio.getAbsolutePath();
        } catch (Exception e) {
            log("【CS】[Private] 音频拷贝failed: " + e);
            return null;
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static void checkProviderAvailability() {
        if (toast_content == null) {
            providerAvailable = false;
            return;
        }
        try {
            android.net.Uri uri = IpcContract.URI_CONFIG;
            String type = toast_content.getContentResolver().getType(uri);
            if (type != null) {
                providerAvailable = true;
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            ParcelFileDescriptor pfd = toast_content.getContentResolver().openFileDescriptor(IpcContract.URI_VIDEO, "r");
            if (pfd != null) {
                providerAvailable = true;
                pfd.close();
                return;
            }
        } catch (Exception ignored) {
        }
        providerAvailable = false;
    }

    public static boolean isProviderAvailable() {
        return providerAvailable;
    }

    public static boolean isUsingProviderBackedVideo() {
        return providerBackedVideo.get();
    }

    // Use LogUtil instead of direct XposedBridge to avoid crash in non-Xposed
    // process
    private static void log(String msg) {
        try {
            LogUtil.log(msg);
        } catch (Throwable e) {
            // Fallback to standard android log if Xposed bridge is not available
            android.util.Log.i("LSPosed-Bridge", msg);
        }
    }

    public static void updateVideoPath(boolean forceRandom) {
        synchronized (pathLock) {
            ConfigManager config = getConfig();

            if (config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false) && toast_content != null) {
                File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");

                // Try from provider implicitly if needed
                try {
                    ParcelFileDescriptor providerPfd = toast_content.getContentResolver().openFileDescriptor(IpcContract.URI_VIDEO, "r");
                    if (providerPfd != null) {
                        copyToPrivateDir(providerPfd);
                        try {
                            providerPfd.close();
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e) {
                    // Provider might not be available
                }

                if (privateFile.exists()) {
                    current_video_path = privateFile.getAbsolutePath();
                    providerBackedVideo.set(false);
                    log("【CS】[Private] Using private directory video: " + current_video_path);
                    return;
                }
            }

            if (toast_content != null) {
                // Try to trigger random update via provider first if needed
                if (forceRandom && config.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
                    try {
                        toast_content.getContentResolver().call(IpcContract.CONTENT_URI,
                                IpcContract.METHOD_RANDOM, null, null);
                    } catch (Exception e) {
                        // log("【CS】Provider random failed: " + e);
                    }
                }

                checkProviderAvailability();
                if (providerAvailable) {
                    providerBackedVideo.set(true);
                    current_video_path = null;
                    return;
                }
            }

            providerBackedVideo.set(false);

            File camFile = new File(video_path, CAM_VIDEO_NAME);

            // 1. Random play mode: randomly select a video filename to store in config (no longer renaming files)
            if (config.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
                if (forceRandom) {
                    pickRandomVideoToConfig(config);
                }
                // Using selected video from config
                String randomSelected = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
                if (randomSelected != null) {
                    File randomFile = new File(video_path, randomSelected);
                    if (randomFile.exists()) {
                        current_video_path = randomFile.getAbsolutePath();
                        log("【CS】[Random] 使用: " + current_video_path);
                        return;
                    }
                }
                // Fallback: try Cam.mp4, then try any video in directory
                current_video_path = findFallbackVideo(camFile);
                return;
            }

            // 2. 普通模式：优先Using selected video from config
            String selectedName = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
            if (selectedName != null && !selectedName.isEmpty()) {
                File selectedFile = new File(video_path, selectedName);
                if (selectedFile.exists()) {
                    current_video_path = selectedFile.getAbsolutePath();
                    log("【CS】[Video] using config path: " + current_video_path);
                    return;
                }
                log("【CS】[Video] Configured video does not exist: " + selectedName);
            }

            // 3. Fallback: Cam.mp4 → any video in directory
            current_video_path = findFallbackVideo(camFile);
        }
    }

    /**
     * Fallback video search: first try Cam.mp4, then scan any video file in directory.
     * Ensure that as long as there is video in directory it can be found.
     */
    private static String findFallbackVideo(File camFile) {
        // 尝试 Cam.mp4
        if (camFile.exists() && camFile.canRead()) {
            log("【CS】[Video] 使用默认路径: " + camFile.getAbsolutePath());
            return camFile.getAbsolutePath();
        }

        // Scanning any video in directory
        File[] files = listVideoFiles(new File(video_path));
        if (files != null && files.length > 0 && files[0].canRead()) {
            log("【CS】[Video] Auto-selecting video in directory: " + files[0].getName());
            return files[0].getAbsolutePath();
        }

        // Checking app private cached video
        if (toast_content != null) {
            File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");
            if (privateFile.exists() && privateFile.length() > 0) {
                log("【CS】[Video] Using private cached video: " + privateFile.getAbsolutePath());
                return privateFile.getAbsolutePath();
            }
        }

        // 无可用video，仍返回 Cam.mp4 路径（后续解码器会处理文件不存在的情况）
        log("【CS】[Video] Warning: No available video files in directory or no read permission, waiting for Provider/Binder delivery...");
        return camFile.getAbsolutePath();
    }

    /**
     * Randomly select video filename and store in config (no longer renaming files)
     */
    private static void pickRandomVideoToConfig(ConfigManager config) {
        File dir = new File(video_path);
        if (!dir.exists() || !dir.isDirectory()) {
            log("【CS】[Random] Video directory does not exist");
            return;
        }

        File[] files = listVideoFiles(dir);

        if (files != null && files.length > 0) {
            int index = ThreadLocalRandom.current().nextInt(files.length);
            File selectedFile = files[index];
            config.setString(ConfigManager.KEY_SELECTED_VIDEO, selectedFile.getName());
            log("【CS】[Random] 选择了: " + selectedFile.getName());
        } else {
            log("【CS】[Random] No available video files");
        }
    }

    public static String getCurrentVideoPath() {
        synchronized (pathLock) {
            if (current_video_path == null) {
                updateVideoPath(false);
            }
            if (providerBackedVideo.get()) {
                return null;
            }
            return current_video_path;
        }
    }

    public static boolean switchVideo(boolean next) {
        if (toast_content != null) {
            try {
                Bundle res = toast_content.getContentResolver().call(
                        IpcContract.CONTENT_URI,
                        next ? IpcContract.METHOD_NEXT : IpcContract.METHOD_PREV, null, null);
                if (res != null && res.getBoolean(IpcContract.EXTRA_CHANGED)) {
                    return true;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Unknown authority") || msg.contains("No content provider"))) {
                    // Expected when provider is not visible
                } else {
                    log("【CS】Provider switch failed: " + e);
                }
            }
        }

        if (providerAvailable) {
            log("【CS】Provider call failed but provider is available. Skipping fallback.");
            showToast("Provider调用failed，无法切换video");
            return false;
        }

        File dir = new File(video_path);
        File[] files = listVideoFiles(dir);

        if (files == null || files.length == 0)
            return false;

        int currentIndex = -1;
        if (current_video_path != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].getAbsolutePath().equals(current_video_path)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int newIndex;
        if (currentIndex == -1) {
            newIndex = 0;
        } else {
            if (next) {
                newIndex = (currentIndex + 1) % files.length;
            } else {
                newIndex = (currentIndex - 1 + files.length) % files.length;
            }
        }

        // Use performVideoSelection to handle renaming and config update
        performVideoSelection(files[newIndex].getName());
        return true;
    }

    /**
     * Execute video selection logic：
     * 仅更新配置中的选中videoname和current path（不再重命名文件）
     */
    public static boolean performVideoSelection(String targetFileName) {
        synchronized (pathLock) {
            ConfigManager config = getConfig();
            File dir = new File(video_path);
            File targetFile = new File(dir, targetFileName);

            if (targetFile.exists()) {
                config.setString(ConfigManager.KEY_SELECTED_VIDEO, targetFileName);
                current_video_path = targetFile.getAbsolutePath();
                log("【CS】Selected: " + targetFileName);
                return true;
            } else {
                log("【CS】Target file not found: " + targetFileName);
                return false;
            }
        }
    }

    // =====================================================================
    // Stream / unified media source support
    // =====================================================================

    /** Whether current config is set to stream mode. */
    public static boolean isStreamMode() {
        ConfigManager config = getConfig();
        String type = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        return ConfigManager.MEDIA_SOURCE_STREAM.equals(type);
    }

    /** Whether there is a usable media source (local file or stream URL). */
    public static boolean hasUsableMediaSource() {
        return getCurrentMediaSource().isValid();
    }

    /**
     * Build a {@link MediaSourceDescriptor} from current config state.
     * Local mode: uses existing video path logic.
     * Stream mode: uses stream_url; falls back to local if URL empty and fallback enabled.
     */
    public static MediaSourceDescriptor getCurrentMediaSource() {
        ConfigManager config = getConfig();
        String sourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);

        if (ConfigManager.MEDIA_SOURCE_STREAM.equals(sourceType)) {
            String streamUrl = config.getString(ConfigManager.KEY_STREAM_URL, "");
            boolean autoReconnect = config.getBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, true);
            boolean localFallback = config.getBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, true);
            String transportHint = config.getString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, "auto");
            long timeoutMs = config.getLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, 8000L);

            if (streamUrl != null && !streamUrl.isEmpty()) {
                return MediaSourceDescriptor.stream(streamUrl)
                        .autoReconnect(autoReconnect)
                        .enableLocalFallback(localFallback)
                        .transportHint(transportHint)
                        .timeoutMs(timeoutMs)
                        .build();
            }

            // Stream URL is empty — fall back to local if enabled
            if (localFallback) {
                log("【CS】Stream address empty, falling back to local video");
                return buildLocalDescriptor();
            }

            // No fallback: return an invalid stream descriptor
            return MediaSourceDescriptor.stream("").build();
        }

        return buildLocalDescriptor();
    }

    private static MediaSourceDescriptor buildLocalDescriptor() {
        String path = getCurrentVideoPath();
        boolean useProvider = isUsingProviderBackedVideo();
        return MediaSourceDescriptor.localFile(path != null ? path : "")
                .useProviderPfd(useProvider)
                .build();
    }
}
