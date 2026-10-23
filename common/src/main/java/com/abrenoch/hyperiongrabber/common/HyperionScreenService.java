package com.abrenoch.hyperiongrabber.common;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.abrenoch.hyperiongrabber.common.network.HyperionThread;
import com.abrenoch.hyperiongrabber.common.util.HyperionGrabberOptions;
import com.abrenoch.hyperiongrabber.common.util.Preferences;

import java.util.Objects;

public class HyperionScreenService extends Service {
    public static final String BROADCAST_ERROR = "SERVICE_ERROR";
    public static final String BROADCAST_TAG = "SERVICE_STATUS";
    public static final String BROADCAST_FILTER = "SERVICE_FILTER";
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionScreenService";

    private static final String BASE = "com.abrenoch.hyperiongrabber.service.";
    public static final String ACTION_START = BASE + "ACTION_START";
    public static final String ACTION_STOP = BASE + "ACTION_STOP";
    public static final String ACTION_EXIT = BASE + "ACTION_EXIT";
    public static final String GET_STATUS = BASE + "ACTION_STATUS";
    public static final String EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_EXIT_INTENT_ID = 2;

    private boolean RECONNECT = false;
    private boolean hasConnected = false;
    private MediaProjectionManager mMediaProjectionManager;
    private HyperionThread mHyperionThread;
    private static MediaProjection _mediaProjection;
    private int mFrameRate;
    private String mResolution;
    private int mHorizontalLEDCount;
    private int mVerticalLEDCount;
    private boolean mSendAverageColor;
    private boolean mUseLegacyImageReader;
    private boolean mDrmOrangeFallback;
    private HyperionScreenEncoder mHyperionEncoder;
    private NotificationManager mNotificationManager;
    private String mStartError = null;
    // [deepseek] Guards against repeating the error broadcast. Since the service now survives failed
    // [deepseek] connect attempts and HyperionThread reports every retry, MainActivity would show a
    // [deepseek] Toast every RECONNECT_DELAY seconds without this flag. Reset on a successful connect.
    private boolean mErrorNotified = false;

    HyperionThreadBroadcaster mReceiver = new HyperionThreadBroadcaster() {
        @Override
        public void onConnected() {
            Log.d(TAG, "CONNECTED TO HYPERION INSTANCE");
            hasConnected = true;
            // [deepseek] Clear a previously reported connection error. While RECONNECT is enabled the
            // [deepseek] service now survives failed attempts, so the UI would otherwise keep showing
            // [deepseek] "server unreachable" even after the connection came up.
            mStartError = null;
            mErrorNotified = false;
            notifyActivity();
        }

        @Override
        public void onConnectionError(int errorID, String error) {
            Log.e(TAG, "COULD NOT CONNECT TO HYPERION INSTANCE");
            if (error != null) Log.e(TAG, error);
            if (!hasConnected) {
                mStartError = getResources().getString(R.string.error_server_unreachable);
                // [deepseek] Only give up when automatic reconnect is disabled. With RECONNECT enabled
                // [deepseek] the call to haltStartup() used to stopSelf() the whole service on the very
                // [deepseek] first failed attempt, which killed the HyperionThread before it could retry.
                // [deepseek] Result: TV boots before the Hyperion server -> grabber dead until a manual
                // [deepseek] restart. Now the service stays alive and the thread keeps retrying.
                if (RECONNECT) {
                    // [deepseek] Report the failure only once per outage; the thread keeps retrying
                    // [deepseek] silently otherwise (logcat only).
                    if (!mErrorNotified) {
                        mErrorNotified = true;
                        notifyActivity();
                    }
                } else {
                    haltStartup();
                }
            }
            if (RECONNECT && hasConnected) {
                Log.e(TAG, "AUTOMATIC RECONNECT ENABLED. CONNECTING ...");
            } else if (!RECONNECT && hasConnected) {
                mStartError = getResources().getString(R.string.error_connection_lost);
                stopSelf();
            }
        }

        @Override
        public void onReceiveStatus(boolean isCapturing) {
            if (DEBUG) Log.v(TAG, "Received grabber status, notifying activity. Status: " +
                    String.valueOf(isCapturing));
            notifyActivity();
        }
    };

