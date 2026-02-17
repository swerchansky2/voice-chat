package com.voicechat.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors

@Composable
fun UserListItem(
    nickname: String,
    isCurrentUser: Boolean = false,
    isMuted: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (isMuted) AppColors.TextSecondary 
                    else AppColors.Success
                )
        )
        
        // Nickname
        Text(
            text = if (isCurrentUser) "$nickname (you)" else nickname,
            color = if (isCurrentUser) AppColors.Accent else AppColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.Normal
        )
        
        // Muted indicator
        if (isMuted) {
            Text(
                text = "🔇",
                fontSize = 16.sp
            )
        }
    }
}
