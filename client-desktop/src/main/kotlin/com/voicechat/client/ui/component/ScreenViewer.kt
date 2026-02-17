package com.voicechat.client.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
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
            val bitmap = withContext(Dispatchers.Default) {
                screenFrame.toComposeImageBitmap()
            }
            imageBitmap = bitmap
            imageSize = Pair(screenFrame.width, screenFrame.height)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1D))
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2B2D31))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (imageBitmap != null) AppColors.Danger else AppColors.TextSecondary)
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
                    text = "${imageSize.first}x${imageSize.second}",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary
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
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (sharerNickname != null) "Connecting to stream..." else "No active stream",
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawVideoFrame(bitmap: ImageBitmap) {
    val canvasWidth = size.width
    val canvasHeight = size.height
    val imgWidth = bitmap.width.toFloat()
    val imgHeight = bitmap.height.toFloat()

    val scaleX = canvasWidth / imgWidth
    val scaleY = canvasHeight / imgHeight
    val scale = minOf(scaleX, scaleY)

    val dstWidth = (imgWidth * scale).toInt()
    val dstHeight = (imgHeight * scale).toInt()
    val offsetX = ((canvasWidth - dstWidth) / 2).toInt()
    val offsetY = ((canvasHeight - dstHeight) / 2).toInt()

    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(offsetX, offsetY),
        dstSize = IntSize(dstWidth, dstHeight)
    )
}
