package com.abrenoch.hyperiongrabber.common.util;

import android.util.Log;

public class HyperionGrabberOptions {
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionGrabberOptions";

    private final int MINIMUM_IMAGE_PACKET_SIZE; // how many bytes the minimal acceptable image quality is
    private final int FRAME_RATE;
    private final boolean USE_AVERAGE_COLOR;
    private final boolean USE_LEGACY_IMAGE_READER;
    private final int BLACK_THRESHOLD = 5; // The limit each RGB value must be under to be considered a black pixel [0-255]
    private final String RESOLUTION;
    public HyperionGrabberOptions(int horizontalLED, int verticalLED, int frameRate, boolean useAvgColor, boolean useLegacyImageReader, String resolution) {

        /*
        * To determine the minimal acceptable image packet size we take the count of the width & height
        * of the LED pixels (that the user is driving via their hyperion server) and multiply them
        * together and then by 3 (1 for each color in RGB). This will give us the count of the bytes
        * that the minimal acceptable quality should be equal to or greater than.
        **/
        MINIMUM_IMAGE_PACKET_SIZE = horizontalLED * verticalLED * 3;
        FRAME_RATE = frameRate;
        USE_AVERAGE_COLOR = useAvgColor;
        USE_LEGACY_IMAGE_READER = useLegacyImageReader;
        RESOLUTION = resolution;

        if (DEBUG) {
            Log.d(TAG, "Horizontal LED Count: " + String.valueOf(horizontalLED));
            Log.d(TAG, "Vertical LED Count: " + String.valueOf(verticalLED));
            Log.d(TAG, "Minimum Image Packet: " + String.valueOf(MINIMUM_IMAGE_PACKET_SIZE));
        }
    }

    public int getFrameRate() { return FRAME_RATE; }

    public boolean useAverageColor() { return USE_AVERAGE_COLOR; }

    public boolean useLegacyImageReader() { return USE_LEGACY_IMAGE_READER; }

    public String Resolution() { return RESOLUTION; }
    /**
    * returns the divisor best suited to be used to meet the minimum image packet size
    *     Since we only want to scale using whole numbers, we need to find what common divisors
    *     are available for the given width & height. We will check those divisors to find the smallest
    *     number (that we can divide our screen dimensions by) that would meet the minimum image
    *     packet size required to match the count of the LEDs on the destination hyperion server.
    * @param width The original width of the device screen
    * @param height  The original height of the device screen
    * @return int The divisor bes suited to scale the screen dimensions by
    **/
    public int findDivisor(int width, int height) {
        // [deepseek] Iterate downwards from the largest possible divisor and return the first one
        // [deepseek] that still yields at least the minimum image packet size. This replaces the
        // [deepseek] old implementation that built a full ArrayList of common divisors on every
        // [deepseek] call; it now runs allocation-free and stops at the first match.
        int min = Math.min(width, height);
        for (int i = min; i > 1; i--) {
            if (width % i != 0 || height % i != 0) continue;
            if ((width / i) * (height / i) * 3 >= MINIMUM_IMAGE_PACKET_SIZE) return i;
        }
        return 1;
    }

    public int getBlackThreshold() { return BLACK_THRESHOLD; }
}
