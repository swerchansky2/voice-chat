package com.voicechat.client.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors
import java.awt.image.BufferedImage

@Composable
fun ScreenViewer(
    screenFrame: BufferedImage?,
    sharerNickname: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.DarkBackground)
    ) {
        if (sharerNickname != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Sidebar)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "$sharerNickname is sharing their screen",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Success
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppColors.DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            if (screenFrame != null) {
                val imageBitmap: ImageBitmap = remember(screenFrame) {
                    screenFrame.toComposeImageBitmap()
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Screen share",
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = if (sharerNickname != null) "Waiting for screen data..." else "No screen share active",
                    color = AppColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}
