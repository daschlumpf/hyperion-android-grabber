package com.abrenoch.hyperiongrabber.common;

import android.content.res.Configuration;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.abrenoch.hyperiongrabber.common.network.HyperionThread;
import com.abrenoch.hyperiongrabber.common.util.BorderProcessor;
import com.abrenoch.hyperiongrabber.common.util.HyperionGrabberOptions;

public class HyperionScreenEncoderBase {
    static final boolean DEBUG = false;
    private static final String TAG = "ScreenEncoderBase";
    private final int CLEAR_COMMAND_DELAY_MS = 100;
    private final int INIT_ORIENTATION;

        final boolean mRemoveBorders = false; // enables detecting borders for standard grabbing - disabled for now
        final boolean mAvgColor;
        final boolean mUseLegacyImageReader;
        // [drmsample] When enabled, black frames (typical for DRM protected content) are replaced by a
        // [drmsample] solid orange image instead of being sent to Hyperion.
        final boolean mDrmOrangeFallback;
        // [deepseek] Two distinct sizes are involved and they must not be confused:
    // [deepseek]   mCaptureWidth/mCaptureHeight = size of the VirtualDisplay and of the ImageReader
    // [deepseek]                                 (derived from the resolution preference).
    // [deepseek]   mWidthScaled/mHeightScaled   = size of the image sent to the Hyperion server.
    // [deepseek]                                 With PASSTHROUGH_1_TO_1 that equals the capture
    // [deepseek]                                 size, otherwise it is the LED matrix size.
    // [deepseek] The reduction from capture size to that size happens exactly once, in getPixels().
    final int mWidthScaled;
    final int mFrameRate;
    final int mHeightScaled;
    final int mCaptureWidth;
    final int mCaptureHeight;
    final int mDensity;
    volatile boolean mIsCapturing = false;
    int mCurrentOrientation;
    final int mDownscaleFactor;

    Handler mHandler;
    MediaProjection mMediaProjection;
    HyperionThread.HyperionThreadListener mListener;

