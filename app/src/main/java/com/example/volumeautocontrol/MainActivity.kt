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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.volumeautocontrol.ui.theme.VolumeAutoControlTheme

class MainActivity : ComponentActivity() {

    // 从系统设置页返回时用它触发一次权限状态刷新。
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardState.load(this)
        enableEdgeToEdge()
        setContent {
            VolumeAutoControlTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GuardScreen(
                        refreshKey = refreshKey,
                        modifier = Modifier.padding(innerPadding),
                    )
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
    var limitInput by remember { mutableStateOf(GuardState.pauseLimit.toString()) }
    var editingScheduleStart by remember { mutableStateOf(false) }
    var editingScheduleEnd by remember { mutableStateOf(false) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("耳机守护", style = MaterialTheme.typography.headlineMedium)
        Text(
            "摘下耳机后自动把媒体音量降到 0，之后任何应用尝试播放都会被立刻暂停并提示你。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!hasNotificationAccess) {
            SetupCard(
                title = "还需要开启「通知使用权」",
                description = "没有这个权限就拿不到其它应用的播放控制权。点下面的按钮，在列表里找到「耳机守护」并允许。",
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

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusRow("守护服务", serviceStatusText(hasNotificationAccess))
                StatusRow("耳机", if (GuardState.headsetConnected) "已连接" else "未连接")
                StatusRow("媒体音量", "$volume / $maxVolume")
                StatusRow("生效时段", scheduleStatusText(withinSchedule))
                StatusRow("本轮拦截", interceptStatusText(withinSchedule))
            }
        }

        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用守护", style = MaterialTheme.typography.titleMedium)
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
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("暂停次数", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(2)
                        limitInput = digits
                        digits.toIntOrNull()?.let { GuardState.setPauseLimit(context, it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "耳机断开后，前 ${GuardState.pauseLimit} 次播放会被暂停，之后本轮不再限制。" +
                        "插回耳机或下次断开时重新计数。填 0 表示完全不拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "另外，断开期间只要你自己动过音量键，就会立刻停止拦截——那说明你清楚自己在外放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("仅在时间段内生效", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = GuardState.scheduleEnabled,
                        onCheckedChange = { GuardState.setScheduleEnabled(context, it) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { editingScheduleStart = true },
                        enabled = GuardState.scheduleEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("开始 ${GuardState.formatMinutes(GuardState.scheduleStart)}")
                    }
                    OutlinedButton(
                        onClick = { editingScheduleEnd = true },
                        enabled = GuardState.scheduleEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("结束 ${GuardState.formatMinutes(GuardState.scheduleEnd)}")
                    }
                }
                Text(
                    scheduleHintText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "时段结束时不会自动恢复音量，避免在你没操作的情况下突然外放。" +
                        "时段开始时如果耳机不在位，会补一次静音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

        Text("最近事件", style = MaterialTheme.typography.titleMedium)
        if (GuardState.events.isEmpty()) {
            Text(
                "还没有记录。插上耳机再拔掉试试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            GuardState.events.forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        event.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(event.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton(
            onClick = {
                GuardForegroundService.stop(context)
                reviveGuard(context)
                Toast.makeText(context, "已请求重启守护服务", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重启守护服务")
        }

        OutlinedButton(
            onClick = { openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("加入电池优化白名单")
        }
        Text(
            "国内定制系统容易在后台杀掉守护服务，建议顺手把本应用设为「允许自启动」和「无限制耗电」，" +
                "并在最近任务卡片上下拉锁定它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    !GuardState.scheduleEnabled -> "关闭时全天生效。"
    GuardState.scheduleStart == GuardState.scheduleEnd -> "起止时间相同，等同于全天生效。"
    GuardState.scheduleStart > GuardState.scheduleEnd ->
        "跨午夜时段：当天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到次日 " +
            "${GuardState.formatMinutes(GuardState.scheduleEnd)} 之间生效。"
    else -> "每天 ${GuardState.formatMinutes(GuardState.scheduleStart)} 到 " +
        "${GuardState.formatMinutes(GuardState.scheduleEnd)} 之间生效，其余时间不介入。"
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

@Composable
private fun SetupCard(title: String, description: String, buttonText: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
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