    BroadcastReceiver mEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case Intent.ACTION_SCREEN_ON:
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_ON intent received");
                    if (mHyperionEncoder != null && !isCapturing()) {
                        if (DEBUG) Log.v(TAG, "Encoder not grabbing, attempting to restart");
                        mHyperionEncoder.resumeRecording();
                    }
                    notifyActivity();
                break;
                case Intent.ACTION_SCREEN_OFF:
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_OFF intent received");
                    if (mHyperionEncoder != null) {
                        if (DEBUG) Log.v(TAG, "Clearing current light data");
                        mHyperionEncoder.clearLights();
                    }
                break;
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received");
                    if (mHyperionEncoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        if (DEBUG) Log.v(TAG, "Configuration changed, checking orientation");
                        mHyperionEncoder.setOrientation(getResources().getConfiguration().orientation);
                    }
                break;
                case Intent.ACTION_SHUTDOWN:
                case Intent.ACTION_REBOOT:
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received");
                    stopScreenRecord();
                break;
            }
        }
    };

    @Override
    public void onCreate() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        super.onCreate();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private boolean prepared() {
        Preferences prefs = new Preferences(getBaseContext());
        String host = prefs.getString(R.string.pref_key_host, null);
        int port = prefs.getInt(R.string.pref_key_port, -1);
        String priority = prefs.getString(R.string.pref_key_priority, "150");
        mFrameRate = prefs.getInt(R.string.pref_key_framerate);
        mResolution = prefs.getString(R.string.pref_key_resolution, "1280");
        Log.d("prepared Settings", "Resolution gespeichert: "  + mResolution);

        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led);
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led);
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color);
        mUseLegacyImageReader = prefs.getBoolean(R.string.pref_key_use_legacy_image_reader);
        mDrmOrangeFallback = prefs.getBoolean(R.string.pref_key_drm_orange_fallback);
        RECONNECT = prefs.getBoolean(R.string.pref_key_reconnect);
        int delay = prefs.getInt(R.string.pref_key_reconnect_delay);
        if (host == null || Objects.equals(host, "0.0.0.0") || Objects.equals(host, "")) {
            mStartError = getResources().getString(R.string.error_empty_host);
            return false;
        }
        if (port == -1) {
            mStartError = getResources().getString(R.string.error_empty_port);
            return false;
        }
        if (mHorizontalLEDCount <= 0 || mVerticalLEDCount <= 0) {
            mStartError = getResources().getString(R.string.error_invalid_led_counts);
            return false;
        }
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mHyperionThread = new HyperionThread(mReceiver, host, port, Integer.parseInt(priority), RECONNECT, delay);
        mHyperionThread.start();
        mStartError = null;
        mErrorNotified = false;
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.v(TAG, "Start command received");
        super.onStartCommand(intent, flags, startId);
        if (intent == null || intent.getAction() == null) {
            String nullItem = (intent == null ? "intent" : "action");
            if (DEBUG) Log.v(TAG, "Null " + nullItem + " provided to start command");
        } else  {
            final String action = intent.getAction();
            if (DEBUG) Log.v(TAG, "Start command action: " + String.valueOf(action));
            switch (action) {
                case ACTION_START:
                    if (mHyperionThread == null) {
                        boolean isPrepared = prepared();
                        if (isPrepared) {
                            startForeground(NOTIFICATION_ID, getNotification());
                            startScreenRecord(intent);

                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                            intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                            intentFilter.addAction(Intent.ACTION_REBOOT);
                            intentFilter.addAction(Intent.ACTION_SHUTDOWN);

                            registerReceiver(mEventReceiver, intentFilter);
                        } else {
                            haltStartup();
                        }
                    }
                    break;
                case ACTION_STOP:
                    stopScreenRecord();
                    // [deepseek] End the service as well. Previously only the capture was stopped while
                    // [deepseek] the service kept running (with the foreground notification cancelled),
                    // [deepseek] which left it in a zombie state: no capture, no notification, and no way
                    // [deepseek] to start again without restarting the process.
                    stopSelf();
                    break;
                case GET_STATUS:
                    notifyActivity();
                    break;
                case ACTION_EXIT:
                    stopSelf();
                    break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.v(TAG, "Ending service");

        try {
            unregisterReceiver(mEventReceiver);
        } catch (Exception e) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered");
        }

        stopScreenRecord();
        stopForeground(true);
        notifyActivity();

        super.onDestroy();
    }

    private void haltStartup() {
        startForeground(NOTIFICATION_ID, getNotification());
        stopSelf();
    }

    private Intent buildExitButton() {
        Intent notificationIntent = new Intent(this, this.getClass());
        notificationIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        notificationIntent.setAction(ACTION_EXIT);
        return notificationIntent;
    }

    public Notification getNotification() {
        HyperionNotification notification = new HyperionNotification(this, mNotificationManager);
        String label = getString(R.string.notification_exit_button);
        notification.setAction(NOTIFICATION_EXIT_INTENT_ID, label, buildExitButton());
        return notification.buildNotification();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScreenRecord(final Intent intent) {
        if (DEBUG) Log.v(TAG, "Start screen recorder");
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        // get MediaProjection
        final MediaProjection projection = mMediaProjectionManager.getMediaProjection(resultCode, intent);
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (projection != null && window != null) {
            _mediaProjection = projection;
            final DisplayMetrics metrics = new DisplayMetrics();
            window.getDefaultDisplay().getRealMetrics(metrics);
            final int density = metrics.densityDpi;
            HyperionGrabberOptions options = new HyperionGrabberOptions(mHorizontalLEDCount,
                    mVerticalLEDCount, mFrameRate, mSendAverageColor, mUseLegacyImageReader, mResolution,
                    mDrmOrangeFallback);
            if (DEBUG) Log.v(TAG, "Starting the recorder");
            mHyperionEncoder = new HyperionScreenEncoder(mHyperionThread.getReceiver(),
                    projection, metrics.widthPixels, metrics.heightPixels,
                    density, options);
            mHyperionEncoder.sendStatus();
        }
    }

    private void stopScreenRecord() {
        if (DEBUG) Log.v(TAG, "Stop screen recorder");
        RECONNECT = false;
        mNotificationManager.cancel(NOTIFICATION_ID);
        if (mHyperionEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping the current encoder");
            mHyperionEncoder.stopRecording();
            // [deepseek] Drop the reference. Leaving it set meant the stopped encoder stayed reachable
            // [deepseek] (isCapturing() then reported stale values) and, worse, blocked a restart:
            // [deepseek] the ACTION_START branch only runs when mHyperionThread is null.
            mHyperionEncoder = null;
        }
        releaseResource();
        if (mHyperionThread != null) {
            mHyperionThread.interrupt();
            // [deepseek] Same reason as above: ACTION_START is guarded by "mHyperionThread == null",
            // [deepseek] so without this a stopped grabber could never be started again.
            mHyperionThread = null;
        }
        // [deepseek] Reset the session state, otherwise a restarted grabber inherits the connection
        // [deepseek] status and a pending error message from the previous run.
        hasConnected = false;
        mStartError = null;
        mErrorNotified = false;
        try {
            unregisterReceiver(mEventReceiver);
        } catch (Exception e) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered");
        }
        // [deepseek] The notification of a foreground service is only removed reliably by
        // [deepseek] stopForeground(); NotificationManager.cancel() alone leaves it on Android 8+.
        stopForeground(true);
        // [deepseek] Report the new state to the UI. This was missing entirely, so any stop that did
        // [deepseek] not come from the activity itself (notification action, shutdown, adb) left the
        // [deepseek] GUI showing the "grabbing" state while nothing was capturing.
        notifyActivity();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void releaseResource() {
        if (_mediaProjection != null) {
            _mediaProjection.stop();
            _mediaProjection = null;
        }
    }

    boolean isCapturing() {
        return mHyperionEncoder != null && mHyperionEncoder.isCapturing();
    }

    boolean isCommunicating() {
        return isCapturing() && hasConnected;
    }

    private void notifyActivity() {
        Intent intent = new Intent(BROADCAST_FILTER);
        intent.putExtra(BROADCAST_TAG, isCommunicating());
        intent.putExtra(BROADCAST_ERROR, mStartError);
        if (DEBUG) {
            Log.v(TAG, "Sending status broadcast - communicating: " +
                    String.valueOf(isCommunicating()));
            if (mStartError != null) {
                Log.v(TAG, "Startup error: " + mStartError);
            }
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    public interface HyperionThreadBroadcaster {
//        void onResponse(String response);
        void onConnected();
        void onConnectionError(int errorHash, String errorString);
        void onReceiveStatus(boolean isCapturing);
    }
}
