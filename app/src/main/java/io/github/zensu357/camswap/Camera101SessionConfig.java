package io.github.zensu357.camswap.api101;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Builds a VTCam-compatible SessionConfiguration for Camera 101.
 *
 * VTCam usecase (chxusecaseVTCam.cpp) requires:
 *   - m_pInputYuvStream  != NULL   (an INPUT stream, YUV_420_888)
 *   - m_pTargetYuvStream != NULL   (an OUTPUT stream, YUV_420_888)
 *   - m_pTargetJpegStream may be NULL (optional)
 *
 * We provide both so configureStreams() returns success instead of -38.
 * The actual video preview is rendered directly onto the app's output
 * Surface by CamSwap's MediaPlayer → MediaCodec → EGL pipeline.
 * No reprocessCaptureRequest is ever submitted — the input stream is
 * only there to satisfy the HAL's usecase validation.
 */
public final class Camera101SessionConfig {

    private static final String TAG = "CS-Cam101";

    /** The YUV ImageReader that acts as the VTCam "input" source. */
    private ImageReader inputReader;

    /** A secondary YUV ImageReader for the VTCam "target" output. */
    private ImageReader targetYuvReader;

    /**
     * Patches the SessionConfiguration so the HAL accepts it.
     *
     * Call this from the createCaptureSession hook BEFORE the original
     * method is invoked.
     *
     * @param sessionConfig the SessionConfiguration the app built
     * @param previewSize   the size the app requested for preview
     * @return true if patched successfully, false on error
     */
    public boolean patchSessionConfig(SessionConfiguration sessionConfig,
                                      Size previewSize) {
        try {
            int w = previewSize.getWidth();
            int h = previewSize.getHeight();

            // --- 1. Input stream (VTCam m_pInputYuvStream) ---
            // VTCam expects a YUV_420_888 input stream.
            // We create an ImageReader that will serve as the input source.
            // We never actually write to it — it just needs to exist.
            inputReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
            InputConfiguration inputConfig =
                    new InputConfiguration(w, h, ImageFormat.YUV_420_888);
            sessionConfig.setInputConfiguration(inputConfig);

            // --- 2. Target YUV output (VTCam m_pTargetYuvStream) ---
            // VTCam also requires a YUV output stream.
            // We add a secondary ImageReader as an extra output.
            targetYuvReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
            List<OutputConfiguration> outputs =
                    sessionConfig.getOutputConfigurations();
            outputs.add(new OutputConfiguration(targetYuvReader.getSurface()));

            LogUtil.log(TAG + " Patched SessionConfiguration for VTCam: "
                    + "input=" + w + "x" + h + " YUV, "
                    + "targetYuv=" + w + "x" + h + " YUV, "
                    + "totalOutputs=" + outputs.size());

            return true;

        } catch (Throwable t) {
            LogUtil.log(TAG + " Failed to patch SessionConfiguration: " + t);
            return false;
        }
    }

    /**
     * Extract the preview size from the app's original output list.
     * Falls back to 1280x720 if nothing usable is found.
     */
    public static Size resolvePreviewSize(SessionConfiguration sessionConfig) {
        List<OutputConfiguration> outputs =
                sessionConfig.getOutputConfigurations();
        if (outputs != null && !outputs.isEmpty()) {
            // Use the first output's surface size as reference.
            // For SurfaceTexture-backed surfaces the size may be 0,
            // so we try each until we find one with dimensions.
            for (OutputConfiguration oc : outputs) {
                Surface s = oc.getSurface();
                if (s != null && s.isValid()) {
                    // SurfaceTexture surfaces report 0x0 before first frame.
                    // We'll use a safe default in that case.
                    Size sz = querySurfaceSize(s);
                    if (sz.getWidth() > 0 && sz.getHeight() > 0) {
                        return sz;
                    }
                }
            }
        }
        // Default: match the EGL size CamSwap already uses
        return new Size(1280, 720);
    }

    private static Size querySurfaceSize(Surface surface) {
        try {
            // For SurfaceView-backed surfaces we can query the holder.
            // For SurfaceTexture, this returns 0x0 — handled by caller.
            java.lang.reflect.Method m =
                    surface.getClass().getMethod("getWidth");
            int w = (int) m.invoke(surface);
            m = surface.getClass().getMethod("getHeight");
            int h = (int) m.invoke(surface);
            return new Size(w, h);
        } catch (Throwable ignored) {
            return new Size(0, 0);
        }
    }

    /**
     * Release resources. Call when the session is closed or the camera
     * is disconnected. This prevents leaked ImageReaders from keeping
     * the HAL session alive and causing flush() to crash on teardown.
     */
    public void release() {
        if (inputReader != null) {
            inputReader.close();
            inputReader = null;
        }
        if (targetYuvReader != null) {
            targetYuvReader.close();
            targetYuvReader = null;
        }
        LogUtil.log(TAG + " Released VTCam stream resources");
    }
}
