package com.tapreplay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 保活前台服务（荣耀/MagicOS 适配核心）。
 * 悬浮控制条显示期间运行：一条最低重要级通知换一个不被后台管控杀掉的进程。
 * 无障碍服务本身由系统绑定，但进程被杀后服务同样会断——保的是进程。
 */
public class KeepAliveService extends Service {

    private static final int NOTI_ID = 42;
    private static final String CHANNEL = "keepalive";

    public static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
        } catch (Exception ignored) { }
    }

    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, KeepAliveService.class)
                    .setAction("stop"));
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "保活", NotificationManager.IMPORTANCE_MIN));
        }
        Notification n = buildNotification();
        try {
            startForeground(NOTI_ID, n);
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("TapReplay 运行中")
                .setContentText("悬浮控制条可用；收起控制条后此通知自动消失")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
