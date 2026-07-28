package com.example.volumeautocontrol

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.volumeautocontrol.ui.AuroraBackground
import com.example.volumeautocontrol.ui.GlassActionButton
import com.example.volumeautocontrol.ui.GlassCard
import com.example.volumeautocontrol.ui.StatusLine
import com.example.volumeautocontrol.ui.StepButton
import com.example.volumeautocontrol.ui.theme.Amber100
import com.example.volumeautocontrol.ui.theme.Amber600
import com.example.volumeautocontrol.ui.theme.Blue500
import com.example.volumeautocontrol.ui.theme.Blue600
import com.example.volumeautocontrol.ui.theme.Slate100
import com.example.volumeautocontrol.ui.theme.Slate200
import com.example.volumeautocontrol.ui.theme.Slate300
import com.example.volumeautocontrol.ui.theme.Slate400
import com.example.volumeautocontrol.ui.theme.Slate500
import com.example.volumeautocontrol.ui.theme.Slate600
import com.example.volumeautocontrol.ui.theme.Slate700
import com.example.volumeautocontrol.ui.theme.Slate800
import com.example.volumeautocontrol.ui.theme.Slate900
import com.example.volumeautocontrol.ui.theme.VolumeAutoControlTheme

class MainActivity : ComponentActivity() {

    // 从系统设置页返回时用它触发一次权限状态刷新。
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardState.load(this)
        // 背景是浅色渐变，系统栏图标必须用深色才看得清。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
        )
        setContent {
            VolumeAutoControlTheme {
                Box(Modifier.fillMaxSize()) {
                    AuroraBackground()
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                    ) { innerPadding ->
                        GuardScreen(
                            refreshKey = refreshKey,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 界面不能只依赖服务上报状态：进程刚重建时服务可能还没起来，这时候要自己查一次真实值。
        GuardState.headsetConnected = HeadsetStatus.isConnected(this)
        reviveGuard(this)
        refreshKey++
    }

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
    }
}

/**
 * 定制系统杀掉应用后不会自动重新绑定通知监听服务，这里主动请求重绑，并确保前台服务在跑。
 * 界面处于前台时调用，不会触发 Android 12 起对后台拉起前台服务的限制。
 */
private fun reviveGuard(context: Context) {
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

@Composable
fun GuardScreen(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasNotificationAccess = remember(refreshKey) { hasNotificationAccess(context) }
    val canPostNotifications = remember(refreshKey) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val maxVolume = remember { MediaVolume.max(context) }
    var volume by remember { mutableIntStateOf(MediaVolume.current(context)) }
    var editingScheduleStart by remember { mutableStateOf(false) }
    var editingScheduleEnd by remember { mutableStateOf(false) }
    var editingPauseLimit by remember { mutableStateOf(false) }
    var manualExpanded by remember { mutableStateOf(false) }

    // 时段判断依赖当前时钟，不是 Compose 状态，所以靠界面恢复和服务写日志这两个时机来重算。
    val withinSchedule = remember(
        refreshKey,
        GuardState.scheduleEnabled,
        GuardState.scheduleStart,
        GuardState.scheduleEnd,
        GuardState.events.size,
    ) { GuardState.isWithinSchedule() }

    // 音量键改的是系统设置，只有注册观察者才能实时拿到变化。
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volume = MediaVolume.current(context)
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(refreshKey) { volume = MediaVolume.current(context) }

    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    val guardActive = GuardState.enabled && hasNotificationAccess

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(active = guardActive, subtitle = heroSubtitle(hasNotificationAccess, withinSchedule))

        if (!hasNotificationAccess) {
            SetupCard(
                title = "还需要开启「通知使用权」",
                description = "没有这个权限就拿不到其它应用的播放控制权，守护会形同虚设。点下面的按钮，在列表里找到「耳机守护」并允许。",
                buttonText = "去开启",
                onClick = { openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }

        if (!canPostNotifications) {
            SetupCard(
                title = "还需要允许发送通知",
                description = "拦截播放时用通知告诉你原因，不然你会不知道为什么放不出声。",
                buttonText = "去允许",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openSettings(context, appDetailsIntent(context))
                    }
                },
            )
        }

        MainToggleCard(context)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(206.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PauseCountCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                context = context,
                onNumberClick = { editingPauseLimit = true },
            )
            ScheduleCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                context = context,
                onStartClick = { editingScheduleStart = true },
                onEndClick = { editingScheduleEnd = true },
            )
        }

        LiveStatusCard(
            hasNotificationAccess = hasNotificationAccess,
            withinSchedule = withinSchedule,
            volume = volume,
            maxVolume = maxVolume,
        )

        RecentEvents()

        GlassActionButton(Icons.Outlined.RestartAlt, "重启守护服务") {
            GuardForegroundService.stop(context)
            reviveGuard(context)
            Toast.makeText(context, "已请求重启守护服务", Toast.LENGTH_SHORT).show()
        }

        GlassActionButton(Icons.Outlined.Tune, "加入电池优化白名单") {
            openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        ManualPanel(expanded = manualExpanded, onToggle = { manualExpanded = !manualExpanded })

        Text(
            "建议将本应用设为「允许自启动」并锁定在最近任务中，以防被系统清理。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = Slate400,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }

    if (editingScheduleStart) {
        TimeRangePickerDialog(
            title = "生效时段的开始时间",
            initialMinutes = GuardState.scheduleStart,
            onDismiss = { editingScheduleStart = false },
            onConfirm = {
                GuardState.setSchedule(context, it, GuardState.scheduleEnd)
                editingScheduleStart = false
            },
        )
    }
    if (editingScheduleEnd) {
        TimeRangePickerDialog(
            title = "生效时段的结束时间",
            initialMinutes = GuardState.scheduleEnd,
            onDismiss = { editingScheduleEnd = false },
            onConfirm = {
                GuardState.setSchedule(context, GuardState.scheduleStart, it)
                editingScheduleEnd = false
            },
        )
    }
    if (editingPauseLimit) {
        PauseLimitDialog(
            initial = GuardState.pauseLimit,
            onDismiss = { editingPauseLimit = false },
            onConfirm = {
                GuardState.setPauseLimit(context, it)
                editingPauseLimit = false
            },
        )
    }
}

@Composable
private fun Hero(active: Boolean, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            // 用 requiredSize 而非 size：前者忽略父级传入的约束，避免被压缩掉。
            modifier = Modifier
                .requiredSize(96.dp)
                .clip(CircleShape)
                .background(if (active) Blue500.copy(alpha = 0.18f) else Slate100.copy(alpha = 0.55f))
                .border(
                    width = 2.dp,
                    color = if (active) Blue500.copy(alpha = 0.45f) else Slate200,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Outlined.Shield else Icons.Outlined.GppBad,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (active) Blue500 else Slate400,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("耳机守护", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 15.sp, color = if (active) Blue600 else Slate500)
    }
}

@Composable
private fun MainToggleCard(context: Context) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("守护服务", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Slate900)
                Spacer(Modifier.height(2.dp))
                Text("防止断开耳机后突然外放", fontSize = 13.sp, color = Slate500)
            }
            Switch(
                checked = GuardState.enabled,
                onCheckedChange = { checked ->
                    GuardState.setEnabled(context, checked)
                    if (checked) {
                        GuardForegroundService.start(context)
                    } else {
                        GuardForegroundService.stop(context)
                    }
                },
                colors = switchColors(),
            )
        }
    }
}

