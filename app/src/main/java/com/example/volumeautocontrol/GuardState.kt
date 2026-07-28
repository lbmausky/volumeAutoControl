package com.example.volumeautocontrol

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class GuardEvent(val time: String, val text: String)

/**
 * 界面与后台服务共享的运行时状态。两者在同一个进程里，用单例传递即可。
 */
object GuardState {

    const val DEFAULT_PAUSE_LIMIT = 3
    const val MAX_PAUSE_LIMIT = 99
    const val DEFAULT_SCHEDULE_START = 9 * 60
    const val DEFAULT_SCHEDULE_END = 22 * 60

    private const val TAG = "GuardState"
    private const val PREFS_NAME = "volume_auto_control"
    private const val KEY_ENABLED = "guard_enabled"
    private const val KEY_PAUSE_LIMIT = "pause_limit"
    private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    private const val KEY_SCHEDULE_START = "schedule_start"
    private const val KEY_SCHEDULE_END = "schedule_end"
    private const val MAX_EVENTS = 40

    var enabled by mutableStateOf(true)
        private set

    /** 一轮断连里最多拦截几次播放，超过就放行。0 表示完全不拦截。 */
    var pauseLimit by mutableIntStateOf(DEFAULT_PAUSE_LIMIT)
        private set

    /** 是否只在指定时间段内生效。关闭时全天生效。 */
    var scheduleEnabled by mutableStateOf(false)
        private set

    /** 生效时段的起止，用“从午夜起的分钟数”表示，避免依赖 API 26 才有的 java.time。 */
    var scheduleStart by mutableIntStateOf(DEFAULT_SCHEDULE_START)
        private set

    var scheduleEnd by mutableIntStateOf(DEFAULT_SCHEDULE_END)
        private set

    var headsetConnected by mutableStateOf(false)

    var serviceConnected by mutableStateOf(false)

    /** 本轮断连已经拦截了几次。耳机状态变化时归零。 */
    var interceptCount by mutableIntStateOf(0)

    /** 本轮断连是否已经放行。放行后不再拦截，直到下次断连。 */
    var bypassed by mutableStateOf(false)

    val events = mutableStateListOf<GuardEvent>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun load(context: Context) {
        val prefs = prefs(context)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        pauseLimit = prefs.getInt(KEY_PAUSE_LIMIT, DEFAULT_PAUSE_LIMIT)
        scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        scheduleStart = prefs.getInt(KEY_SCHEDULE_START, DEFAULT_SCHEDULE_START)
        scheduleEnd = prefs.getInt(KEY_SCHEDULE_END, DEFAULT_SCHEDULE_END)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        log(if (value) "守护已开启" else "守护已关闭")
    }

    fun setPauseLimit(context: Context, value: Int) {
        val safe = value.coerceIn(0, MAX_PAUSE_LIMIT)
        pauseLimit = safe
        prefs(context).edit().putInt(KEY_PAUSE_LIMIT, safe).apply()
    }

    fun setScheduleEnabled(context: Context, value: Boolean) {
        scheduleEnabled = value
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()
        log(if (value) "已限定生效时段 ${formatMinutes(scheduleStart)}–${formatMinutes(scheduleEnd)}" else "已改为全天生效")
    }

    fun setSchedule(context: Context, start: Int, end: Int) {
        scheduleStart = start.coerceIn(0, 24 * 60 - 1)
        scheduleEnd = end.coerceIn(0, 24 * 60 - 1)
        prefs(context).edit()
            .putInt(KEY_SCHEDULE_START, scheduleStart)
            .putInt(KEY_SCHEDULE_END, scheduleEnd)
            .apply()
        log("生效时段改为 ${formatMinutes(scheduleStart)}–${formatMinutes(scheduleEnd)}")
    }

    /**
     * 当前是否落在生效时段内。没开时段限制时永远为真。
     *
     * 起点晚于终点表示跨午夜（例如 22:00–07:00），这时候判断条件要取并集而不是交集。
     * 起止相同视为全天，避免出现一个永远不生效的空区间。
     */
    fun isWithinSchedule(nowMinutes: Int = currentMinutes()): Boolean {
        if (!scheduleEnabled || scheduleStart == scheduleEnd) return true
        return if (scheduleStart < scheduleEnd) {
            nowMinutes >= scheduleStart && nowMinutes < scheduleEnd
        } else {
            nowMinutes >= scheduleStart || nowMinutes < scheduleEnd
        }
    }

    fun currentMinutes(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    fun formatMinutes(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

    /** 耳机状态变化时开启新一轮，拦截计数和放行标记都归零。 */
    fun resetCycle() {
        interceptCount = 0
        bypassed = false
    }

    fun log(text: String) {
        Log.i(TAG, text)
        events.add(0, GuardEvent(timeFormat.format(Date()), text))
        while (events.size > MAX_EVENTS) {
            events.removeAt(events.size - 1)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
