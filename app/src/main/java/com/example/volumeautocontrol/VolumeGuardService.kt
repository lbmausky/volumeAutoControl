package com.example.volumeautocontrol

import android.service.notification.NotificationListenerService

/**
 * 这个服务自己不干活，它存在的意义是承载「通知使用权」这张通行证。
 *
 * 系统在校验媒体会话控制权时只看这个组件有没有被用户授权，不看它是否处于绑定状态，
 * 所以真正的守护逻辑放在 [GuardForegroundService] 里，那边活得久得多。
 */
class VolumeGuardService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardState.load(this)
        GuardState.log("通知使用权已生效")
        if (GuardState.enabled) {
            GuardForegroundService.start(this)
        }
    }
}
