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

    // Auto-recovery timings for Android TV / MStar HAL standby or BufferQueue stalls
    private static final long SCREEN_ON_CHECK_DELAY_MS = 2500L;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;
    private static final long STALL_TIMEOUT_NS = 20_000_000_000L;
    private static final int MAX_CONSECUTIVE_RECOVERIES = 3;

    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private volatile long mLastImageTimeNs = System.nanoTime();
    private volatile boolean mScreenOn = true;
    private volatile boolean mStopped = false;
    private int mConsecutiveRecoveries = 0;

    int mScaledWidth;
    int mScaledHeight;
    // Triple-buffered frame pool: capture thread owns mCaptureBuffer while the network thread
    // and its pending slot own the other two buffers, eliminating both per-frame allocations
    // and overwrite races when the network stalls.
    private byte[] mCaptureBuffer;
    // Scratch row buffer for bulk reads of one full source row (works even with GPU row padding).
    private byte[] mRowBuffer;
    // Reused 3-byte buffer for the average-color path.
    private final byte[] mAvgColorBuffer = new byte[3];

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
        // int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;// | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
        // flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        // flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        //   | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        // flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

        // Use AUTO_MIRROR to capture system content.
        final int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

        // [deepseek] getGrabberWidth()/getGrabberHeight() return the capture resolution selected by
        // [deepseek] the resolution preference (160x90 ... 1920x1080). Whether it is reduced before
        // [deepseek] being sent is decided by PASSTHROUGH_1_TO_1 in HyperionScreenEncoderBase.
        setImageReader();

        // Pass the ImageReader Surface directly during creation and dispatch callbacks on mHandler
        // to avoid pipeline stalls or secondary display init cycles on TV display HALs.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                getGrabberWidth(), 
                getGrabberHeight(), 
                mDensity,
                flags,
                mImageReader.getSurface(), 
                mDisplayCallback,
                mHandler);

        mLastImageTimeNs = System.nanoTime();
        scheduleWatchdog();
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "stopRecording Called");
        mStopped = true;
        setCapturing(false);

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

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

    /** Called by HyperionScreenService on ACTION_SCREEN_OFF. */
    public void onScreenOff() {
        mScreenOn = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mWatchdogRunnable);
            mHandler.removeCallbacks(mScreenOnVerifyRunnable);
        }
    }

    /**
     * Called by HyperionScreenService on ACTION_SCREEN_ON.
     * Drains stale frames and verifies that the display HAL resumes delivering frames;
     * if no frame arrives within SCREEN_ON_CHECK_DELAY_MS, rebinds a fresh ImageReader surface.
     */
    public void onScreenOn() {
        if (mStopped || mHandler == null) return;
        mScreenOn = true;
        mConsecutiveRecoveries = 0;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mStopped) return;
                drainLatestImageLocked();
                mNextFrameDeadline = System.nanoTime();
                setCapturing(true);
                mScreenOnBaselineNs = mLastImageTimeNs;
                mHandler.removeCallbacks(mScreenOnVerifyRunnable);
                mHandler.postDelayed(mScreenOnVerifyRunnable, SCREEN_ON_CHECK_DELAY_MS);
                scheduleWatchdog();
            }
        });
    }

    private long mScreenOnBaselineNs;

    private final Runnable mScreenOnVerifyRunnable = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public void run() {
            if (mStopped || !mScreenOn) return;
            if (mLastImageTimeNs == mScreenOnBaselineNs) {
                recoverImageReader("no frames after screen-on");
            }
        }
    };

    private final Runnable mWatchdogRunnable = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public void run() {
            if (mStopped || !mScreenOn) return;
            if (isCapturing()) {
                long silentNs = System.nanoTime() - mLastImageTimeNs;
                if (silentNs >= STALL_TIMEOUT_NS && mConsecutiveRecoveries < MAX_CONSECUTIVE_RECOVERIES) {
                    mConsecutiveRecoveries++;
                    recoverImageReader("stalled for " + (silentNs / 1_000_000_000L) + "s");
                }
            }
            scheduleWatchdog();
        }
    };

    private void scheduleWatchdog() {
        if (!mStopped && mScreenOn && mHandler != null) {
            mHandler.removeCallbacks(mWatchdogRunnable);
            mHandler.postDelayed(mWatchdogRunnable, WATCHDOG_INTERVAL_MS);
        }
    }

    private void drainLatestImageLocked() {
        if (mImageReader == null) return;
        Image img = null;
        try {
            img = mImageReader.acquireLatestImage();
        } catch (Exception ignored) {
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /**
     * Rebinds a fresh ImageReader BufferQueue onto the existing VirtualDisplay without recreating
     * the VirtualDisplay itself (which avoids leaking mirror displays on MStar Android 11 HALs).
     * Must run on mHandler.
     */
    @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
    private void recoverImageReader(String reason) {
        if (mStopped || mVirtualDisplay == null) return;
        Log.w(TAG, "Recovering ImageReader (" + reason + ")");
        try {
            mVirtualDisplay.setSurface(null);
            if (mImageReader != null) {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
                mImageReader = null;
            }
            createImageReaderInstance();
            mVirtualDisplay.setSurface(mImageReader.getSurface());
            mNextFrameDeadline = System.nanoTime();
            setCapturing(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to recover ImageReader", e);
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "resumeRecording Called");
        if (!mStopped && !isCapturing() && mHandler != null) {
            // Drain stale frames on the ImageReader's own handler thread to avoid cross-thread races
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!mStopped && !isCapturing() && mImageReader != null) {
                        if (DEBUG) Log.i(TAG, "Resuming reading images");
                        drainLatestImageLocked();
                        mNextFrameDeadline = System.nanoTime();
                        setCapturing(true);
                    }
                }
            });
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
    private void createImageReaderInstance() {
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

        mImageReader.setOnImageAvailableListener(imageAvailableListener, mHandler);
    }

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

        createImageReaderInstance();

        mScaledWidth = width / mDownscaleFactor;
        mScaledHeight = height / mDownscaleFactor;

        // Allocate triple-buffered RGB frames up front (0 per-frame allocations on the hot path)
        final int frameBytes = mScaledWidth * mScaledHeight * 3;
        mCaptureBuffer = new byte[frameBytes];
        if (mListener != null) {
            mListener.initBuffers(new byte[frameBytes], new byte[frameBytes]);
        }
        // [drmsample] The orange buffer is sized to the scaled dimensions, so it must be rebuilt
        // [drmsample] whenever the capture size changes; also reset the detection state so a
        // [drmsample] restart begins with a clean slate.
        mOrangeBuffer = null;
        mBlackFrameCount = 0;
        mBlackSinceNs = 0L;
        mBrightFrameCount = 0;
        mSendingOrange = false;
        // One full RGBA row of the source image, reused for every row of every frame.
        mRowBuffer = new byte[width * 4];

        if (DEBUG) Log.d(TAG, "mCaptureBuffer " + frameBytes);

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
    private static final boolean STATS = false;
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
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;

                long now = System.nanoTime();
                mLastImageTimeNs = now;
                mConsecutiveRecoveries = 0;

                if (mListener != null && isCapturing()) {
                    countImage(img);
                    if (now >= mNextFrameDeadline) {
                        // Skip expensive pixel conversion while disconnected/reconnecting
                        if (mListener.isConnected()) {
                            sendImage(img);
                            mFramesSent++;
                        }
                        mNextFrameDeadline += mFramePeriodNanos;

                        // If we fell more than one whole period behind (screen off,
                        // long pause, GC hiccup) resync instead of firing a burst of
                        // frames back to back to "catch up".
                        if (now - mNextFrameDeadline > mFramePeriodNanos) {
                            mNextFrameDeadline = now + mFramePeriodNanos;
                        }
                    }
                }
            } catch (final Exception e) {
                if (DEBUG) Log.w(TAG, "sendImage exception:", e);
            } finally {
                // Always close the acquired Image so MAX_IMAGE_READER_IMAGES can never leak and stall the pipeline
                if (img != null) {
                    img.close();
                }
            }
        }
    };

    private void getPixels(ByteBuffer buffer, byte[] out, int width, int height,
                           int rowStride, int pixelStride, int downscaleFactor) {
        int outputIndex = 0;
        final int rowBytes = width * 4;
        // Bulk row read works whenever pixels are tightly packed RGBA (pixelStride == 4),
        // even if the GPU pads rowStride to 64/128/256-byte alignment.
        final boolean bulkRowRead = pixelStride == 4 && rowStride >= rowBytes;

        if (bulkRowRead && downscaleFactor == 1) {
            final byte[] row = mRowBuffer;
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, rowBytes);
                for (int src = 0; src < rowBytes; src += 4) {
                    out[outputIndex++] = row[src];
                    out[outputIndex++] = row[src + 1];
                    out[outputIndex++] = row[src + 2];
                }
            }
        } else if (bulkRowRead) {
            final byte[] row = mRowBuffer;
            final int srcStep = 4 * downscaleFactor;
            for (int y = 0; y < height; y += downscaleFactor) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, rowBytes);
                for (int src = 0; src < rowBytes; src += srcStep) {
                    out[outputIndex++] = row[src];
                    out[outputIndex++] = row[src + 1];
                    out[outputIndex++] = row[src + 2];
                }
            }
        } else {
            final int srcStep = pixelStride * downscaleFactor;
            for (int y = 0; y < height; y += downscaleFactor) {
                final int rowStart = y * rowStride;
                for (int x = 0, src = 0; x < width; x += downscaleFactor, src += srcStep) {
                    final int pixelIndex = rowStart + src;
                    out[outputIndex++] = buffer.get(pixelIndex);
                    out[outputIndex++] = buffer.get(pixelIndex + 1);
                    out[outputIndex++] = buffer.get(pixelIndex + 2);
                }
            }
        }
    }

    /**
     * Fast DRM brightness probe directly on the source ByteBuffer (~60 pixels at 160x90).
     * Used while DRM orange fallback is active so we avoid copying the entire frame.
     */
    private void probeDrmDirect(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride) {
        int sampleMax = 0;
        int brightSamples = 0;
        int sampleCount = 0;

        for (int y = 0; y < height; y += SAMPLE_STEP) {
            final int rowStart = y * rowStride;
            for (int x = 0; x < width; x += SAMPLE_STEP) {
                final int idx = rowStart + x * pixelStride;
                final int rr = buffer.get(idx) & 0xFF;
                final int gg = buffer.get(idx + 1) & 0xFF;
                final int bb = buffer.get(idx + 2) & 0xFF;
                final int max = rr > gg ? (rr > bb ? rr : bb) : (gg > bb ? gg : bb);
                if (max > sampleMax) sampleMax = max;
                if (max > BLACK_SAMPLE_THRESHOLD) brightSamples++;
                sampleCount++;
            }
        }

        updateDrmState(sampleMax, brightSamples, sampleCount);
    }

    /**
     * Lightweight DRM brightness probe over the already-copied RGB frame (~60 array reads in L1 cache),
     * keeping the hot pixel-copy loop completely branch-free.
     */
    private void probeDrmFromRgb(byte[] rgb, int scaledWidth, int scaledHeight, int downscaleFactor) {
        final int step = Math.max(1, SAMPLE_STEP / downscaleFactor);
        int sampleMax = 0;
        int brightSamples = 0;
        int sampleCount = 0;

        for (int y = 0; y < scaledHeight; y += step) {
            final int rowOffset = y * scaledWidth * 3;
            for (int x = 0; x < scaledWidth; x += step) {
                final int idx = rowOffset + x * 3;
                final int rr = rgb[idx] & 0xFF;
                final int gg = rgb[idx + 1] & 0xFF;
                final int bb = rgb[idx + 2] & 0xFF;
                final int max = rr > gg ? (rr > bb ? rr : bb) : (gg > bb ? gg : bb);
                if (max > sampleMax) sampleMax = max;
                if (max > BLACK_SAMPLE_THRESHOLD) brightSamples++;
                sampleCount++;
            }
        }

        updateDrmState(sampleMax, brightSamples, sampleCount);
    }

    // [drmsample] --- DRM / protected-content detection --------------------------------
    // True DRM blackout from the compositor produces channel value 0; keep a tiny margin (4)
    // so dark movie scenes do not falsely trigger the orange fallback.
    private static final int BLACK_SAMPLE_THRESHOLD = 4;
    // Sampling stride in *source* pixels.
    private static final int SAMPLE_STEP = 16;
    // Require both a minimum frame count and 2.5 seconds of continuous true-black frames
    // before switching to orange, so fades-to-black, scene cuts, and brief MStar decoder
    // hiccups never cause 2-second orange flashes.
    private static final int BLACK_FRAMES_BEFORE_ORANGE = 10;
    private static final long BLACK_DURATION_BEFORE_ORANGE_NS = 2_500_000_000L;
    // Number of consecutive bright frames before returning to normal capture.
    private static final int BRIGHT_FRAMES_BEFORE_NORMAL = 3;
    // Orange in RGB (255, 140, 0).
    private static final byte ORANGE_R = (byte) 0xFF;
    private static final byte ORANGE_G = (byte) 0x8C;
    private static final byte ORANGE_B = (byte) 0x00;

    private int mBlackFrameCount;
    private long mBlackSinceNs;
    private int mBrightFrameCount;
    private boolean mSendingOrange;
    // Orange frame buffer, reused; same size as the RGB frame buffers.
    private byte[] mOrangeBuffer;

    /**
     * Updates the black/bright frame counters from one frame's sampling result and switches the
     * DRM orange fallback on/off accordingly.
     */
    private void updateDrmState(int sampleMax, int brightSamples, int sampleCount) {
        if (!mDrmOrangeFallback) {
            mBlackFrameCount = 0;
            mBlackSinceNs = 0L;
            mBrightFrameCount = 0;
            mSendingOrange = false;
            return;
        }
        if (sampleCount == 0) return;

        final boolean black = brightSamples == 0;

        if (black) {
            mBrightFrameCount = 0;
            final long now = System.nanoTime();
            if (mBlackFrameCount == 0) {
                mBlackSinceNs = now;
            }
            if (mBlackFrameCount < Integer.MAX_VALUE) mBlackFrameCount++;
            if (!mSendingOrange
                    && mBlackFrameCount >= BLACK_FRAMES_BEFORE_ORANGE
                    && (now - mBlackSinceNs) >= BLACK_DURATION_BEFORE_ORANGE_NS) {
                mSendingOrange = true;
                Log.i(TAG, "DRM fallback ON: " + mBlackFrameCount
                        + " consecutive black frames over " + ((now - mBlackSinceNs) / 1_000_000L)
                        + " ms (max channel " + sampleMax + "), sending orange");
            }
        } else {
            mBlackFrameCount = 0;
            mBlackSinceNs = 0L;
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

    private void sendImage(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int width = img.getWidth();
        int height = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();

        // Fast path while DRM orange fallback is active: only probe the ~60 sample pixels
        // instead of copying the entire frame into RGB.
        boolean drmProbed = false;
        if (mDrmOrangeFallback && mSendingOrange) {
            probeDrmDirect(buffer, width, height, rowStride, pixelStride);
            drmProbed = true;
            if (isSendingOrange()) {
                mListener.sendFrame(getOrangeBuffer(), mScaledWidth, mScaledHeight, false);
                return;
            }
        }

        final byte[] out = mCaptureBuffer;
        getPixels(buffer, out, width, height, rowStride, pixelStride, mDownscaleFactor);

        if (mDrmOrangeFallback && !drmProbed) {
            probeDrmFromRgb(out, mScaledWidth, mScaledHeight, mDownscaleFactor);
        }

        if (isSendingOrange()) {
            mListener.sendFrame(getOrangeBuffer(), mScaledWidth, mScaledHeight, false);
        } else {
            mCaptureBuffer = mListener.sendFrame(out, mScaledWidth, mScaledHeight, true);
        }
    }
}