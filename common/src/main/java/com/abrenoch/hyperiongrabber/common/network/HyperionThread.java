package com.abrenoch.hyperiongrabber.common.network;

import com.abrenoch.hyperiongrabber.common.HyperionScreenService;
import com.abrenoch.hyperiongrabber.common.HyperionProto;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated background thread responsible for socket connection, reconnection,
 * and asynchronous frame transmission to Hyperion.
 * Decoupled from the screen capture pipeline to ensure screen capture never stalls.
 */
public class HyperionThread extends Thread {
    private final String HOST;
    private final int PORT;
    private final int PRIORITY;
    private final int FRAME_DURATION = -1;
    private boolean RECONNECT = false;
    // [deepseek] Removed the HAS_CONNECTED flag: it made the first connect attempt a special case,
    // [deepseek] which is exactly what broke the boot scenario. RECONNECT alone now decides whether
    // [deepseek] the worker keeps retrying.
    private final int RECONNECT_DELAY;
    private Hyperion mHyperion;
    private volatile boolean mRunning = true;

    // Single-slot queue: Holds at most one pending frame.
    // If the network is slow, older unconsumed frames are dropped immediately to eliminate latency accumulation.
    private final ArrayBlockingQueue<Frame> mFrameQueue = new ArrayBlockingQueue<>(1);

    private static class Frame {
        final byte[] data;
        final int width;
        final int height;

        Frame(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    HyperionScreenService.HyperionThreadBroadcaster mSender;
    HyperionThreadListener mReceiver = new HyperionThreadListener() {
        @Override
        public void sendFrame(byte[] data, int width, int height) {
            if (!mRunning || mHyperion == null || !mHyperion.isConnected()) {
                return;
            }

            // [deepseek] Non-blocking hand-off: the encoder gives us a ping-pong buffer that it will
            // [deepseek] not touch again until the next frame, so no defensive clone() is needed.
            Frame frame = new Frame(data, width, height);

            // Discard any older unsent frame and offer the newest frame to the network worker
            mFrameQueue.poll();
            mFrameQueue.offer(frame);
        }

        @Override
        public void clear() {
            if (mHyperion != null && mHyperion.isConnected()) {
                try {
                    mHyperion.clear(PRIORITY);
                } catch (IOException e) {
                    if (mSender != null) {
                        mSender.onConnectionError(e.hashCode(), e.getMessage());
                    }
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void disconnect() {
            mRunning = false;
            interrupt();
            if (mHyperion != null) {
                try {
                    mHyperion.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void sendStatus(boolean isGrabbing) {
            if (mSender != null) {
                mSender.onReceiveStatus(isGrabbing);
            }
        }
    };

    public HyperionThread(HyperionScreenService.HyperionThreadBroadcaster listener, final String host,
                          final int port, final int priority, final boolean reconnect, final int delay) {
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
        super.interrupt();
    }

    @Override
    public void run() {
        while (mRunning && !isInterrupted()) {
            // 1. Establish or re-establish connection if disconnected
            if (mHyperion == null || !mHyperion.isConnected()) {
                try {
                    mHyperion = new Hyperion(HOST, PORT);
                    if (mSender != null) {
                        mSender.onConnected();
                    }
                } catch (IOException e) {
                    if (mSender != null) {
                        mSender.onConnectionError(e.hashCode(), e.getMessage());
                    }
                    // [deepseek] Retry no matter whether a connection ever succeeded. The old guard was
                    // [deepseek] "RECONNECT && HAS_CONNECTED", so a failure on the very first attempt
                    // [deepseek] (TV boots before the Hyperion server is reachable) dropped out of the
                    // [deepseek] loop and killed the thread for good. Together with "start on boot" the
                    // [deepseek] grabber then stayed dark until the app was restarted by hand, even
                    // [deepseek] though RECONNECT was enabled.
                    if (!RECONNECT) break;
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    continue;
                }
            }

            // 2. Wait for the next frame from the capture pipeline (non-busy wait with timeout)
            Frame frame;
            try {
                frame = mFrameQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }

            // 3. Serialize and transmit frame over socket on this background thread
            if (frame != null && mHyperion != null && mHyperion.isConnected()) {
                try {
                    HyperionProto.HyperionRequest req =
                            Hyperion.setImageRequest(frame.data, frame.width, frame.height, PRIORITY, FRAME_DURATION);
                    mHyperion.sendRequest(req);
                } catch (IOException e) {
                    if (mSender != null) {
                        mSender.onConnectionError(e.hashCode(), e.getMessage());
                    }
                    try {
                        mHyperion.disconnect();
                    } catch (Exception ignored) {}
                    mHyperion = null;

                    // Reconnection delay runs here on the network worker, avoiding stalls on the capture thread
                    // [deepseek] Same rule as in the connect block above: only RECONNECT decides.
                    if (!RECONNECT) break;
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }

        // Cleanup on thread shutdown
        if (mHyperion != null) {
            try {
                mHyperion.disconnect();
            } catch (Exception ignored) {}
            mHyperion = null;
        }
    }

    public interface HyperionThreadListener {
        void sendFrame(byte[] data, int width, int height);
        void clear();
        void disconnect();
        void sendStatus(boolean isGrabbing);
    }
}
