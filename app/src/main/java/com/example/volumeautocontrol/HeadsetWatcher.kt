package com.example.volumeautocontrol

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** 耳机是否连接的判定，界面和服务共用同一份逻辑。 */
object HeadsetStatus {

    private val HEADSET_TYPES: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
    }

    fun isConnected(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in HEADSET_TYPES }
}

/**
 * 监听耳机的接入与断开。有线、蓝牙、USB 走的是同一套音频设备回调，不需要分别处理。
 */
class HeadsetWatcher(
    private val context: Context,
    private val onHeadsetLost: () -> Unit,
    private val onHeadsetFound: () -> Unit,
) {

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // 注册回调时系统会立刻回调一次当前设备列表，这一次不算“状态变化”。
    private var initialised = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = sync()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = sync()
    }

    /**
     * 先同步落一次权威值，再注册回调。
     *
     * 注册时系统给的那次“当前设备列表”回调是 post 到 [handler] 的，要等当前这一轮消息处理完才会到。
     * 调用方（服务的 onCreate）紧接着就会开始处理媒体会话，那时候 [GuardState.headsetConnected]
     * 还是初始的 false，耳机明明连着也会把正在播放的内容按停。所以这里必须同步先查一次。
     */
    fun start() {
        sync()
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
        initialised = false
    }

    private fun sync() {
        val connected = HeadsetStatus.isConnected(context)
        val previous = GuardState.headsetConnected
        GuardState.headsetConnected = connected

        if (!initialised) {
            initialised = true
            GuardState.log(if (connected) "启动时耳机已连接" else "启动时没有耳机")
            return
        }
        if (previous == connected) return

        if (connected) {
            GuardState.log("耳机已连接")
            onHeadsetFound()
        } else {
            GuardState.log("耳机已断开")
            onHeadsetLost()
        }
    }
}
