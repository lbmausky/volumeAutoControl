package com.example.volumeautocontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机后把守护拉起来，省得用户每次重启都要手动打开一次界面。
 *
 * 开机广播属于“允许后台启动前台服务”的豁免场景，所以这里可以直接调 [reviveGuard]。
 *
 * 注意：小米这类定制系统上，没给「自启动」权限的应用收不到开机广播，这个接收器根本不会被调用。
 * 界面里那个「允许开机自启」按钮就是为此准备的。
 *
 * 没有监听 ACTION_LOCKED_BOOT_COMPLETED：那个广播只发给声明了 directBootAware 的组件，
 * 而直接启动阶段读不到默认的 SharedPreferences（凭据加密存储要解锁后才可用），
 * [GuardState.load] 会直接抛异常。要支持就得把配置迁到设备加密存储，代价远大于收益。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "boot broadcast: ${intent?.action}")
        GuardState.load(context)
        if (!GuardState.enabled) {
            GuardState.log("开机了，但守护是关着的，没启动")
            return
        }
        if (!hasNotificationAccess(context)) {
            GuardState.log("开机了，但还没给通知使用权，先不启动")
            return
        }
        GuardState.log("开机自启，正在拉起守护")
        reviveGuard(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
