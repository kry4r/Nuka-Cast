package com.nukacast.app.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.nukacast.app.MainActivity;
import com.nukacast.app.NukaCastApp;
import com.nukacast.app.R;
import com.nukacast.app.core.AppState;
import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.tvbox.TvBoxRepository;

public final class NukaCastService extends Service {
    private NukaRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = ((NukaCastApp) getApplication()).runtime();
        startForeground(41, notification());
        try {
            runtime.startServices();
            runtime.getTvBoxRepository().refreshAllAsync(new TvBoxRepository.RefreshListener() {
                @Override public void onRefreshComplete(int configs, int sites) {
                    runtime.getState().updateSources(configs, sites);
                }
            });
        } catch (Exception error) {
            runtime.getState().updateService(AppState.ServiceState.ERROR,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.nukacast_logo)
                .setContentTitle("NukaCast")
                .setContentText("AirPlay 与局域网控制服务运行中")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }
}
