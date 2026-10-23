package com.abrenoch.hyperiongrabber.common;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Build;
import androidx.annotation.RequiresApi;
import android.util.Log;

import com.abrenoch.hyperiongrabber.common.network.HyperionThread;
import com.abrenoch.hyperiongrabber.common.util.BorderProcessor;
import com.abrenoch.hyperiongrabber.common.util.HyperionGrabberOptions;

import java.nio.ByteBuffer;
import java.util.Locale;

public class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    // Use 2 buffers: At least 2 are required so the Surface producer (VirtualDisplay)
    // always has a free buffer to render into while the consumer processes/releases the previous frame.
    // Setting this to 1 causes pipeline stalls and dropped frames.
    private static final int MAX_IMAGE_READER_IMAGES = 2;
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;

    int mScaledWidth;
    int mScaledHeight;
    // [deepseek] Ping-pong frame buffers: the capture thread writes into one buffer while the
    // [deepseek] network thread still owns the other one, so no per-frame clone() is needed on
    // [deepseek] the hot path. mUseAltBuffer selects which buffer the next frame goes into.
    private byte[] mRgbBuffer;
    private byte[] mRgbBufferAlt;
    private boolean mUseAltBuffer;
    // [deepseek] Scratch row buffer for bulk reads of one full source row. Reading a whole row
    // [deepseek] with a single ByteBuffer.get() avoids three bounds-checked get() calls per pixel.
    private byte[] mRowBuffer;
        // [deepseek] Reused 3-byte buffer for the average-color path so that no byte[] is allocated
    // [deepseek] on every frame (the old code created a ByteArrayOutputStream per call).
    private final byte[] mAvgColorBuffer = new byte[3];
    // [deepseek] Removed the private mDownscaleFactor field that used to live here: it shadowed the
    // [deepseek] field of the same name in HyperionScreenEncoderBase. Before prepare() copied the
    // [deepseek] base value into it, reads returned 0, which would have caused a division by zero
    // [deepseek] in setImageReader(). The inherited field is used now, and it can no longer go stale.

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    HyperionScreenEncoder(final HyperionThread.HyperionThreadListener listener,
                           final MediaProjection projection, final int width, final int height,
                           final int density, HyperionGrabberOptions options) {
        super(listener, projection, width, height, density, options);

        try {
            prepare();
        } catch (MediaCodec.CodecException e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void prepare() throws MediaCodec.CodecException {
        if (DEBUG) Log.d(TAG, "Preparing encoder");

        //Note that DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION is always enabled.
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;// | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        // flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        //   | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

                // [deepseek] getGrabberWidth()/getGrabberHeight() return the capture resolution selected by
        // [deepseek] the resolution preference (160x90 ... 1920x1080). Whether it is reduced before
        // [deepseek] being sent is decided by PASSTHROUGH_1_TO_1 in HyperionScreenEncoderBase.
        setImageReader();

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                getGrabberWidth(), 
                getGrabberHeight(), 
                mDensity,
                flags,
                null, 
                mDisplayCallback, 
                null);

        mVirtualDisplay.setSurface(mImageReader.getSurface());

                // [deepseek] There is deliberately no frame rate control for the virtual display here.
        // [deepseek] Tried and measured: Surface.setFrameRate() on the capture Surface is accepted
        // [deepseek] without error on API 30, but it has no effect. With the framerate preference at
        // [deepseek] 30 fps the virtual display still produced 40-50 frames per second while the
        // [deepseek] screen was moving. createVirtualDisplay() itself takes no frame rate argument,
        // [deepseek] so the capture rate can only be bounded on the consumer side, in
        // [deepseek] imageAvailableListener below.

        //setImageReader();
    }

        @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording Called");
        setCapturing(false);

        // [deepseek] Detach the ImageReader's BufferQueue from the virtual display before releasing
        // [deepseek] it. Calling release() alone left the display device registered with state=ON and
        // [deepseek] still mirroring on this TV (Android 11, MStar HAL): three start/stop cycles in one
        // [deepseek] process left three live 240x135 mirror displays behind, and only force-stop
        // [deepseek] removed them. Every leftover mirror is extra composition work for the display HAL,
        // [deepseek] which is where most of the grabber's CPU cost is spent.
        if (mVirtualDisplay != null) {
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        // [deepseek] Drop the callback before closing so a queued frame cannot run against a closed
        // [deepseek] reader.
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }

        if (mHandler != null) {
            mHandler.getLooper().quit();
        }

        clearAndDisconnect();
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording Called");
        if (!isCapturing() && mImageReader != null) {
            if (DEBUG) Log.i(TAG, "Resuming reading images");
            Image img = mImageReader.acquireNextImage();
            setCapturing(true);
            if (img != null) {
                img.close();
            }
        }
    }

    // @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    // @Override
    // public void setOrientation(int orientation) {
    //     if (mVirtualDisplay != null && orientation != mCurrentOrientation) {
    //         mCurrentOrientation = orientation;
    //         mIsCapturing = false;
    //         mVirtualDisplay.resize(getGrabberWidth(), getGrabberHeight(), mDensity);
    //         mImageReader.close();
    //         setImageReader();
    //     }
    // }

    private VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d("DEBUG", "HyperionScreenEncoder.displayCallback.onPaused triggered");
            super.onPaused();
            setCapturing(false);
        }

        @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public void onResumed() {
            if (DEBUG) Log.d("DEBUG", "HyperionScreenEncoder.displayCallback.onResumed triggered");
            super.onResumed();
            resumeRecording();
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d("DEBUG", "HyperionScreenEncoder.displayCallback.onStopped triggered");
            super.onStopped();
            setCapturing(false);
        }
    };

    @SuppressLint("WrongConstant") // incorrectly reports PixelFormat.RGBA_8888 as incompatible
    @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
    private void setImageReader() {
        if (DEBUG) Log.d(TAG, "Setting image reader  " + String.valueOf(isCapturing()));

        if (mImageReader != null) {
            mImageReader.close();  // Release image reader
            mImageReader = null;
        }

        int width = getGrabberWidth();
        int height = getGrabberHeight();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !mUseLegacyImageReader) {
            if (DEBUG) Log.d(TAG, "Using new image reader...");

            mImageReader = ImageReader.newInstance(width, height,
                    HardwareBuffer.RGBA_8888, MAX_IMAGE_READER_IMAGES, HardwareBuffer.USAGE_CPU_READ_OFTEN);
            
        } else {
            if (DEBUG) Log.d(TAG, "Using legacy image reader...");

            mImageReader = ImageReader.newInstance(width, height,
                    PixelFormat.RGBA_8888, MAX_IMAGE_READER_IMAGES);
        }

                mScaledWidth = width / mDownscaleFactor;
        mScaledHeight = height / mDownscaleFactor;

                // [deepseek] The reader runs at capture size and getPixels() applies mDownscaleFactor exactly
        // [deepseek] once. With PASSTHROUGH_1_TO_1 the factor is 1, so mScaledWidth/mScaledHeight equal
        // [deepseek] the capture size and the buffers below hold a full uncompressed RGB frame.
        // [deepseek] Allocate both ping-pong frame buffers up front so the capture loop never
        // [deepseek] allocates memory per frame (avoids GC pressure on weak TV hardware).
        mRgbBuffer = new byte[mScaledWidth * mScaledHeight * 3];
        mRgbBufferAlt = new byte[mScaledWidth * mScaledHeight * 3];
        mUseAltBuffer = false;
        // [deepseek] One full RGBA row of the source image, reused for every row of every frame.
        mRowBuffer = new byte[width * 4];

        if (DEBUG) Log.d(TAG, "mRgbBuffer " + String.valueOf(mScaledWidth * mScaledHeight * 3));

        mImageReader.setOnImageAvailableListener(imageAvailableListener, mHandler);
        //mVirtualDisplay.setSurface(mImageReader.getSurface());
        setCapturing(true);
    }

                    // [deepseek] TEMPORARY INSTRUMENTATION for measuring the capture and send rate.
    // [deepseek] Counts how many *distinct* frames the virtual display produces and how many frames
    // [deepseek] we actually send, then logs both rates every 5 seconds.
    // [deepseek] "Distinct" means keyed on the Image timestamp: acquireLatestImage() hands out the
    // [deepseek] same producer frame again after close(), which would roughly double a plain count.
    // [deepseek] Measured reference values on this TV:
    // [deepseek]   producer rate is content driven, not a constant 60 Hz - a static screen produces
    // [deepseek]   only ~8 img/s, moving content ~40-50 img/s.
    // [deepseek]   "fps sent" must sit at or just below the configured framerate. Clearly below it
    // [deepseek]   means the pacing did not keep up: with the old relative throttle, 30 configured
    // [deepseek]   produced only ~21.6 fps sent.
    // [deepseek] Master switch for the instrumentation below. When false, countImage() returns
    // [deepseek] immediately and the JIT removes the rest, so the code can stay in place without
    // [deepseek] costing anything. The per frame overhead when enabled is a field read, a long
    // [deepseek] compare and one System.nanoTime() (~100 ns), plus one Log.i every 5 seconds.
    private static final boolean STATS = true;
    private static final long STATS_INTERVAL_NS = 5_000_000_000L;
    private long mUniqueImages;
    private long mFramesSent;
    private long mLastImageTimestamp = Long.MIN_VALUE;
    private long mStatsStartTime = System.nanoTime();
    private boolean mSettingsLogged;

    private void logSettingsOnce() {
        if (mSettingsLogged) return;
        mSettingsLogged = true;
        Log.i(TAG, "Config: framerate=" + mFrameRate
                + " capture=" + mCaptureWidth + "x" + mCaptureHeight
                + " sent=" + mScaledWidth + "x" + mScaledHeight
                + " downscale=" + mDownscaleFactor);
    }

    private void countImage(Image img) {
        if (!STATS) return;
        logSettingsOnce();

        // [deepseek] Only count frames with a new producer timestamp, see explanation above.
        final long timestamp = img.getTimestamp();
        if (timestamp != mLastImageTimestamp) {
            mLastImageTimestamp = timestamp;
            mUniqueImages++;
        }

        final long elapsed = System.nanoTime() - mStatsStartTime;
        if (elapsed < STATS_INTERVAL_NS) return;

        final double seconds = elapsed / 1e9;
        Log.i(TAG, String.format(Locale.US,
                "Stats: %d images, %d sent in %.1f s -> %.1f img/s producer, %.1f fps sent, ratio %.2f:1",
                mUniqueImages, mFramesSent, seconds,
                mUniqueImages / seconds, mFramesSent / seconds,
                mFramesSent == 0 ? 0.0 : (double) mUniqueImages / mFramesSent));
        mUniqueImages = 0;
        mFramesSent = 0;
        mStatsStartTime = System.nanoTime();
    }

        // [deepseek] Frame pacing. The old throttle compared against the timestamp of the last frame
    // [deepseek] that was actually sent ("now - lastFrame >= min_nano_time" together with
    // [deepseek] "lastFrame = now"). Because that check runs on the ImageReader callback, i.e. on the
    // [deepseek] vsync grid, every send is rounded up to the next callback - and resetting the
    // [deepseek] reference to "now" means the rounding error is never recovered. The throttle could
    // [deepseek] therefore only ever end up slower than the configured rate. Measured: 30 fps
    // [deepseek] configured produced 21.6 fps actually sent, because two vsync intervals (33.333 ms)
    // [deepseek] fall a few nanoseconds short of 1e9/30, so the throttle slipped to three intervals
    // [deepseek] (50 ms -> 20 fps).
    //
    // [deepseek] An absolute deadline advanced by exactly one period keeps the long term rate at the
    // [deepseek] configured value. Individual gaps still follow the vsync grid and vary, but the
    // [deepseek] rounding error no longer accumulates.
    private final long mFramePeriodNanos = 1_000_000_000L / Math.max(1, mFrameRate);
    private long mNextFrameDeadline = System.nanoTime();

    private OnImageAvailableListener imageAvailableListener = new OnImageAvailableListener() {
        @RequiresApi(api = Build.VERSION_CODES.KITKAT)
        @Override
        public void onImageAvailable(ImageReader reader) {
            if (mListener != null && isCapturing()) {
                try {
                    long now = System.nanoTime();
                    Image img = reader.acquireLatestImage();
                    if (img != null) {
                        countImage(img);
                        if (now >= mNextFrameDeadline) {
                            sendImage(img);
                            mFramesSent++;
                            mNextFrameDeadline += mFramePeriodNanos;

                            // [deepseek] If we fell more than one whole period behind (screen off,
                            // [deepseek] long pause, GC hiccup) resync instead of firing a burst of
                            // [deepseek] frames back to back to "catch up".
                            if (now - mNextFrameDeadline > mFramePeriodNanos) {
                                mNextFrameDeadline = now + mFramePeriodNanos;
                            }
                        }
                        img.close(); // Bild nach der Verwendung schließen                        
                    }
                } catch (final Exception e) {
                    if (DEBUG) Log.w(TAG, "sendImage exception:", e);
                }
            }
        }
    };

    private byte[] getPixels(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride, int downscaleFactor) 
    {  
        // [deepseek] Alternate between the two frame buffers so the buffer that was handed to the
        // [deepseek] network thread is never overwritten while it is still being serialized/sent.
        final byte[] out = mUseAltBuffer ? mRgbBufferAlt : mRgbBuffer;
        mUseAltBuffer = !mUseAltBuffer;
        int outputIndex = 0;

        // [deepseek] Fast path: tightly packed RGBA rows (pixelStride == 4, no row padding).
        // [deepseek] One bulk get() per row replaces three bounds-checked absolute get() calls
        // [deepseek] per pixel, which is the dominant cost of the whole capture pipeline.
        if (pixelStride == 4 && rowStride == width * 4) {
            final byte[] row = mRowBuffer;
            for (int y = 0; y < height; y += downscaleFactor) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, rowStride);

                for (int x = 0, src = 0; x < width; x += downscaleFactor, src += 4 * downscaleFactor) {
                    out[outputIndex++] = row[src];
                    out[outputIndex++] = row[src + 1];
                    out[outputIndex++] = row[src + 2];
                }
            }
            return out;
        }

        // [deepseek] Fallback: padded rows or an unusual pixel stride, handled with the original
        // [deepseek] per-pixel absolute reads.
        for (int y = 0; y < height; y += downscaleFactor) 
        {
            int rowStart = y * rowStride;
            
            for (int x = 0; x < width; x += downscaleFactor) 
            {                
                int pixelIndex = rowStart + x * pixelStride;   
                
                out[outputIndex++] = buffer.get(pixelIndex);
                out[outputIndex++] = buffer.get(pixelIndex + 1);
                out[outputIndex++] = buffer.get(pixelIndex + 2);
            }
        }

        return out;
    }

    private byte[] getAverageColor(ByteBuffer buffer, int width, int height, int rowStride,
                                   int pixelStride, int firstX, int firstY) {
        long totalRed = 0, totalGreen = 0, totalBlue = 0;
        int rowPadding = rowStride - width * pixelStride;
        int pixelCount = 0;
        int offset = 0;

        for (int y = 0, compareHeight = height - firstY - 1; y < height; y++, offset += rowPadding) {
            if (y < firstY || y > compareHeight) {
                offset += width * pixelStride;
                continue;
            }
            for (int x = 0, compareWidth = width - firstX - 1; x < width; x++, offset += pixelStride) {
                if (x < firstX || x > compareWidth) continue;
                totalRed += buffer.get(offset) & 0xff; // R
                totalGreen += buffer.get(offset + 1) & 0xff; // G
                totalBlue += buffer.get(offset + 2) & 0xff; // B
                pixelCount++;
            }
        }

        // [deepseek] Guard against a fully cropped image: without this the division below would
        // [deepseek] throw ArithmeticException (division by zero).
        if (pixelCount == 0) return mAvgColorBuffer;

        // [deepseek] Write into the reused buffer instead of allocating a new byte[] per frame.
        mAvgColorBuffer[0] = (byte) ((int) totalRed / pixelCount);
        mAvgColorBuffer[1] = (byte) ((int) totalGreen / pixelCount);
        mAvgColorBuffer[2] = (byte) ((int) totalBlue / pixelCount);

        return mAvgColorBuffer;
    }

    private void sendImage(Image img) 
    {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int width = img.getWidth();
        int height = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int firstX = 0;
        int firstY = 0;        

        // if (mRemoveBorders || mAvgColor) {
        //     mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride);
        //     BorderProcessor.BorderObject border = mBorderProcessor.getCurrentBorder();
        //     if (border != null && border.isKnown()) {
        //         firstX = border.getHorizontalBorderIndex();
        //         firstY = border.getVerticalBorderIndex();
        //     }
        // }

        // if (mAvgColor) {
        //     mListener.sendFrame(
        //             getAverageColor(buffer, width, height, rowStride, pixelStride, firstX, firstY),
        //             1,
        //             1
        //     );
        // } else 
        {                     

            mListener.sendFrame(
                getPixels(buffer, width, height, rowStride, pixelStride, mDownscaleFactor),
                mScaledWidth,
                mScaledHeight
            );  
        }
    }
}