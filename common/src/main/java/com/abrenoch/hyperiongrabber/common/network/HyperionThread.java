package com.abrenoch.hyperiongrabber.common.network;

import com.abrenoch.hyperiongrabber.common.HyperionScreenService;

import java.io.IOException;

/**
 * Dedicated background thread responsible for socket connection, reconnection,
 * and asynchronous frame transmission to Hyperion.
 * Uses a zero-allocation triple-buffer handoff so screen capture never stalls
 * and never races with network serialization.
 */
public class HyperionThread extends Thread {
    private final String HOST;
    private final int PORT;
    private final int PRIORITY;
    private final int FRAME_DURATION = -1;
    private final boolean RECONNECT;
    private final int RECONNECT_DELAY;

    private volatile Hyperion mHyperion;
    private volatile boolean mRunning = true;
    private volatile boolean mConnected = false;

    // Lightweight monitor held only for nanosecond-scale reference swaps (never during socket I/O)
    private final Object mQueueLock = new Object();
    private byte[] mPendingData;
    private int mPendingWidth;
    private int mPendingHeight;
    private boolean mPendingReusable;
    private boolean mHasPendingFrame;
    private boolean mClearRequested;

    // 2-slot free pool for triple-buffering (3rd buffer is held by capture or network thread)
    private byte[] mFree0;
    private byte[] mFree1;
    private int mExpectedBufferLength;

    HyperionScreenService.HyperionThreadBroadcaster mSender;
    HyperionThreadListener mReceiver = new HyperionThreadListener() {
        @Override
        public boolean isConnected() {
            return mRunning && mConnected;
        }

        @Override
        public void initBuffers(byte[] free0, byte[] free1) {
            synchronized (mQueueLock) {
                mExpectedBufferLength = free0 != null ? free0.length : 0;
                mFree0 = free0;
                mFree1 = free1;
                mPendingData = null;
                mHasPendingFrame = false;
                mPendingReusable = false;
            }
        }

        @Override
        public byte[] sendFrame(byte[] data, int width, int height, boolean reusable) {
            if (!mRunning || !mConnected) {
                return reusable ? data : null;
            }

            synchronized (mQueueLock) {
                if (mHasPendingFrame && mPendingReusable) {
                    pushFreeLocked(mPendingData);
                }
                mPendingData = data;
                mPendingWidth = width;
                mPendingHeight = height;
                mPendingReusable = reusable;
                mHasPendingFrame = true;
                mQueueLock.notify();

                if (reusable) {
                    byte[] next = popFreeLocked();
                    return next != null ? next : new byte[data.length];
                }
                return null;
            }
        }

        @Override
        public void clear() {
            synchronized (mQueueLock) {
                if (mHasPendingFrame && mPendingReusable) {
                    pushFreeLocked(mPendingData);
                }
                mPendingData = null;
                mHasPendingFrame = false;
                mPendingReusable = false;
                mClearRequested = true;
                mQueueLock.notify();
            }
        }

        @Override
        public void disconnect() {
            interrupt();
        }

        @Override
        public void sendStatus(boolean isGrabbing) {
            if (mSender != null) {
                mSender.onReceiveStatus(isGrabbing);
            }
        }
    };

    private void pushFreeLocked(byte[] buf) {
        if (buf == null || buf.length != mExpectedBufferLength) return;
        if (mFree0 == null) {
            mFree0 = buf;
        } else if (mFree1 == null) {
            mFree1 = buf;
        }
    }

    private byte[] popFreeLocked() {
        if (mFree0 != null) {
            byte[] b = mFree0;
            mFree0 = mFree1;
            mFree1 = null;
            return b;
        }
        return null;
    }

    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster listener, final String host,
                          final int port, final int priority, final boolean reconnect, final int delay) {
        super("HyperionNetwork");
        HOST = host;
        PORT = port;
        PRIORITY = priority;
        RECONNECT = reconnect;
        RECONNECT_DELAY = delay * 1000;
        mSender = listener;
    }

    public HyperionThreadListener getReceiver() {
        return mReceiver;
    }

    @Override
    public void interrupt() {
        mRunning = false;
        mConnected = false;
        synchronized (mQueueLock) {
            mQueueLock.notifyAll();
        }
        super.interrupt();
        Hyperion h = mHyperion;
        if (h != null) {
            try {
                h.disconnect();
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void run() {
        while (mRunning && !isInterrupted()) {
            // 1. Establish or re-establish connection if disconnected
            Hyperion hyperion = mHyperion;
            if (hyperion == null || !hyperion.isConnected()) {
                mConnected = false;
                try {
                    hyperion = new Hyperion(HOST, PORT);
                    mHyperion = hyperion;
                    mConnected = true;
                    if (mSender != null) {
                        mSender.onConnected();
                    }
                } catch (IOException e) {
                    mConnected = false;
                    if (mSender != null) {
                        mSender.onConnectionError(e.hashCode(), e.getMessage());
                    }
                    if (!RECONNECT) break;
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    continue;
                }
            }

            // 2. Wait for the next frame or clear command without polling wakeups
            byte[] data = null;
            int width = 0;
            int height = 0;
            boolean reusable = false;
            boolean doClear = false;

            synchronized (mQueueLock) {
                while (mRunning && !isInterrupted() && !mHasPendingFrame && !mClearRequested) {
                    try {
                        mQueueLock.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!mRunning || isInterrupted()) break;

                if (mClearRequested) {
                    doClear = true;
                    mClearRequested = false;
                } else if (mHasPendingFrame) {
                    data = mPendingData;
                    width = mPendingWidth;
                    height = mPendingHeight;
                    reusable = mPendingReusable;
                    mPendingData = null;
                    mHasPendingFrame = false;
                    mPendingReusable = false;
                }
            }

            // 3. Transmit over socket outside mQueueLock
            try {
                if (doClear) {
                    hyperion.clear(PRIORITY);
                } else if (data != null) {
                    hyperion.setImageRaw(data, data.length, width, height, PRIORITY, FRAME_DURATION);
                }
            } catch (IOException e) {
                mConnected = false;
                if (mSender != null) {
                    mSender.onConnectionError(e.hashCode(), e.getMessage());
                }
                try {
                    hyperion.disconnect();
                } catch (Exception ignored) {}
                mHyperion = null;

                if (!RECONNECT) break;
                try {
                    Thread.sleep(RECONNECT_DELAY);
                } catch (InterruptedException ie) {
                    break;
                }
            } finally {
                if (reusable && data != null) {
                    synchronized (mQueueLock) {
                        pushFreeLocked(data);
                    }
                }
            }
        }

        // Cleanup on thread shutdown
        mConnected = false;
        Hyperion h = mHyperion;
        if (h != null) {
            try {
                h.disconnect();
            } catch (Exception ignored) {}
            mHyperion = null;
        }
    }

    public interface HyperionThreadListener {
        boolean isConnected();
        void initBuffers(byte[] free0, byte[] free1);
        byte[] sendFrame(byte[] data, int width, int height, boolean reusable);
        void clear();
        void disconnect();
        void sendStatus(boolean isGrabbing);
    }
}
