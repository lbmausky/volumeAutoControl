package com.example.volumeautocontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 常驻的守护服务。耳机检测、静音恢复、播放拦截都在这里。
 *
 * 之所以不把这些逻辑放在 [VolumeGuardService]，是因为定制系统会在用户划掉应用后杀掉通知监听服务
 * 且长时间不重新绑定。前台服务带一条常驻通知，系统对它宽容得多。
 *
 * 控制其它应用的媒体会话只要求通知监听组件“已被授权”，不要求它当前处于绑定状态，
 * 所以这里可以直接借 [VolumeGuardService] 的组件名去拿会话控制权。
 */
class GuardForegroundService : Service() {

    private class Watched(val controller: MediaController, val callback: MediaController.Callback)

    private val handler = Handler(Looper.getMainLooper())
    private val watched = mutableMapOf<MediaSession.Token, Watched>()

    private var headsetWatcher: HeadsetWatcher? = null
    private var sessionManager: MediaSessionManager? = null

    /** 上一次求值出来的时段状态，用来识别跨越边界的那一刻。 */
    private var wasWithinSchedule = true

    /**
     * 每分钟重新求值一次时段。
     *
     * 用 Handler 而不是精确闹钟：后者在 Android 12 以上要额外权限，还常被定制系统限制。
     * 代价是手机深度休眠时这个回调不会准时触发，所以真正的判断都放在事件发生的那一刻现算，
     * 这个定时器只负责刷新界面和常驻通知，以及处理进入时段时的补偿动作。
     */
    private val scheduleTicker = object : Runnable {
        override fun run() {
            refreshSchedule()
            handler.postDelayed(this, SCHEDULE_TICK_MS)
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncWatchedSessions(controllers.orEmpty())
        }

    /** 音量键改的是系统设置，只能靠观察者感知。这个观察者对所有系统设置变化都会触发，所以要自己比对音量值。 */
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = checkUserVolumeChange()
    }

    override fun onCreate() {
        super.onCreate()
        GuardState.load(this)
        createChannels()
        goForeground()

        headsetWatcher = HeadsetWatcher(this, ::onHeadsetLost, ::onHeadsetFound).also { it.start() }
        startWatchingSessions()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)

        wasWithinSchedule = GuardState.isWithinSchedule()
        handler.postDelayed(scheduleTicker, SCHEDULE_TICK_MS)

