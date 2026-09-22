#!/usr/bin/env python3
"""Rewire JPEG capture to ImageWriter pump, gated on KEY_ENABLE_PHOTO_FAKE."""
import os, re, sys

def find_file(name):
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in {".git","build",".gradle","_filtered_out"}]
        if name in files:
            return os.path.join(root, name)
    return None

def match_brace(s, open_idx):
    depth, i, n = 0, open_idx, len(s)
    in_str=in_char=in_line=in_block=esc=False
    while i < n:
        c=s[i]; nxt=s[i+1] if i+1<n else ""
        if in_line:
            if c=="\n": in_line=False
        elif in_block:
            if c=="*" and nxt=="/": in_block=False; i+=1
        elif in_str:
            if esc: esc=False
            elif c=="\\": esc=True
            elif c=='"': in_str=False
        elif in_char:
            if esc: esc=False
            elif c=="\\": esc=True
            elif c=="'": in_char=False
        else:
            if c=="/" and nxt=="/": in_line=True; i+=1
            elif c=="/" and nxt=="*": in_block=True; i+=1
            elif c=='"': in_str=True
            elif c=="'": in_char=True
            elif c=="{": depth+=1
            elif c=="}":
                depth-=1
                if depth==0: return i
        i+=1
    return -1

hookmain = find_file("HookMain.java")
sesshook = find_file("Camera2SessionHook.java")
if not hookmain or not sesshook:
    sys.exit("HookMain.java or Camera2SessionHook.java not found")

# ───────────────────────── 1. HookMain.java: rewire maybeReplaceJpegImage ─────────────────────────
src = open(hookmain, encoding="utf-8").read()
m = re.search(r"private\s+Object\s+maybeReplaceJpegImage\s*\(", src)
if not m:
    sys.exit("maybeReplaceJpegImage not found")
brace = src.find("{", m.end()); end = match_brace(src, brace)
if end == -1: sys.exit("brace match failed (HookMain)")

new_method = '''private Object maybeReplaceJpegImage(ImageReader imageReader, Surface surface, Object result) {
        if (!(result instanceof Image)) {
            return result;
        }
        try {
            Image image = (Image) result;
            boolean isJpegTarget = image.getFormat() == android.graphics.ImageFormat.JPEG
                    || camera2Hook.isJpegReaderSurface(surface);
            if (!isJpegTarget) {
                return result;
            }
            // ===== KEY_ENABLE_PHOTO_FAKE master gate =====
            if (!VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)) {
                return result; // toggle OFF -> real JPEG passes through untouched
            }
            // ===== vcam-style pump: clean fake JPEG Image, drain real HAL frame =====
            Image fake = camera2Hook.acquireFakeJpegImage(imageReader, surface);
            if (fake != null) {
                try { image.close(); } catch (Throwable ignored) {}
                return fake;
            }
            // fallback: in-place overwrite if the bridge failed
            camera2Hook.replaceJpegImageIfNeeded(imageReader, image);
        } catch (Exception e) {
            LogUtil.log("【CS】处理 ImageReader 结果失败: " + e);
        }
        return result;
    }'''

src = src[:m.start()] + new_method + src[end+1:]
open(hookmain, "w", encoding="utf-8").write(src)
print("[OK] HookMain.java: maybeReplaceJpegImage rewired (gate + pump + dump removed)")

# ─────────────── 2. Camera2SessionHook.java: add JPEG bridge + cleanup ───────────────
src2 = open(sesshook, encoding="utf-8").read()
if "acquireFakeJpegImage" not in src2:
    anchor = re.search(r"public\s+boolean\s+replaceJpegImageIfNeeded\s*\(", src2)
    if not anchor:
        sys.exit("replaceJpegImageIfNeeded not found")
    bridge = '''// ===== JPEG bridge fields (vcam-style ImageWriter pump) =====
    private ImageReader jpegBridgeReader;
    private ImageWriter jpegBridgeWriter;

    public Image acquireFakeJpegImage(ImageReader appReader, Surface appSurface) {
        Image input = null;
        try {
            int width = appReader.getWidth();
            int height = appReader.getHeight();
            if (width <= 0 || height <= 0) return null;
            if (jpegBridgeReader == null
                    || jpegBridgeReader.getWidth() != width
                    || jpegBridgeReader.getHeight() != height) {
                closeJpegBridge();
                jpegBridgeReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
                jpegBridgeWriter = ImageWriter.newInstance(jpegBridgeReader.getSurface(), 2);
            }
            input = jpegBridgeWriter.dequeueInputImage();
            if (input == null) return null;
            ByteBuffer buf = input.getPlanes()[0].getBuffer();
            byte[] jpegBytes = createFakeJpegBytes(appSurface, buf.capacity());
            if (jpegBytes == null || jpegBytes.length == 0) {
                try { input.close(); } catch (Throwable ignored) {}
                return null;
            }
            buf.clear();
            buf.put(jpegBytes);
            buf.flip();
            input.setTimestamp(getNextMonotonicPtsNs());
            jpegBridgeWriter.queueInputImage(input);
            input = null; // ownership transferred to writer
            pendingJpegSurfaces.remove(appSurface);
            pendingPhotoSurface = null;
            LogUtil.log("【CS】成功以 ImageWriter 泵入 JPEG 伪帧: 大小=" + jpegBytes.length + " 字节");
            return jpegBridgeReader.acquireNextImage();
        } catch (Exception e) {
            LogUtil.log("【CS】JPEG 伪帧桥接失败: " + e);
            if (input != null) { try { input.close(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    private void closeJpegBridge() {
        try { if (jpegBridgeWriter != null) jpegBridgeWriter.close(); } catch (Throwable ignored) {}
        try { if (jpegBridgeReader != null) jpegBridgeReader.close(); } catch (Throwable ignored) {}
        jpegBridgeWriter = null;
        jpegBridgeReader = null;
    }

    '''
    src2 = src2[:anchor.start()] + bridge + src2[anchor.start():]
    print("[OK] Camera2SessionHook.java: added acquireFakeJpegImage + closeJpegBridge")
else:
    print("[SKIP] acquireFakeJpegImage already present")

# release cleanup: close bridge when session tears down
if "closeJpegBridge(); pendingJpegSurfaces.clear();" not in src2:
    src2 = src2.replace("pendingJpegSurfaces.clear();",
                        "closeJpegBridge(); pendingJpegSurfaces.clear();")
    print("[OK] release(): closeJpegBridge() wired into cleanup")

open(sesshook, "w", encoding="utf-8").write(src2)
print("\nDone. Rebuild: ./gradlew clean assembleDebug")