@Composable
private fun PauseCountCard(modifier: Modifier, context: Context, onNumberClick: () -> Unit) {
    GlassCard(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Start)
                .size(20.dp),
            tint = Slate300,
        )
        Spacer(Modifier.weight(1f))
        Text("暂停次数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Slate600)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(
                icon = Icons.Outlined.Remove,
                description = "减少",
                enabled = GuardState.pauseLimit > 0,
            ) { GuardState.setPauseLimit(context, GuardState.pauseLimit - 1) }
            Text(
                text = GuardState.pauseLimit.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                color = Slate800,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNumberClick)
                    .padding(vertical = 2.dp),
            )
            StepButton(
                icon = Icons.Outlined.Add,
                description = "增加",
                enabled = GuardState.pauseLimit < GuardState.MAX_PAUSE_LIMIT,
            ) { GuardState.setPauseLimit(context, GuardState.pauseLimit + 1) }
        }
        Spacer(Modifier.height(6.dp))
        Text("次尝试后放行", fontSize = 11.sp, color = Slate400)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ScheduleCard(
    modifier: Modifier,
    context: Context,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            // Switch 的布局高度约 32dp，scale 只缩放绘制不改变布局尺寸，
            // 顶部对齐会让它的中心低于 20dp 的时钟图标，所以按中心对齐。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = Slate300)
            Switch(
                checked = GuardState.scheduleEnabled,
                onCheckedChange = { GuardState.setScheduleEnabled(context, it) },
                colors = switchColors(),
                modifier = Modifier.scale(0.75f),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(modifier = Modifier.alpha(if (GuardState.scheduleEnabled) 1f else 0.4f)) {
            Text("生效时段", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Slate600)
            Spacer(Modifier.height(4.dp))
            TimeText(GuardState.formatMinutes(GuardState.scheduleStart), GuardState.scheduleEnabled, onStartClick)
            Text("至", fontSize = 12.sp, color = Slate400, modifier = Modifier.padding(vertical = 1.dp))
            TimeText(GuardState.formatMinutes(GuardState.scheduleEnd), GuardState.scheduleEnabled, onEndClick)
        }
    }
}

@Composable
private fun TimeText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Light,
        color = Slate800,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 1.dp),
    )
}

