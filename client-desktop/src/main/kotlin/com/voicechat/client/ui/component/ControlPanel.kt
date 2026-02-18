package com.voicechat.client.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors

@Composable
fun ControlPanel(
    isMuted: Boolean,
    isScreenSharing: Boolean,
    screenSharerNickname: String?,
    onToggleMute: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onDisconnect: () -> Unit
) {
    val canToggleScreenShare = screenSharerNickname == null || isScreenSharing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mute button
            val muteGradient by animateGradientAsState(
                target = if (isMuted)
                    listOf(AppColors.Surface3, AppColors.Surface3)
                else
                    listOf(AppColors.AccentLight, AppColors.Accent)
            )
            GradientActionButton(
                label = if (isMuted) "Unmute" else "Mute",
                gradient = muteGradient,
                modifier = Modifier.weight(1f),
                onClick = onToggleMute
            )

            // Screen share button
            val shareGradient by animateGradientAsState(
                target = when {
                    !canToggleScreenShare -> listOf(AppColors.TextMuted, AppColors.TextMuted)
                    isScreenSharing       -> listOf(Color(0xFFF43F5E), Color(0xFFDC2626))
                    else                  -> listOf(Color(0xFF10B981), Color(0xFF059669))
                }
            )
            GradientActionButton(
                label = if (isScreenSharing) "Stop Share" else "Share Screen",
                gradient = shareGradient,
                modifier = Modifier.weight(1f),
                enabled = canToggleScreenShare,
                onClick = onToggleScreenShare
            )
        }

        // Disconnect — subtle outlined style
        val disconnectInteraction = remember { MutableInteractionSource() }
        val isPressed by disconnectInteraction.collectIsPressedAsState()
        val disconnectScale by animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "disconnectScale"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .graphicsLayer { scaleX = disconnectScale; scaleY = disconnectScale }
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.DangerDim)
                .clickable(
                    interactionSource = disconnectInteraction,
                    indication = null
                ) { onDisconnect() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Disconnect",
                color = AppColors.Danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GradientActionButton(
    label: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "actionBtnScale"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else AppColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Animates between two gradient color lists by independently animating each color stop.
 * Returns a State<List<Color>> so Compose recompositions are efficient.
 */
@Composable
private fun animateGradientAsState(target: List<Color>): State<List<Color>> {
    val c0 by animateColorAsState(target[0], tween(250), label = "g0")
    val c1 by animateColorAsState(target[1], tween(250), label = "g1")
    return remember { derivedStateOf { listOf(c0, c1) } }
}
