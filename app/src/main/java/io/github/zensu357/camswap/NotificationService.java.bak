package io.github.zensu357.camswap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import io.github.zensu357.camswap.utils.LogUtil;

public class NotificationService extends Service {
    private static final String CHANNEL_ID = "camswap_control_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private static final String ACTION_PREV_INTERNAL = "io.github.zensu357.camswap.action.PREV_INTERNAL";
    private static final String ACTION_NEXT_INTERNAL = "io.github.zensu357.camswap.action.NEXT_INTERNAL";
    private static final String ACTION_ROTATE_INTERNAL = "io.github.zensu357.camswap.action.ROTATE_INTERNAL";
    private static final String ACTION_EXIT_INTERNAL = "io.github.zensu357.camswap.action.EXIT_INTERNAL";
    private static final String ACTION_PASSTHROUGH_INTERNAL = "io.github.zensu357.camswap.action.PASSTHROUGH_INTERNAL"; // NEW

    private ConfigManager configManager;
    private int currentRotationOffset = 0;

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            android.util.Log.d("Camswap_NOTIF", "收到操作指令: " + action);
            
            if (ACTION_EXIT_INTERNAL.equals(action)) {
                stopSelf();
            } else if (ACTION_PREV_INTERNAL.equals(action)) {
                handleSwitch(false);
            } else if (ACTION_NEXT_INTERNAL.equals(action)) {
                handleSwitch(true);
            } else if (ACTION_ROTATE_INTERNAL.equals(action)) {
                currentRotationOffset = (currentRotationOffset + 90) % 360;
                if (configManager != null) {
                    configManager.forceReload();
                    configManager.setInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, currentRotationOffset);
                }
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, buildNotification());
                }
                android.util.Log.d("Camswap_NOTIF", "旋转偏移已切换为: " + currentRotationOffset + "°");
            } else if (ACTION_PASSTHROUGH_INTERNAL.equals(action)) { // NEW HANDLER
                boolean currentState = configManager.getBoolean(ConfigManager.KEY_PASSTHROUGH_MODE, false);
                configManager.setBoolean(ConfigManager.KEY_PASSTHROUGH_MODE, !currentState);
                
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, buildNotification());
                }
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(io.github.zensu357.camswap.utils.LocaleHelper.INSTANCE.onAttach(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        configManager = new ConfigManager();
        configManager.setContext(this);
        currentRotationOffset = configManager.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_EXIT_INTERNAL);
        filter.addAction(ACTION_PREV_INTERNAL);
        filter.addAction(ACTION_NEXT_INTERNAL);
        filter.addAction(ACTION_ROTATE_INTERNAL);
        filter.addAction(ACTION_PASSTHROUGH_INTERNAL); // NEW
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(controlReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);
        
        String rotationLabel = getString(R.string.notif_rotate_label) + currentRotationOffset + "°";
        
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_rotate_offset) + currentRotationOffset + "°")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
                
        builder.addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_prev),
                getPendingIntent(ACTION_PREV_INTERNAL)).build());
        builder.addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_next),
                getPendingIntent(ACTION_NEXT_INTERNAL)).build());
        builder.addAction(new Notification.Action.Builder(null, rotationLabel,
                getPendingIntent(ACTION_ROTATE_INTERNAL)).build());

        // 👇 NEW PASSTHROUGH ACTION BUTTON 👇
        boolean isPassthrough = configManager.getBoolean(ConfigManager.KEY_PASSTHROUGH_MODE, false);
        String passthroughLabel = isPassthrough 
            ? getString(R.string.notif_action_use_swapcam) 
            : getString(R.string.notif_action_use_real);
            
        builder.addAction(new Notification.Action.Builder(null, passthroughLabel,
                getPendingIntent(ACTION_PASSTHROUGH_INTERNAL)).build());
        // 👆 END OF NEW CODE 👆

        builder.addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_exit),
                getPendingIntent(ACTION_EXIT_INTERNAL)).build());
                
        return builder.build();
    }

    private PendingIntent getPendingIntent(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void handleSwitch(boolean next) {
        boolean changed = ControlActionHelper.switchVideo(this, next);
        if (!changed) {
            LogUtil.log("【CS】通知栏切换视频未发生变化");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
} // <-- This closing brace for the class was likely missing or misplaced