@Composable
private fun LiveStatusCard(
    hasNotificationAccess: Boolean,
    withinSchedule: Boolean,
    volume: Int,
    maxVolume: Int,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.MonitorHeart, null, Modifier.size(20.dp), tint = Blue500)
            Spacer(Modifier.width(8.dp))
            Text("实时状态", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Slate900)
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            StatusLine(Icons.Outlined.Shield, "守护服务", serviceStatusText(hasNotificationAccess))
            StatusLine(
                Icons.Outlined.Headphones,
                "耳机连接",
                if (GuardState.headsetConnected) "已连接" else "未连接",
            )
            StatusLine(Icons.AutoMirrored.Outlined.VolumeOff, "媒体音量", "$volume / $maxVolume")
            StatusLine(Icons.Outlined.Schedule, "生效时段", scheduleStatusText(withinSchedule))
            StatusLine(Icons.Outlined.NotificationsActive, "本轮拦截", interceptStatusText(withinSchedule))
        }
    }
}

@Composable
private fun RecentEvents() {
    Column {
        Text(
            "最近事件",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Slate700,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        GlassCard(Modifier.fillMaxWidth(), radius = 20.dp, alpha = 0.60f, contentPadding = 16.dp) {
            if (GuardState.events.isEmpty()) {
                Text("还没有记录。插上耳机再拔掉试试。", fontSize = 13.sp, color = Slate500)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuardState.events.forEach { event ->
                        Row {
                            Text(
                                text = event.time,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Blue500,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(event.text, fontSize = 13.sp, color = Slate600)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualPanel(expanded: Boolean, onToggle: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), radius = 20.dp, alpha = 0.55f, contentPadding = 16.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = Slate500)
            Spacer(Modifier.width(8.dp))
            Text(
                "使用说明",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Slate700,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = Slate400,
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ManualText(
                    "耳机断开后，前 ${GuardState.pauseLimit} 次播放会被暂停，之后本轮不再限制。" +
                        "插回耳机或下次断开时重新计数。设为 0 表示完全不拦截。"
                )
                ManualText("断开期间只要你自己动过音量键，就会立刻停止拦截——那说明你清楚自己在外放。")
                ManualText(scheduleHintText())
                ManualText(
                    "时段结束时不会自动恢复音量，避免在你没操作的情况下突然外放。" +
                        "时段开始时如果耳机不在位，会补一次静音。"
                )
            }
        }
    }
}

@Composable
private fun ManualText(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = Slate500)
}

@Composable
private fun SetupCard(title: String, description: String, buttonText: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), alpha = 0.75f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, Modifier.size(20.dp), tint = Amber600)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Amber600)
        }
        Spacer(Modifier.height(8.dp))
        Text(description, fontSize = 12.sp, lineHeight = 19.sp, color = Slate600)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber100,
                contentColor = Amber600,
            ),
        ) {
            Text(buttonText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Blue500,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Slate200,
    uncheckedBorderColor = Color.Transparent,
)

private fun heroSubtitle(hasNotificationAccess: Boolean, withinSchedule: Boolean): String = when {
    !GuardState.enabled -> "服务已停止"
    !hasNotificationAccess -> "缺少通知使用权"
    !withinSchedule -> "时段外待机中"
    GuardState.bypassed -> "本轮已放行"
    else -> "守护运行中"
}

private fun serviceStatusText(hasNotificationAccess: Boolean): String = when {
    !hasNotificationAccess -> "缺少通知使用权"
    GuardState.serviceConnected -> "运行中"
    else -> "正在启动"
}

private fun interceptStatusText(withinSchedule: Boolean): String = when {
    !withinSchedule -> "时段外，不拦截"
    GuardState.headsetConnected -> "耳机在位，不拦截"
    GuardState.bypassed -> "已放行，本轮不再拦截"
    else -> "${GuardState.interceptCount} / ${GuardState.pauseLimit} 次"
}

private fun scheduleStatusText(withinSchedule: Boolean): String = when {
    !GuardState.scheduleEnabled -> "全天"
    withinSchedule -> "时段内"
    else -> "时段外"
}

private fun scheduleHintText(): String = when {
    !GuardState.scheduleEnabled -> "生效时段已关闭，当前全天生效。"
    GuardState.scheduleStart == GuardState.scheduleEnd -> "起止时间相同，等同于全天生效。"
    GuardState.scheduleStart > GuardState.scheduleEnd ->
        "跨午夜时段：当天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到次日 " +
            "${GuardState.formatMinutes(GuardState.scheduleEnd)} 之间生效。"
    else -> "每天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到 " +
        "${GuardState.formatMinutes(GuardState.scheduleEnd)} 之间生效，其余时间不介入。"
}

@Composable
private fun PauseLimitDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var input by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暂停次数") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw -> input = raw.filter { it.isDigit() }.take(2) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "可填 0 到 ${GuardState.MAX_PAUSE_LIMIT}，0 表示完全不拦截。",
                    fontSize = 12.sp,
                    color = Slate500,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { input.toIntOrNull()?.let(onConfirm) ?: onDismiss() },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangePickerDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun appDetailsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(appDetailsIntent(context)) }.onFailure {
            Toast.makeText(context, "这台设备找不到对应的设置页，请手动进入系统设置", Toast.LENGTH_LONG).show()
        }
    }
}
