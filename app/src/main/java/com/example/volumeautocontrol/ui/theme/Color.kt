package com.example.volumeautocontrol.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 取自 V2 设计稿的 Bento Glassmorphism 浅色配色。
 * 背景渐变原稿在 oklab 空间插值，Compose 只能在 sRGB 空间插值，故中段紫粉过渡略有色差。
 */
val AuroraStops = listOf(
    0.00f to Color(0xFFF5EBD9),
    0.31f to Color(0xFFF2D4DB),
    0.50f to Color(0xFFEBBDDE),
    0.65f to Color(0xFFCCBAE3),
    0.82f to Color(0xFF8CBFF0),
    1.00f to Color(0xFF78B0FF),
)

/** 压在渐变之上的白色蒙版，对应设计稿的 bg-white/40，负责把饱和度压下来。 */
val AuroraScrim = Color(0x66FFFFFF)

val Blue500 = Color(0xFF3B82F6)
val Blue600 = Color(0xFF2563EB)

val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate600 = Color(0xFF475569)
val Slate500 = Color(0xFF64748B)
val Slate400 = Color(0xFF94A3B8)
val Slate300 = Color(0xFFCBD5E1)
val Slate200 = Color(0xFFE2E8F0)
val Slate100 = Color(0xFFF1F5F9)

/** 权限缺失时的提示卡配色，用琥珀色而非红色，避免正常流程里出现刺眼的报错感。 */
val Amber600 = Color(0xFFD97706)
val Amber100 = Color(0xFFFEF3C7)
