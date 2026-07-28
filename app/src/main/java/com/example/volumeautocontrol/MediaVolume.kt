package com.example.volumeautocontrol

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 只操作媒体音量（STREAM_MUSIC），不碰铃声和闹钟。
 *
 * 这里刻意不做“插回耳机时恢复原音量”。安卓按输出设备分别维护音量表，耳机有自己的一份，
 * 插回去系统会自动用上，本来就不需要我们干预；而 setStreamVolume 只能写当前活动的输出，
 * 在路由切换的瞬间调用会把扬声器的数值写到耳机上，导致实际响度和界面显示对不上。
 *
 * 静音的方向没有这个问题：设备移除的回调触发时路由已经切到扬声器，不存在竞态。
 */
object MediaVolume {

    private const val TAG = "MediaVolume"

    /** 最后一次由本应用设置的音量。用来区分音量是我们改的还是用户自己改的。 */
    private var lastAppliedVolume = -1

    fun current(context: Context): Int =
        audioManager(context).getStreamVolume(AudioManager.STREAM_MUSIC)

    fun max(context: Context): Int =
        audioManager(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** 当前音量是否偏离了我们最后设置的值，是的话就说明用户自己动过音量键。 */
    fun changedByUser(context: Context): Boolean =
        lastAppliedVolume >= 0 && current(context) != lastAppliedVolume

    /** 耳机接回来时清掉基准，否则会拿静音时的 0 去和耳机自己的音量比较，误判成用户调了音量。 */
    fun forget() {
        lastAppliedVolume = -1
    }

    fun mute(context: Context) {
        val volume = current(context)
        if (volume == 0) {
            lastAppliedVolume = 0
            GuardState.log("媒体音量本来就是 0，无需静音")
            return
        }
        try {
            audioManager(context).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            lastAppliedVolume = 0
            GuardState.log("已静音（原音量 $volume）")
        } catch (e: SecurityException) {
            GuardState.log("静音失败：系统拒绝了请求")
            Log.w(TAG, "setStreamVolume(0) failed", e)
        }
    }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(AudioManager::class.java)
}
