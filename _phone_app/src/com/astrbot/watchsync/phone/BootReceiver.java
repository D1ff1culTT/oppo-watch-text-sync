package com.astrbot.watchsync.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启同步服务（BOOT_COMPLETED 属于允许后台启动前台服务的豁免场景）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, SyncService.class));
        }
    }
}
