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
        // [drmsample] The orange buffer is sized to the scaled dimensions, so it must be rebuilt
        // [drmsample] whenever the capture size changes; also reset the detection state so a
        // [drmsample] restart begins with a clean slate.
        mOrangeBuffer = null;
        mBlackFrameCount = 0;
        mBrightFrameCount = 0;
        mSendingOrange = false;
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
                "Stats: %d images, %d sent in %.1f s -> %.1f img/s producer, %.1f fps sent, ratio %.2f:1 drmFallback=%s",
                mUniqueImages, mFramesSent, seconds,
                mUniqueImages / seconds, mFramesSent / seconds,
                mFramesSent == 0 ? 0.0 : (double) mUniqueImages / mFramesSent,
                mSendingOrange ? "ON" : "off"));
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

        // [drmsample] Brightness probe for DRM detection. We do NOT do a second pass over the
        // [drmsample] image: instead we sample a fixed grid of positions while the existing copy
        // [drmsample] loop is already touching every pixel, so the extra cost is a couple of
        // [drmsample] comparisons per row/column - no measurable impact.
        // [drmsample] A sampled pixel counts as "bright" when any of R,G,B is clearly above black.
        // [drmsample] Real content almost always has some non-black pixel in the grid; a protected
        // [drmsample] layer that the compositor replaces with black produces an all-black grid.
        int sampleMax = 0;
        int brightSamples = 0;
        int sampleCount = 0;
        // [drmsample] Skip the probing entirely when the user disabled the fallback, so a disabled
        // [drmsample] feature costs literally nothing in the copy loop.
        final boolean doSampling = mDrmOrangeFallback;

        // [deepseek] Path selection: the fast path handles tightly packed RGBA rows (pixelStride == 4,
        // [deepseek] no row padding) with one bulk get() per row; the fallback handles padded rows or
        // [deepseek] an unusual pixel stride with per-pixel absolute reads. Only the addressing of a
        // [deepseek] single pixel differs, so both share the loop below.
        final boolean bulkRowRead = pixelStride == 4 && rowStride == width * 4;
        final byte[] row = bulkRowRead ? mRowBuffer : null;

        for (int y = 0; y < height; y += downscaleFactor)
        {
            final int rowStart = y * rowStride;
            // [drmsample] Only probe every SAMPLE_STEP-th source row.
            final boolean sampleRow = doSampling && (y % SAMPLE_STEP) == 0;

            if (bulkRowRead)
            {
                // [deepseek] FAST PATH: read the whole padded-free RGBA row in one bulk get(); the
                // [deepseek] inner loop then only touches the scratch row instead of the ByteBuffer.
                buffer.position(rowStart);
                buffer.get(row, 0, rowStride);
            }

            for (int x = 0, src = 0; x < width; x += downscaleFactor, src += pixelStride * downscaleFactor)
            {
                final byte r;
                final byte g;
                final byte b;
                if (bulkRowRead) 
                {
                    // [deepseek] FAST PATH: pixel comes from the already materialized row buffer.
                    r = row[src];
                    g = row[src + 1];
                    b = row[src + 2];
                } 
                else 
                {
                    // [deepseek] FALLBACK PATH: per-pixel absolute reads honour row padding and stride.
                    final int pixelIndex = rowStart + src;
                    r = buffer.get(pixelIndex);
                    g = buffer.get(pixelIndex + 1);
                    b = buffer.get(pixelIndex + 2);
                }

                out[outputIndex++] = r;
                out[outputIndex++] = g;
                out[outputIndex++] = b;

                if (sampleRow && (x % SAMPLE_STEP) == 0) 
                {
                    final int rr = r & 0xFF;
                    final int gg = g & 0xFF;
                    final int bb = b & 0xFF;
                    final int max = rr > gg ? (rr > bb ? rr : bb) : (gg > bb ? gg : bb);
                    if (max > sampleMax) sampleMax = max;
                    if (max > BLACK_SAMPLE_THRESHOLD) brightSamples++;
                    sampleCount++;
                }
            }
        }

        updateDrmState(sampleMax, brightSamples, sampleCount);
        return out;
    }

    // [drmsample] --- DRM / protected-content detection --------------------------------
    // [drmsample] Thresholds are intentionally conservative: a pixel is "not black" when its
    // [drmsample] brightest channel is above BLACK_SAMPLE_THRESHOLD. The sum of all sampled pixels
    // [drmsample] has to stay below BLACK_FRAME_MAX for a frame to count as black.
    private static final int BLACK_SAMPLE_THRESHOLD = 24;
    // [drmsample] Sampling stride in *source* pixels. 16 means every 16th row and every 16th
    // [drmsample] column of the capture image is probed - on 1280x720 that is ~80x45 = 3600 probes
    // [drmsample] out of 921k pixels, i.e. 0.4%.
    private static final int SAMPLE_STEP = 16;
    // [drmsample] Number of consecutive black frames before the orange fallback kicks in.
    // [drmsample] At 30 fps this is ~0.33 s, short enough to hide scene cuts (which have dark frames)
    // [drmsample] and long enough not to react to a single fade frame.
    private static final int BLACK_FRAMES_BEFORE_ORANGE = 10;
    // [drmsample] Number of consecutive bright frames before returning to normal capture.
    private static final int BRIGHT_FRAMES_BEFORE_NORMAL = 3;
    // [drmsample] Orange in RGB (255, 140, 0).
    private static final byte ORANGE_R = (byte) 0xFF;
    private static final byte ORANGE_G = (byte) 0x8C;
    private static final byte ORANGE_B = (byte) 0x00;

    private int mBlackFrameCount;
    private int mBrightFrameCount;
    private boolean mSendingOrange;
    // [drmsample] Orange frame buffer, reused; same size as the RGB frame buffers so sendFrame()
    // [drmsample] can take the exact same width/height.
    private byte[] mOrangeBuffer;

    /**
     * Updates the black/bright frame counters from one frame's sampling result and switches the
     * DRM orange fallback on/off accordingly. Called from getPixels() with the values collected
     * during the normal pixel copy, so no additional image pass is required.
     *
     * @param sampleMax    highest channel value seen in the sample grid
     * @param brightSamples number of sampled pixels above BLACK_SAMPLE_THRESHOLD
     * @param sampleCount  total number of sampled pixels
     */
    private void updateDrmState(int sampleMax, int brightSamples, int sampleCount) {
        // [drmsample] Feature toggle from the settings screen. When disabled the counters stay at
        // [drmsample] zero and sendImage() always forwards the real frame.
        if (!mDrmOrangeFallback) {
            mBlackFrameCount = 0;
            mBrightFrameCount = 0;
            mSendingOrange = false;
            return;
        }
        if (sampleCount == 0) return;

        final boolean black = brightSamples == 0;

        if (black) {
            mBrightFrameCount = 0;
            if (mBlackFrameCount < Integer.MAX_VALUE) mBlackFrameCount++;
            if (!mSendingOrange && mBlackFrameCount >= BLACK_FRAMES_BEFORE_ORANGE) {
                mSendingOrange = true;
                Log.i(TAG, "DRM fallback ON: " + mBlackFrameCount
                        + " consecutive black frames (max channel " + sampleMax + "), sending orange");
            }
        } else {
            mBlackFrameCount = 0;
            if (mSendingOrange) {
                mBrightFrameCount++;
                if (mBrightFrameCount >= BRIGHT_FRAMES_BEFORE_NORMAL) {
                    mSendingOrange = false;
                    mBrightFrameCount = 0;
                    Log.i(TAG, "DRM fallback OFF: content visible again ("
                            + brightSamples + "/" + sampleCount + " sampled pixels bright)");
                }
            }
        }
    }

    /** @return true if the last sampled frame was judged to be a protected/black frame */
    private boolean isSendingOrange() {
        return mSendingOrange;
    }

    /** Builds (once) and returns the solid orange frame buffer. */
    private byte[] getOrangeBuffer() {
        if (mOrangeBuffer == null) {
            final int pixels = mScaledWidth * mScaledHeight;
            final byte[] buf = new byte[pixels * 3];
            for (int i = 0; i < pixels; i++) {
                buf[i * 3] = ORANGE_R;
                buf[i * 3 + 1] = ORANGE_G;
                buf[i * 3 + 2] = ORANGE_B;
            }
            mOrangeBuffer = buf;
        }
        return mOrangeBuffer;
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
            // [drmsample] Always run the pixel copy: it updates the black/bright counters that
            // [drmsample] drive the DRM fallback, so the detection costs nothing extra here.
            final byte[] pixels = getPixels(buffer, width, height, rowStride, pixelStride, mDownscaleFactor);

            // [drmsample] While a protected (DRM) layer hides the real content, the compositor only
            // [drmsample] hands us black frames. In that case send a solid orange image instead of
            // [drmsample] the meaningless black frame, so the LEDs show a defined state rather than
            // [drmsample] staying dark.
            if (isSendingOrange()) {
                mListener.sendFrame(getOrangeBuffer(), mScaledWidth, mScaledHeight);
            } else {
                mListener.sendFrame(pixels, mScaledWidth, mScaledHeight);
            }
        }
    }
}