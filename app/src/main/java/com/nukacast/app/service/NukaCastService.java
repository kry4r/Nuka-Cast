package com.nukacast.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.nukacast.app.MainActivity;
import com.nukacast.app.NukaCastApp;
import com.nukacast.app.R;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.diagnostics.AppLog;
import com.nukacast.app.tvbox.TvBoxRepository;

public final class NukaCastService extends Service {
    private static final String CHANNEL_ID = "nukacast_receiver";
    /** Let the app finish coming up before starting network work. */
    private static final long STARTUP_REFRESH_DELAY_MS = 4000L;
    private NukaRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = ((NukaCastApp) getApplication()).runtime();
        createNotificationChannel();
        startForeground(41, notification());
        startRuntime();
    }

    private void startRuntime() {
        try {
            runtime.startServices();
            // Startup is the worst moment to hammer a small box: the system is still bringing the
            // app up, and a 139-site config would otherwise begin fetching every site at once.
            delayedRefresh();
        } catch (Exception error) {
            AppLog.e("服务", "接收服务启动失败", error);
            runtime.getState().updateService(AppState.ServiceState.ERROR,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    /** Refreshes the sources a few seconds after startup, on a background thread. */
    private void delayedRefresh() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(STARTUP_REFRESH_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    runtime.getTvBoxRepository().refreshAllAsync(new TvBoxRepository.RefreshListener() {
                        @Override public void onSourceRefreshed(int configs, int sites) {
                            runtime.contentChanged();
                        }
                        @Override public void onRefreshComplete(int configs, int sites) {
                            // Per-source callbacks have already published the final content state.
                        }
                    });
                } catch (RuntimeException error) {
                    AppLog.w("服务", "启动刷新失败：" + error.getClass().getSimpleName());
                }
            }
        }, "nukacast-startup-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppState.ServiceState state = runtime.getState().getServiceState();
        if (state == AppState.ServiceState.ERROR || state == AppState.ServiceState.STOPPED) {
            startRuntime();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        runtime.stopServices();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_nav_cast)
                .setContentTitle("NukaCast")
                .setContentText("AirPlay 与局域网控制服务运行中")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "NukaCast 接收服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("AirPlay 和局域网控制服务");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
