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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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

/** 半宽卡片顶部那一行的高度，暂停次数图标、时段图标、时段开关三者靠它对齐中心线。 */
private val TOP_ROW_HEIGHT = 32.dp

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(active = guardActive, subtitle = heroSubtitle(hasNotificationAccess, withinSchedule))

        if (!hasNotificationAccess) {
            SetupCard(
                title = "还要开「通知使用权」",
                description = "没有它就没法暂停别的应用的播放，守护等于没开。点下面的按钮，在列表里找到「耳机守护」打开开关。",
                buttonText = "去开启",
                onClick = { openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }

        if (!canPostNotifications) {
            SetupCard(
                title = "还要允许发通知",
                description = "拦下播放时会发条通知说明原因，不然你会莫名其妙听不到声音。",
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
                // 这个高度同时决定首屏的收尾：实时状态卡要完整露出、下方留一段空隙，
                // 而「最近事件」标题要正好被顶到屏幕外。
                .height(182.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            Toast.makeText(context, "正在重启守护服务", Toast.LENGTH_SHORT).show()
        }

        GlassActionButton(Icons.Outlined.Tune, "取消电池优化限制") {
            openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        ManualPanel(expanded = manualExpanded, onToggle = { manualExpanded = !manualExpanded })

        Text(
            "建议把它设成「允许自启动」，再在最近任务里锁定，免得被系统清理掉。",
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
            title = "从几点开始",
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
            title = "到几点结束",
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
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
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
        Spacer(Modifier.height(12.dp))
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
                Text("耳机一断开就静音，防止突然外放", fontSize = 13.sp, color = Slate500)
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
    GlassCard(
        modifier = modifier,
        contentPadding = 16.dp,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与生效时段卡片共用 TOP_ROW_HEIGHT，两张卡片的顶部元素中心线才能对齐。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Slate300,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("暂停次数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Slate600)
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(4.dp))
        Text("次之后就不拦了", fontSize = 11.sp, color = Slate400)
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
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_ROW_HEIGHT),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = Slate300)
            // Switch 内部带 48dp 的最小可点尺寸，直接放进 32dp 的行会撑破行高、
            // 中心线又和图标错开；用 requiredHeight 把外框锁成 32dp，Switch 在框内
            // 居中，视觉中心就等于行的中心。
            Box(
                modifier = Modifier.requiredHeight(TOP_ROW_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Switch(
                    checked = GuardState.scheduleEnabled,
                    onCheckedChange = { GuardState.setScheduleEnabled(context, it) },
                    colors = switchColors(),
                    modifier = Modifier.scale(0.75f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(modifier = Modifier.alpha(if (GuardState.scheduleEnabled) 1f else 0.4f)) {
            Text("生效时段", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Slate600)
            Spacer(Modifier.height(2.dp))
            TimeText(GuardState.formatMinutes(GuardState.scheduleStart), GuardState.scheduleEnabled, onStartClick)
            Text("至", fontSize = 12.sp, color = Slate400)
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
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StatusLine(Icons.Outlined.Shield, "守护服务", serviceStatusText(hasNotificationAccess))
            StatusLine(
                Icons.Outlined.Headphones,
                "耳机连接",
                if (GuardState.headsetConnected) "已连接" else "未连接",
            )
            StatusLine(
                // volume 由 MainActivity 的 ContentObserver 实时推上来，静音与有声的图标随之切换。
                if (volume > 0) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                "媒体音量",
                "$volume / $maxVolume",
            )
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
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp),
        )
        GlassCard(Modifier.fillMaxWidth(), radius = 20.dp, alpha = 0.60f, contentPadding = 16.dp) {
            if (GuardState.events.isEmpty()) {
                Text("还没有记录。插上耳机再断开试试。", fontSize = 13.sp, color = Slate500)
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
                    "耳机断开后，前 ${GuardState.pauseLimit} 次播放都会被暂停，再播就不拦了。" +
                        "插回耳机、或者下次断开时重新数。填 0 就完全不拦。"
                )
                ManualText("断开期间只要你按过音量键，就立刻不拦了——按了就说明你知道在外放。")
                ManualText(scheduleHintText())
                ManualText(
                    "时段结束时不会自动把音量调回来，免得你没动手就突然外放。" +
                        "时段开始时如果耳机不在，会补一次静音。"
                )
                ManualText("插回耳机时也不动音量。耳机那边的音量系统自己记着，插上就是原来那档。")
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
    !GuardState.enabled -> "守护已关闭"
    !hasNotificationAccess -> "还没给通知使用权"
    !withinSchedule -> "不在生效时段，暂时不管"
    GuardState.bypassed -> "这轮不拦了"
    else -> "正在守护"
}

private fun serviceStatusText(hasNotificationAccess: Boolean): String = when {
    !hasNotificationAccess -> "没通知使用权"
    GuardState.serviceConnected -> "运行中"
    else -> "正在启动"
}

// 实时状态右侧的胶囊标签空间很紧，这几个取值要保持短，长了会折行撑高卡片。
private fun interceptStatusText(withinSchedule: Boolean): String = when {
    !withinSchedule -> "时段外不拦"
    GuardState.headsetConnected -> "耳机还在，不拦"
    GuardState.bypassed -> "这轮不再拦"
    else -> "${GuardState.interceptCount} / ${GuardState.pauseLimit} 次"
}

private fun scheduleStatusText(withinSchedule: Boolean): String = when {
    !GuardState.scheduleEnabled -> "全天"
    withinSchedule -> "时段内"
    else -> "时段外"
}

private fun scheduleHintText(): String = when {
    !GuardState.scheduleEnabled -> "没开生效时段，现在是全天生效。"
    GuardState.scheduleStart == GuardState.scheduleEnd -> "起止时间一样，等于全天生效。"
    GuardState.scheduleStart > GuardState.scheduleEnd ->
        "跨午夜：当天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到次日 " +
            "${GuardState.formatMinutes(GuardState.scheduleEnd)} 之间生效。"
    else -> "每天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到 " +
        "${GuardState.formatMinutes(GuardState.scheduleEnd)} 生效，其它时间什么都不做。"
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
                    "填 0 到 ${GuardState.MAX_PAUSE_LIMIT}，填 0 就完全不拦。",
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
            Toast.makeText(context, "打不开这个设置页，请到系统设置里手动找一下", Toast.LENGTH_LONG).show()
        }
    }
}
