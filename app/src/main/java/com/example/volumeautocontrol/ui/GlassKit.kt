package com.example.volumeautocontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.volumeautocontrol.ui.theme.AuroraScrim
import com.example.volumeautocontrol.ui.theme.AuroraStops
import com.example.volumeautocontrol.ui.theme.Slate100
import com.example.volumeautocontrol.ui.theme.Slate300
import com.example.volumeautocontrol.ui.theme.Slate500
import com.example.volumeautocontrol.ui.theme.Slate600
import com.example.volumeautocontrol.ui.theme.Slate700

/**
 * 设计稿的背景是「渐变 + 白色蒙版 + 60px 背景模糊」。
 * 模糊在这里可以省掉：底下是一层平滑渐变，模糊平滑渐变看不出差别，而 Compose 也没有
 * 等价于 CSS backdrop-filter 的能力。白色蒙版必须保留，它负责把饱和度压成截图里的淡彩效果。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colorStops = AuroraStops.toTypedArray()))
            .background(AuroraScrim)
    )
}

/** 半透明白卡片。同理不做背景模糊，用不透明度直接压在渐变上，视觉等价。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    alpha: Float = 0.70f,
    contentPadding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(shape)
            .background(Color.White.copy(alpha = alpha))
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** 暂停次数卡片上的圆形加减钮。 */
@Composable
fun StepButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Slate100)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) Slate600 else Slate300,
        )
    }
}

/** 实时状态里的一行：左侧图标加标签，右侧带浅色底的数值。 */
@Composable
fun StatusLine(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = Slate500)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 14.sp, color = Slate500)
        }
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Slate700,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Slate100.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 底部的系统操作按钮，样式和玻璃卡片统一。 */
@Composable
fun GlassActionButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.06f),
            )
            .clip(shape)
            .background(Color.White.copy(alpha = 0.70f))
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Slate700)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Slate700)
    }
}
