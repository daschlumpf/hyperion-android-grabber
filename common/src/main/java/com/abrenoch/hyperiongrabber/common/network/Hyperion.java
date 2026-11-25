package com.abrenoch.hyperiongrabber.common.network;

import com.abrenoch.hyperiongrabber.common.HyperionProto.ClearRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.ColorRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.HyperionRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.ImageRequest;
import com.google.protobuf.ByteString;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Hyperion {
    private static final int CONNECT_TIMEOUT = 1000;
    private static final int SO_TIMEOUT = 2000;
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    private final Socket mSocket;
    private final OutputStream mOutput;
    private final InputStream mInput;
    private final Object mWriteLock = new Object();

    // Reusable buffers for zero-allocation frame serialization (guarded by mWriteLock)
    private final byte[] mHeaderBuf = new byte[4];
    private final byte[] mProtoPrefix = new byte[64];
    private final byte[] mProtoSuffix = new byte[16];

    public Hyperion(String address, int port) throws IOException {
        mSocket = new Socket();
        // Disable Nagle's algorithm to ensure immediate frame dispatch without artificial 20-40ms latency
        mSocket.setTcpNoDelay(true);
        mSocket.setSoTimeout(SO_TIMEOUT);
        mSocket.setKeepAlive(true);
        mSocket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT);
        mOutput = new BufferedOutputStream(mSocket.getOutputStream(), OUTPUT_BUFFER_SIZE);
        mInput = mSocket.getInputStream();
    }


    @Override
    protected void finalize() throws Throwable {
        disconnect();
        super.finalize();
    }


    public boolean isConnected() {
        // [deepseek] Socket.isConnected() only reports whether the socket was connected ONCE; it
        // [deepseek] stays true after close(). A locally closed socket therefore still looked
        // [deepseek] healthy, so the reconnect logic in HyperionThread never noticed that its
        // [deepseek] connection was gone until the next write failed. isClosed() is the missing
        // [deepseek] part of the check. The null guard stays as a cheap safety net.
        //noinspection ConstantConditions for some reason mSocket CAN be null sometimes
        return mSocket != null && mSocket.isConnected() && !mSocket.isClosed();
    }

    // Intentionally NOT synchronized on mWriteLock: Socket.close() must be able to interrupt
    // a blocked write/flush immediately without deadlocking on a stalled TCP connection.
    public void disconnect() throws IOException {
        if (mSocket != null && !mSocket.isClosed()) {
            mSocket.close();
        }
    }

    public void clear(int priority) throws IOException {
        sendRequest(clearRequest(priority));
    }
    public static HyperionRequest clearRequest(int priority) {
        ClearRequest clearRequest = ClearRequest.newBuilder()
                .setPriority(priority)
                .build();

        return HyperionRequest.newBuilder()
                .setCommand(HyperionRequest.Command.CLEAR)
                .setExtension(ClearRequest.clearRequest, clearRequest)
                .build();
    }


    public void clearAll() throws IOException {
        sendRequest(clearAllRequest());
    }
    public static HyperionRequest clearAllRequest() {
        return HyperionRequest.newBuilder()
                .setCommand(HyperionRequest.Command.CLEARALL)
                .build();
    }


    public void setColor(int color, int priority) throws IOException {
        setColor(color, priority, -1);
    }
    public void setColor(int color, int priority, int duration_ms) throws IOException {
        sendRequest(setColorRequest(color, priority, duration_ms));
    }
    public static HyperionRequest setColorRequest(int color, int priority, int duration_ms) {
        ColorRequest colorRequest = ColorRequest.newBuilder()
                .setRgbColor(color)
                .setPriority(priority)
                .setDuration(duration_ms)
                .build();

        return HyperionRequest.newBuilder()
                .setCommand(HyperionRequest.Command.COLOR)
                .setExtension(ColorRequest.colorRequest, colorRequest)
                .build();
    }


    public void setImage(byte[] data, int width, int height, int priority) throws IOException {
        setImage(data, width, height, priority, -1);
    }
    public void setImage(byte[] data, int width, int height, int priority, int duration_ms) throws IOException {
        setImageRaw(data, data.length, width, height, priority, duration_ms);
    }

    /**
     * Zero-allocation protobuf wire encoder for HyperionRequest(IMAGE).
     * Writes the exact same wire format as {@link #setImageRequest} directly into the buffered
     * socket output stream without ByteString.copyFrom(), Protobuf builders, or CodedOutputStream
     * heap allocations.
     */
    public void setImageRaw(byte[] data, int dataLen, int width, int height, int priority, int duration_ms) throws IOException {
        synchronized (mWriteLock) {
            if (!isConnected()) return;

            final int imageRequestSize =
                    (1 + varintSize64(priority))
                    + (1 + varintSize64(width))
                    + (1 + varintSize64(height))
                    + (1 + varintSize32(dataLen) + dataLen)
                    + (1 + varintSize64(duration_ms));

            final int totalSize = 2 + 1 + varintSize32(imageRequestSize) + imageRequestSize;

            final byte[] prefix = mProtoPrefix;
            int p = 0;
            // 4-byte big-endian message length framing
            prefix[p++] = (byte) ((totalSize >> 24) & 0xFF);
            prefix[p++] = (byte) ((totalSize >> 16) & 0xFF);
            prefix[p++] = (byte) ((totalSize >> 8) & 0xFF);
            prefix[p++] = (byte) (totalSize & 0xFF);

            // HyperionRequest.command = IMAGE (field 1, varint = 2)
            prefix[p++] = 0x08;
            prefix[p++] = 0x02;

            // HyperionRequest.imageRequest extension (field 11, length-delimited -> (11 << 3) | 2 = 0x5A)
            prefix[p++] = 0x5A;
            p = writeVarint32(prefix, p, imageRequestSize);

            // ImageRequest.priority (field 1, int32)
            prefix[p++] = 0x08;
            p = writeVarint64(prefix, p, priority);

            // ImageRequest.imagewidth (field 2, int32)
            prefix[p++] = 0x10;
            p = writeVarint64(prefix, p, width);

            // ImageRequest.imageheight (field 3, int32)
            prefix[p++] = 0x18;
            p = writeVarint64(prefix, p, height);

            // ImageRequest.imagedata (field 4, bytes)
            prefix[p++] = 0x22;
            p = writeVarint32(prefix, p, dataLen);

            // ImageRequest.duration (field 5, optional int32)
            final byte[] suffix = mProtoSuffix;
            int s = 0;
            suffix[s++] = 0x28;
            s = writeVarint64(suffix, s, duration_ms);

            mOutput.write(prefix, 0, p);
            mOutput.write(data, 0, dataLen);
            mOutput.write(suffix, 0, s);
            mOutput.flush();

            drainReplies();
        }
    }

    public static HyperionRequest setImageRequest(byte[] data, int width, int height, int priority, int duration_ms) {
        ImageRequest imageRequest = ImageRequest.newBuilder()
                .setImagedata(ByteString.copyFrom(data))
                .setImagewidth(width)
                .setImageheight(height)
                .setPriority(priority)
                .setDuration(duration_ms)
                .build();

        return HyperionRequest.newBuilder()
                .setCommand(HyperionRequest.Command.IMAGE)
                .setExtension(ImageRequest.imageRequest, imageRequest)
                .build();
    }

    // Synchronized on mWriteLock to prevent concurrent writes on the TCP socket output stream
    public void sendRequest(HyperionRequest request) throws IOException {
        synchronized (mWriteLock) {
            if (isConnected()) {
                int size = request.getSerializedSize();

                // Reuse 4-byte framing header buffer
                mHeaderBuf[0] = (byte) ((size >> 24) & 0xFF);
                mHeaderBuf[1] = (byte) ((size >> 16) & 0xFF);
                mHeaderBuf[2] = (byte) ((size >> 8) & 0xFF);
                mHeaderBuf[3] = (byte) (size & 0xFF);

                // Write framing header + payload via BufferedOutputStream, then flush once
                mOutput.write(mHeaderBuf, 0, 4);
                request.writeTo(mOutput);
                mOutput.flush();

                drainReplies();
            }
        }
    }

    private void drainReplies() {
        // Non-blocking reply drain:
        // Hyperion replies to requests, but video streaming does not need synchronous per-frame ACKs.
        // We drain any available reply bytes here so the OS TCP receive window does not stall.
        try {
            int available = mInput.available();
            while (available > 0) {
                long skipped = mInput.skip(available);
                if (skipped <= 0) break;
                available = mInput.available();
            }
        } catch (IOException ignored) {}
    }

    private static int varintSize32(int value) {
        if ((value & ~0x7F) == 0) return 1;
        if ((value & ~0x3FFF) == 0) return 2;
        if ((value & ~0x1FFFFF) == 0) return 3;
        if ((value & ~0x0FFFFFFF) == 0) return 4;
        return 5;
    }

    private static int varintSize64(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int writeVarint32(byte[] buf, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        return pos;
    }

    private static int writeVarint64(byte[] buf, int pos, long value) {
        while ((value & ~0x7FL) != 0) {
            buf[pos++] = (byte) (((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        return pos;
    }
}