        GuardState.serviceConnected = true
        GuardState.log("守护已启动")
        updateOngoingNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GuardState.serviceConnected = false
        headsetWatcher?.stop()
        headsetWatcher = null
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        sessionManager = null
        contentResolver.unregisterContentObserver(settingsObserver)
        handler.removeCallbacks(scheduleTicker)
        watched.values.forEach { it.controller.unregisterCallback(it.callback) }
        watched.clear()
        GuardState.log("守护已停止")
        super.onDestroy()
    }

    private fun onHeadsetLost() {
        GuardState.resetCycle()
        updateOngoingNotification()
        if (!GuardState.enabled) {
            GuardState.log("守护没开，这次断开不管")
            return
        }
        if (!GuardState.isWithinSchedule()) {
            GuardState.log("不在生效时段，这次断开不管")
            return
        }
        MediaVolume.mute(this)
        pauseEverythingPlaying()
    }

    private fun onHeadsetFound() {
        GuardState.resetCycle()
        // 耳机自己的音量由系统的分设备音量表恢复，我们只需要放弃静音时留下的比较基准。
        MediaVolume.forget()
        updateOngoingNotification()
    }

    /** 耳机断开期间用户自己动了音量，说明他清楚自己在外放，那就别再拦着。 */
    private fun checkUserVolumeChange() {
        if (GuardState.headsetConnected || !GuardState.enabled || GuardState.bypassed) return
        if (!GuardState.isWithinSchedule()) return
        if (!MediaVolume.changedByUser(this)) return
        bypass(getString(R.string.bypass_by_volume))
    }

    /**
     * 跨越时段边界时的处理。
     *
     * 进入时段且耳机不在位就补一次静音——设时段本来就是在说“这些时间不要外放”，静音是保护方向的动作。
     * 离开时段则只是停止介入，不动音量，避免在用户没操作的情况下突然把声音放出来。
     */
    private fun refreshSchedule() {
        val within = GuardState.isWithinSchedule()
        if (within == wasWithinSchedule) return
        wasWithinSchedule = within

        if (within) {
            GuardState.resetCycle()
            GuardState.log("进入生效时段")
            if (GuardState.enabled && !GuardState.headsetConnected) {
                MediaVolume.mute(this)
            }
        } else {
            GuardState.log("离开生效时段，暂停守护，音量不动")
        }
        updateOngoingNotification()
    }

    private fun bypass(reason: String) {
        GuardState.bypassed = true
        GuardState.log(reason)
        postNotification(getString(R.string.bypass_title), reason)
    }

    private fun startWatchingSessions() {
        val manager: MediaSessionManager = getSystemService(MediaSessionManager::class.java)
        sessionManager = manager
        val token = ComponentName(this, VolumeGuardService::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, token)
            syncWatchedSessions(manager.getActiveSessions(token))
        }.onFailure {
            GuardState.log("拿不到播放控制权，检查一下通知使用权")
            Log.w(TAG, "media session access denied", it)
        }
    }

    /** 每次活跃会话列表变化时重新挂载监听。列表里每次都是新的 controller 实例，所以用 token 做键。 */
    private fun syncWatchedSessions(controllers: List<MediaController>) {
        val liveTokens = controllers.map { it.sessionToken }.toSet()
        watched.keys.toList()
            .filterNot { it in liveTokens }
            .forEach { token -> watched.remove(token)?.let { it.controller.unregisterCallback(it.callback) } }

        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (watched.containsKey(token)) return@forEach

            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    interceptIfPlaying(controller, state)
                }

                override fun onSessionDestroyed() {
                    watched.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
                }
            }
            watched[token] = Watched(controller, callback)
            controller.registerCallback(callback, handler)
            interceptIfPlaying(controller, controller.playbackState)
        }
    }

    private fun interceptIfPlaying(controller: MediaController, state: PlaybackState?) {
        if (state?.state != PlaybackState.STATE_PLAYING) return
        if (!GuardState.enabled || GuardState.headsetConnected || GuardState.bypassed) return
        if (!GuardState.isWithinSchedule()) return
        // 动手前再查一次权威值兜底。缓存值靠音频设备回调维护，回调只要比播放状态变化晚一步，
        // 就会把用户正在听的内容按停。查设备列表只是一次 binder 调用，而这里只在播放状态变化时才走到，
        // 代价远小于误暂停。不在这里改写缓存值：耳机状态的记账（写日志、归零本轮计数）由
        // HeadsetWatcher 统一负责，抢着改反而会让它漏掉一次连接事件。
        if (HeadsetStatus.isConnected(this)) return

        if (GuardState.interceptCount >= GuardState.pauseLimit) {
            bypass(getString(R.string.bypass_by_count, GuardState.pauseLimit))
            return
        }

        val name = appLabel(controller.packageName)
        controller.transportControls.pause()
        GuardState.interceptCount++

        val remaining = GuardState.pauseLimit - GuardState.interceptCount
        GuardState.log("「$name」想播放，已暂停（第 ${GuardState.interceptCount} 次，还剩 $remaining 次）")
        postNotification(
            getString(R.string.blocked_title),
            if (remaining > 0) {
                getString(R.string.blocked_text, name, remaining)
            } else {
                getString(R.string.blocked_text_last, name)
            },
        )
    }

    /** 拔耳机那一刻正在播放的会被直接按停，这不算用户主动尝试播放，所以不计入拦截次数。 */
    private fun pauseEverythingPlaying() {
        watched.values.forEach { entry ->
            val controller = entry.controller
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                val name = appLabel(controller.packageName)
                controller.transportControls.pause()
                GuardState.log("「$name」正在播放，已暂停")
            }
        }
    }

    /**
     * 会话所属应用的名字，取不到就退回包名。
     *
     * 取不到是 Android 11 起的包可见性限制，不是异常状况。清单里的 `<queries>` 只声明了
     * MEDIA_BUTTON，只能看到声明了媒体按键组件的应用（网易云音乐、小米音乐这些）；像 UC 浏览器
     * 那样在网页里放音频的应用并不声明，静态规则覆盖不到，查名字会抛 NameNotFoundException。
     *
     * 系统另外会在应用间产生交互后动态补一条授予，之后就能查到名字，但这条授予在本应用被重装或
     * 手机重启后会清空，所以清空后的第一次拦截会显示包名。用 `adb shell dumpsys package queries`
     * 可以核对，静态的在 queries via component 段，动态的在 queryable via interaction 段。
     *
     * 要彻底消掉只能申请 QUERY_ALL_PACKAGES，那是敏感权限，为了日志里一个名字不划算。
     */
    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse {
        Log.w(TAG, "app label lookup failed for $packageName", it)
        packageName
    }

    private fun goForeground() {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun createChannels() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ONGOING_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.ongoing_channel_name))
                .setDescription(getString(R.string.ongoing_channel_description))
                .setShowBadge(false)
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(BLOCKED_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.channel_name))
                .setDescription(getString(R.string.channel_description))
                .build()
        )
    }

    private fun buildOngoingNotification(): Notification {
        val text = when {
            !GuardState.enabled -> getString(R.string.ongoing_disabled)
            !GuardState.isWithinSchedule() -> getString(
                R.string.ongoing_out_of_schedule,
                GuardState.formatMinutes(GuardState.scheduleStart),
                GuardState.formatMinutes(GuardState.scheduleEnd),
            )
            GuardState.headsetConnected -> getString(R.string.ongoing_headset_connected)
            else -> getString(R.string.ongoing_headset_missing)
        }
        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ongoing_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateOngoingNotification() {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(ONGOING_ID, buildOngoingNotification())
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(title: String, text: String) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(this, BLOCKED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(BLOCKED_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // CLEAR_TOP 与 SINGLE_TOP 一起用，界面已在栈里时复用实例，避免每点一次通知就多叠一层
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "GuardForegroundService"
        private const val ONGOING_CHANNEL_ID = "guard_ongoing"
        private const val BLOCKED_CHANNEL_ID = "playback_blocked"
        private const val ONGOING_ID = 1000
        private const val BLOCKED_ID = 1001
        private const val SCHEDULE_TICK_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, GuardForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // Android 12 起，应用在后台时不允许拉起前台服务。等用户下次打开界面会重试。
                Log.w(TAG, "startForegroundService rejected", it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardForegroundService::class.java))
        }
    }
}
