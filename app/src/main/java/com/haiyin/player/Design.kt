package com.haiyin.player

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern Design Tokens & Micro-components for HaiYin.
 */
object D {
    val bg = Color(0xFF090D14)               // 深邃夜色
    val bgSubtle = Color(0xFF0F1420)         // 微亮底色
    val surface = Color(0xFF151C2A)          // 卡片表面
    val surfaceVar = Color(0xFF1D2638)       // 悬浮条 / 交互项底色
    val surfaceBorder = Color(0x1FFFFFFF)    // 边框微光感
    val accent = Color(0xFF38BDF8)           // 流光天空蓝
    val accentGlow = Color(0x4038BDF8)       // 主色弥散光
    val textPrimary = Color(0xFFF1F5F9)      // 一级文字 (高对比)
    val textDim = Color(0xFF94A3B8)          // 次级文字
    val textFaint = Color(0xFF64748B)        // 弱化文字 / 占位
    val fav = Color(0xFFFB7185)              // 收藏桃红
    val cardGlow = Color(0x1038BDF8)
}

/** 3-Bar Jumping Equalizer animation for playing song indicator */
@Composable
fun MiniEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = D.accent,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val h1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = if (isPlaying) 1f else 0.3f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 0.8f, targetValue = if (isPlaying) 0.2f else 0.4f,
        animationSpec = infiniteRepeatable(tween(550, delayMillis = 100, easing = LinearEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 0.4f, targetValue = if (isPlaying) 0.9f else 0.3f,
        animationSpec = infiniteRepeatable(tween(480, delayMillis = 50, easing = LinearEasing), RepeatMode.Reverse),
        label = "h3"
    )

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(h1, h2, h3).forEach { factor ->
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight(if (isPlaying) factor.coerceIn(0.2f, 1f) else 0.25f)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Ambient glow backdrop for full player, derived from album art color.
 */
@Composable
fun AmbientGlowBackground(
    dominantColor: Color,
    modifier: Modifier = Modifier,
) {
    val targetColor = remember(dominantColor) {
        if (dominantColor == Color.Unspecified || dominantColor == Color.Black || dominantColor == Color.Transparent) {
            D.accent
        } else {
            dominantColor
        }
    }

    Box(modifier = modifier.fillMaxSize().background(D.bg)) {
        // Top-left ambient orb
        Box(
            modifier = Modifier
                .size(340.dp)
                .offset(x = (-60).dp, y = (-40).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(targetColor.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        // Center-right ambient orb
        Box(
            modifier = Modifier
                .size(380.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 100.dp, y = 60.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(targetColor.copy(alpha = 0.25f), Color.Transparent)
                    )
                )
        )
        // Overlay dark scrim to maintain text readability (7:1 contrast)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            D.bg.copy(alpha = 0.65f),
                            D.bg.copy(alpha = 0.85f),
                            D.bg.copy(alpha = 0.95f)
                        )
                    )
                )
        )
    }
}

/**
 * Extracts a representative vibrant color from album art bitmap.
 */
fun extractDominantColor(bitmap: Bitmap?): Color {
    if (bitmap == null || bitmap.isRecycled) return D.accent
    return try {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return D.accent

        val stepX = (width / 12).coerceAtLeast(1)
        val stepY = (height / 12).coerceAtLeast(1)
        var bestColor = D.accent
        var maxScore = -1f
        val hsv = FloatArray(3)

        for (x in 0 until width step stepX) {
            for (y in 0 until height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // filter out pure darks, pure whites, and muddy grays
                if (sat in 0.25f..0.95f && value in 0.25f..0.90f) {
                    val score = sat * 1.5f + value
                    if (score > maxScore) {
                        maxScore = score
                        bestColor = Color(pixel)
                    }
                }
            }
        }
        bestColor
    } catch (_: Exception) {
        D.accent
    }
}

/** HitText with ripple and 44x44 minimum touch target */
@Composable
fun HitText(
    text: String,
    onClick: () -> Unit,
    color: Color = D.textDim,
    selected: Boolean = false,
    selectedColor: Color = D.accent,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(color = selectedColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = fontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) selectedColor else color,
        )
    }
}

/** Modern pill chip with rounded corner, subtle border and ripple state */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (selected) D.accent.copy(alpha = 0.16f) else D.surfaceVar.copy(alpha = 0.5f)
    val border = if (selected) D.accent.copy(alpha = 0.5f) else D.surfaceBorder

    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(color = D.accent),
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) D.accent else D.textDim,
        )
    }
}
