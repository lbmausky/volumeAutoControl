package com.example.volumeautocontrol

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

// 拉起守护的共用入口。界面 onResume 和开机广播都走这里，避免两处各写一份。

/** 通知使用权是否已授予。判断的是本应用的包名，所以调试版和正式版各算各的。 */
internal fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * 主动请求重绑通知监听服务，并确保前台服务在跑。
 *
 * 定制系统杀掉应用后不会自动重新绑定通知监听服务，重启后也不一定可靠，所以两个时机都补一次。
 *
 * 调用方必须处在“允许后台启动前台服务”的豁免场景里：界面在前台，或者正在处理开机广播。
 * 别在其它后台时机调用，Android 12 起会直接抛异常。
 */
internal fun reviveGuard(context: Context) {
    if (!hasNotificationAccess(context)) return
    runCatching {
        NotificationListenerService.requestRebind(
            ComponentName(context, VolumeGuardService::class.java)
        )
    }
    if (GuardState.enabled) {
        GuardForegroundService.start(context)
    }
}
