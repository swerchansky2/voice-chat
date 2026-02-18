package com.voicechat.client.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors
import kotlin.math.absoluteValue

// Deterministic avatar colors based on nickname hash
private val avatarGradients = listOf(
    AppColors.AccentLight to AppColors.Accent,
    Color(0xFF10B981) to Color(0xFF059669),
    Color(0xFFF59E0B) to Color(0xFFD97706),
    Color(0xFF8B5CF6) to Color(0xFF7C3AED),
    Color(0xFF06B6D4) to Color(0xFF0891B2),
    Color(0xFFF43F5E) to Color(0xFFE11D48),
)

@Composable
fun UserListItem(
    nickname: String,
    isCurrentUser: Boolean = false,
    isMuted: Boolean = false,
    isScreenSharing: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) AppColors.Surface2 else Color.Transparent,
        animationSpec = tween(150),
        label = "hoverBg"
    )

    // Pick a consistent gradient color based on nickname hash
    val (gradStart, gradEnd) = avatarGradients[nickname.hashCode().absoluteValue % avatarGradients.size]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .hoverable(interactionSource)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Avatar with gradient and initials
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    Brush.linearGradient(listOf(gradStart, gradEnd)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = nickname.first().uppercaseChar().toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Nickname
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isCurrentUser) "$nickname (you)" else nickname,
                color = if (isCurrentUser) AppColors.AccentLight else AppColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isCurrentUser) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        // LIVE badge with blinking dot
        if (isScreenSharing) {
            val liveTransition = rememberInfiniteTransition(label = "live")
            val blinkAlpha by liveTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
                label = "blink"
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.DangerDim)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(AppColors.Danger.copy(alpha = blinkAlpha), CircleShape)
                )
                Text(
                    text = "LIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Danger
                )
            }
        }

        // Status dot (right side)
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (isMuted) AppColors.TextMuted else AppColors.Success,
                    CircleShape
                )
        )
    }
}
