package com.nukacast.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.nukacast.app.core.AppSettings;
import com.nukacast.app.diagnostics.AppLog;

/**
 * Brings the receiver up when the box powers on.
 *
 * <p>Without this the casting endpoints (HTTP control, DLNA, AirPlay) only exist while somebody has
 * opened the app on the television — the phone can find nothing to cast to. Switching it on makes the
 * box behave like a dedicated receiver.
 *
 * <p>Android 8 and later forbid starting a foreground service from the background, and Android 12
 * narrows it further; the attempt is made anyway and the refusal is logged, because on the older
 * televisions this app actually runs on it works and on the newer ones it fails quietly otherwise.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        if (!AppSettings.startOnBoot(context)) return;
        try {
            Intent service = new Intent(context, NukaCastService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            AppLog.i("服务", "开机自启：接收服务已启动");
        } catch (Throwable refused) {
            // Android 12+ blocks this; the setting stays on for older boxes.
            AppLog.w("服务", "开机自启被系统拒绝：" + refused.getClass().getSimpleName()
                    + (refused.getMessage() == null ? "" : "（" + refused.getMessage() + "）"));
        }
    }
}
