package com.abrenoch.hyperiongrabber.common.network;

import com.abrenoch.hyperiongrabber.common.HyperionProto.ClearRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.ColorRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.HyperionRequest;
import com.abrenoch.hyperiongrabber.common.HyperionProto.ImageRequest;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Hyperion {
    private final int TIMEOUT = 1000;
    private final Socket mSocket;


    public Hyperion(String address, int port) throws IOException {
        mSocket = new Socket();
        // Disable Nagle's algorithm to ensure immediate frame dispatch without artificial 20-40ms latency
        mSocket.setTcpNoDelay(true);
        mSocket.connect(new InetSocketAddress(address, port), TIMEOUT);
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

    // Synchronized to prevent closing the socket concurrently during write operations
    public synchronized void disconnect() throws IOException {
        if (isConnected()) {
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
        sendRequest(setImageRequest(data, width, height, priority, duration_ms));
    }
    public static HyperionRequest setImageRequest(byte[] data, int width, int height, int priority, int duration_ms) {
        // [deepseek] Correction of an earlier comment: ByteString.copyFrom(byte[]) does NOT wrap the
        // [deepseek] array, it makes a defensive copy (only the package-private ByteString.wrap()
        // [deepseek] would be zero-copy). With a 32x18 LED matrix the copy is 1728 bytes per frame,
        // [deepseek] so this is irrelevant in practice and no rewrite is needed.
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


    // Synchronized to prevent concurrent writes on the TCP socket output stream
    public synchronized void sendRequest(HyperionRequest request) throws IOException {
        if (isConnected()) {
            int size = request.getSerializedSize();

            // create the header
            byte[] header = new byte[4];
            header[0] = (byte)((size >> 24) & 0xFF);
            header[1] = (byte)((size >> 16) & 0xFF);
            header[2] = (byte)((size >>  8) & 0xFF);
            header[3] = (byte)((size  ) & 0xFF);

            // write the data to the socket
            OutputStream output = mSocket.getOutputStream();
            output.write(header);
            request.writeTo(output);
            output.flush();

            // Non-blocking reply drain:
            // Hyperion replies to requests, but video streaming does not need synchronous per-frame ACKs.
            // Synchronously waiting for replies blocks the stream and adds network round-trip latency.
            // We drain any available reply bytes here so the OS TCP receive window does not stall.
            try {
                InputStream input = mSocket.getInputStream();
                int available = input.available();
                while (available > 0) {
                    long skipped = input.skip(available);
                    if (skipped <= 0) break;
                    available = input.available();
                }
            } catch (IOException ignored) {}
        }
    }
}