    HyperionScreenEncoderBase(final HyperionThread.HyperionThreadListener listener,
                              final MediaProjection projection, int width, int height,
                              final int density, HyperionGrabberOptions options) {
        if (DEBUG) Log.d(TAG, "Encoder starting");

        mListener = listener;
        mMediaProjection = projection;
        mDensity = density;
        mFrameRate = options.getFrameRate();
        mAvgColor = options.useAverageColor();
        String resolution = options.Resolution();
        mUseLegacyImageReader = options.useLegacyImageReader();
        mDrmOrangeFallback = options.useDrmOrangeFallback();

        mCurrentOrientation = INIT_ORIENTATION = width > height ? Configuration.ORIENTATION_LANDSCAPE :
                Configuration.ORIENTATION_PORTRAIT;
                
        Log.d("HyperionScreenEncoderBase Settings", "Resolution gespeichert: " + resolution);

                // [deepseek] The resolution preference stores only the width ("160", "240", "320", "640",
        // [deepseek] "1280", "1920"). The height is now derived from the real panel aspect ratio
        // [deepseek] instead of a hardcoded 16:9 table, so adding another width needs no change here
        // [deepseek] and non-16:9 panels keep their aspect ratio.
        int targetWidth;
        try {
            targetWidth = Integer.parseInt(resolution.trim());
        } catch (NumberFormatException e) {
            targetWidth = 320;
        }
        if (targetWidth <= 0) targetWidth = 320;
        // never capture wider than the panel itself
        targetWidth = Math.min(targetWidth, width);
        int targetHeight = (int) Math.round((double) targetWidth * height / width);

                // [deepseek] These are the dimensions of the VirtualDisplay and the ImageReader, so the
        // [deepseek] resolution preference finally does what its label promises. Everything below
        // [deepseek] is derived from them.
        mCaptureWidth = targetWidth;
        mCaptureHeight = targetHeight;

                // [deepseek] TEST: 1:1 passthrough. The captured image is sent to Hyperion unscaled and
        // [deepseek] Hyperion averages it down onto the LED matrix itself. Because the factor is 1,
        // [deepseek] the transmitted size equals the capture size, i.e. the resolution preference
        // [deepseek] directly selects the image size that goes over the wire.
        // [deepseek] Set this to false to get the previous behaviour back (capture size divided by
        // [deepseek] findDivisor() == the LED matrix size).
        final boolean PASSTHROUGH_1_TO_1 = true;
        int divisor = PASSTHROUGH_1_TO_1 ? 1 : options.findDivisor(mCaptureWidth, mCaptureHeight);

        // set the scaled width & height based upon the found divisor
        mHeightScaled = (mCaptureHeight / divisor);
        mWidthScaled = (mCaptureWidth / divisor);

                // [deepseek] Single downscale stage. The ImageReader delivers the capture resolution, so
        // [deepseek] getPixels() applies the factor exactly once. With PASSTHROUGH_1_TO_1 the factor
        // [deepseek] is 1 and the image is forwarded unmodified.
        // [deepseek] The previous code created the VirtualDisplay/ImageReader at mWidthScaled x
        // [deepseek] mHeightScaled (already reduced) and then applied a divisor a second time in
        // [deepseek] getPixels(), so the transmitted image was a factor of `divisor` too small.
        mDownscaleFactor = divisor;

        if (DEBUG) {
            Log.d(TAG, "Density: " + String.valueOf(mDensity));
            Log.d(TAG, "Frame Rate: " + String.valueOf(mFrameRate));
                        // [deepseek] Capture size used for VirtualDisplay and ImageReader.
            Log.d(TAG, "Capture Width: " + String.valueOf(mCaptureWidth));
            Log.d(TAG, "Capture Height: " + String.valueOf(mCaptureHeight));
            Log.d(TAG, "Average Color Only: " + String.valueOf(mAvgColor));
        }

        if (DEBUG) {
                        Log.d(TAG, "Common Divisor: " + String.valueOf(divisor));
            // [deepseek] Scaled size is what is actually sent to Hyperion (the LED matrix size).
            Log.d(TAG, "Scaled Width: " + String.valueOf(mWidthScaled));
            Log.d(TAG, "Scaled Height: " + String.valueOf(mHeightScaled));
            // [deepseek] The one and only downscale stage; equals the divisor, so the reduction from
            // [deepseek] capture size to scaled size is exact (no remainder, no partially filled buffer).
            Log.d(TAG, "Downscale Factor: " + String.valueOf(mDownscaleFactor));
        }

        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        if (DEBUG) Log.d(TAG, "Encoder ready");
    }

    public void clearLights() {
        if (mListener != null) {
            mListener.clear();
        }
    }

    void clearAndDisconnect() {
        if (mListener != null) {
            mListener.disconnect();
        }
    }

    public boolean isCapturing() {
        return mIsCapturing;
    }

    public void setCapturing(boolean isCapturing) {
        mIsCapturing = isCapturing;
    }

    public void sendStatus() {
        if (mListener != null) {
            mListener.sendStatus(isCapturing());
        }
    }

    public void stopRecording() {
        throw new RuntimeException("Stub!");
    }

    public void resumeRecording() {
        throw new RuntimeException("Stub!");
    }

        // [deepseek] The grabber (VirtualDisplay/ImageReader) runs at the capture resolution now, not at
    // [deepseek] the LED size. getPixels() applies the downscale, so these two sizes must differ.
    int getGrabberWidth() {
        return INIT_ORIENTATION != mCurrentOrientation ? mCaptureHeight : mCaptureWidth;
    }

    int getGrabberHeight() {
        return INIT_ORIENTATION != mCurrentOrientation ? mCaptureWidth : mCaptureHeight;
    }

    int getDownscaleFactor() {
        return mDownscaleFactor;
    }

    public void setOrientation(int orientation) {
        mCurrentOrientation = orientation;
    }
}