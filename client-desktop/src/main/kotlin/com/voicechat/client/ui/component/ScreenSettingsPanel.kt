package com.voicechat.client.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.screen.ScreenResolution
import com.voicechat.client.ui.theme.AppColors

@Composable
fun ScreenSettingsPanel(
    selectedResolution: ScreenResolution,
    onResolutionChanged: (ScreenResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "QUALITY",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = AppColors.TextMuted
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ScreenResolution.entries.forEach { resolution ->
                val isSelected = resolution == selectedResolution

                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) AppColors.AccentDim else AppColors.Surface3,
                    animationSpec = tween(200),
                    label = "resBg"
                )
                val borderColor by animateColorAsState(
                    targetValue = if (isSelected) AppColors.Accent else AppColors.Border,
                    animationSpec = tween(200),
                    label = "resBorder"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) AppColors.AccentLight else AppColors.TextSecondary,
                    animationSpec = tween(200),
                    label = "resText"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .clickable { onResolutionChanged(resolution) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resolution.label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}
