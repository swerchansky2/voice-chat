package com.voicechat.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Sidebar)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Screen Share Quality",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ScreenResolution.entries.forEach { resolution ->
                val isSelected = resolution == selectedResolution
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) AppColors.Accent else AppColors.DarkBackground
                        )
                        .clickable { onResolutionChanged(resolution) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resolution.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else AppColors.TextSecondary
                    )
                }
            }
        }
    }
}
