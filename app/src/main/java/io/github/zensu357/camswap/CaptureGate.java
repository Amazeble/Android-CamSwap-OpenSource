package io.github.zensu357.camswap;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.view.Surface;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * CaptureGate with AutoModeSwitch integration.
 *
 * Flow:
 *   App pressed capture
 *     -> CameraX issues pre-capture
 *     -> CaptureGate wraps CaptureCallback
 *     -> pre-capture onCaptureCompleted arrives
 *     -> if virtual source is not ready:
 *          hold CameraX callback
 *          AutoModeSwitch.onPreCaptureCompleted()
 *            -> switch passthrough -> CamSwap
 *            -> drop CaptureGate.markReady()
 *     -> CameraX continues
 *     -> CamSwap can replace JPEG
 */
public final class CaptureGate {

    private static final String TAG = "【CS】【CaptureGate】";
    private static final AtomicBoolean virtualSourceReady = new AtomicBoolean(false);
    private static final Queue<Runnable> pendingCallbacks = new ConcurrentLinkedQueue<>();
    private static final int MAX_PENDING_CALLBACKS = 32;

    private CaptureGate() {}

    public static void markReady() {
        if (virtualSourceReady.compareAndSet(false, true)) {
            LogUtil.log(TAG + "virtual source ready, flushing held pre-capture statuses");
            flushPendingCallbacks();
        }
    }

    public static void markNotReady() {
        if (virtualSourceReady.compareAndSet(true, false)) {
            LogUtil.log(TAG + "virtual source marked not ready");
        }
    }

    public static boolean isReady() {
        return virtualSourceReady.get();
    }

    private static void flushPendingCallbacks() {
        Runnable r;
        while ((r = pendingCallbacks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                LogUtil.log(TAG + "flush pending callback error: " + t);
            }
        }
    }

    private static void enqueueCallback(final Handler handler, final Runnable invocation) {
        while (pendingCallbacks.size() >= MAX_PENDING_CALLBACKS) {
            pendingCallbacks.poll();
        }

        pendingCallbacks.offer(new Runnable() {
            @Override
            public void run() {
                try {
                    if (handler != null) {
                        handler.post(invocation);
                    } else {
                        invocation.run();
                    }
                } catch (Throwable t) {
                    LogUtil.log(TAG + "deliver held callback error: " + t);
                }
            }
        });
    }

    public static boolean isPreCaptureRequest(CaptureRequest request) {
        if (request == null) return false;

        try {
            Integer ae = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
            if (ae != null && ae.intValue() == CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START) {
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            Integer af = request.get(CaptureRequest.CONTROL_AF_TRIGGER);
            if (af != null && af.intValue() == CaptureRequest.CONTROL_AF_TRIGGER_START) {
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static CameraCaptureSession.CaptureCallback wrapCallback(
            final CameraCaptureSession session,
            final CaptureRequest request,
            final CameraCaptureSession.CaptureCallback callback,
            final Handler handler) {

        if (callback == null || request == null) {
            return callback;
        }

        if (callback instanceof GateCaptureCallback) {
            return callback;
        }

        if (!isPreCaptureRequest(request)) {
            return callback;
        }

        LogUtil.log(TAG + "wrapping pre-capture callback");
        return new GateCaptureCallback(callback, handler, "pre-capture");
    }

    public static CameraCaptureSession.CaptureCallback wrapBurstCallback(
            final CameraCaptureSession session,
            final List<?> requests,
            final CameraCaptureSession.CaptureCallback callback,
            final Handler handler) {

        if (callback == null || requests == null || requests.isEmpty()) {
            return callback;
        }

        if (callback instanceof GateCaptureCallback) {
            return callback;
        }

        boolean hasPreCapture = false;
        for (Object obj : requests) {
            if (obj instanceof CaptureRequest && isPreCaptureRequest((CaptureRequest) obj)) {
                hasPreCapture = true;
                break;
            }
        }

        if (!hasPreCapture) {
            return callback;
        }

        LogUtil.log(TAG + "wrapping pre-capture burst callback");
        return new GateCaptureCallback(callback, handler, "pre-capture-burst");
    }

    private static final class GateCaptureCallback extends CameraCaptureSession.CaptureCallback {

        private final CameraCaptureSession.CaptureCallback original;
        private final Handler handler;
        private final String reason;

        GateCaptureCallback(
                CameraCaptureSession.CaptureCallback original,
                Handler handler,
                String reason) {
            this.original = original;
            this.handler = handler;
            this.reason = reason;
        }

        private void hold(Runnable invocation) {
            LogUtil.log(TAG + "holding " + reason + " callback until virtual source ready");
            enqueueCallback(handler, invocation);
        }

        @Override
        public void onCaptureStarted(
                CameraCaptureSession session,
                CaptureRequest request,
                long timestamp,
                long frameNumber) {
            original.onCaptureStarted(session, request, timestamp, frameNumber);
        }

        @Override
        public void onCaptureProgressed(
                CameraCaptureSession session,
                CaptureRequest request,
                CaptureResult partialResult) {
            original.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(
                final CameraCaptureSession session,
                final CaptureRequest request,
                final TotalCaptureResult result) {

            if (!isReady()) {
                hold(new Runnable() {
                    @Override
                    public void run() {
                        original.onCaptureCompleted(session, request, result);
                    }
                });

                // New automatic behavior:
                // pre-capture completed -> switch passthrough to CamSwap -> markReady()
                AutoModeSwitch.onPreCaptureCompleted();
                return;
            }

            original.onCaptureCompleted(session, request, result);
        }

        @Override
        public void onCaptureFailed(
                final CameraCaptureSession session,
                final CaptureRequest request,
                final CaptureFailure failure) {

            if (!isReady()) {
                hold(new Runnable() {
                    @Override
                    public void run() {
                        original.onCaptureFailed(session, request, failure);
                    }
                });
                return;
            }

            original.onCaptureFailed(session, request, failure);
        }

        @Override
        public void onCaptureSequenceCompleted(
                final CameraCaptureSession session,
                final int sequenceId,
                final long frameNumber) {

            if (!isReady()) {
                hold(new Runnable() {
                    @Override
                    public void run() {
                        original.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
                    }
                });
                return;
            }

            original.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
        }

        @Override
        public void onCaptureSequenceAborted(
                final CameraCaptureSession session,
                final int sequenceId) {

            if (!isReady()) {
                hold(new Runnable() {
                    @Override
                    public void run() {
                        original.onCaptureSequenceAborted(session, sequenceId);
                    }
                });
                return;
            }

            original.onCaptureSequenceAborted(session, sequenceId);
        }

        @Override
        public void onCaptureBufferLost(
                CameraCaptureSession session,
                CaptureRequest request,
                Surface target,
                long frameNumber) {
            original.onCaptureBufferLost(session, request, target, frameNumber);
        }
    }
}
