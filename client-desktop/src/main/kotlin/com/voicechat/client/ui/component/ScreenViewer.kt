package com.voicechat.client.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

@Composable
fun ScreenViewer(
    screenFrame: BufferedImage?,
    sharerNickname: String?,
    modifier: Modifier = Modifier
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageSize by remember { mutableStateOf(Pair(0, 0)) }

    LaunchedEffect(screenFrame) {
        if (screenFrame != null) {
            val bitmap = withContext(Dispatchers.Default) { screenFrame.toComposeImageBitmap() }
            imageBitmap = bitmap
            imageSize = Pair(screenFrame.width, screenFrame.height)
        }
    }

    val isLive = imageBitmap != null

    // Animated glow border when stream is active
    val infiniteTransition = rememberInfiniteTransition(label = "viewer")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "borderGlow"
    )
    // Loading spinner rotation
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin"
    )

    val borderBrush = if (isLive)
        Brush.linearGradient(
            listOf(AppColors.Danger.copy(alpha = borderAlpha), AppColors.Danger.copy(alpha = borderAlpha * 0.5f))
        )
    else
        Brush.linearGradient(listOf(AppColors.Border, AppColors.Border))

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isLive) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(12.dp)
            )
            .background(Color(0xFF0A0A12))
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161622))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Live indicator dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isLive) AppColors.Danger.copy(alpha = borderAlpha) else AppColors.TextMuted,
                            CircleShape
                        )
                )
                Text(
                    text = sharerNickname?.let { "$it's screen" } ?: "Screen Share",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
            }
            if (imageSize.first > 0) {
                Text(
                    text = "${imageSize.first}×${imageSize.second}",
                    fontSize = 11.sp,
                    color = AppColors.TextMuted
                )
            }
        }

        // Video area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val bmp = imageBitmap
            if (bmp != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawVideoFrame(bmp)
                }
            } else if (sharerNickname != null) {
                // Connecting state with spinner
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(40.dp)
                            .rotate(spinAngle)
                    ) {
                        drawArc(
                            color = AppColors.Accent,
                            startAngle = 0f,
                            sweepAngle = 260f,
                            useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "Connecting to stream...",
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                Text(
                    text = "No active stream",
                    color = AppColors.TextMuted,
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun DrawScope.drawVideoFrame(bitmap: ImageBitmap) {
    val scaleX = size.width / bitmap.width.toFloat()
    val scaleY = size.height / bitmap.height.toFloat()
    val scale = minOf(scaleX, scaleY)
    val dstW = (bitmap.width * scale).toInt()
    val dstH = (bitmap.height * scale).toInt()
    val offX = ((size.width - dstW) / 2).toInt()
    val offY = ((size.height - dstH) / 2).toInt()
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(offX, offY),
        dstSize = IntSize(dstW, dstH)
    )
}
