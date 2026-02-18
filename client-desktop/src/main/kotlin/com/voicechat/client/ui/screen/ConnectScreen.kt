package com.voicechat.client.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors
import com.voicechat.client.viewmodel.ConnectionState
import com.voicechat.client.viewmodel.VoiceChatViewModel

@Composable
fun ConnectScreen(
    viewModel: VoiceChatViewModel,
    onConnected: (String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var serverHost by remember { mutableStateOf("localhost") }
    var serverPort by remember { mutableStateOf("8080") }

    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isConnecting = connectionState is ConnectionState.Connecting

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onConnected(nickname)
    }

    // Animated logo glow
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .drawBehind {
                // Subtle ambient radial glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(AppColors.Accent.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.38f),
                        radius = size.width * 0.55f
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width / 2f, size.height * 0.38f)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(370.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.Surface1)
                .border(1.dp, AppColors.Border, RoundedCornerShape(20.dp))
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Animated logo
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer pulsing glow ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer { scaleX = glowScale; scaleY = glowScale }
                        .background(AppColors.Accent.copy(alpha = glowAlpha), CircleShape)
                )
                // Gradient icon circle
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.linearGradient(listOf(AppColors.AccentLight, AppColors.Accent)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(30.dp)) { drawMicIcon() }
                }
            }

            // Title + subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Voice Chat",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "Connect to start talking",
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary
                )
            }

            // Input fields
            ConnectTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = "Nickname",
                placeholder = "Your name"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    label = "Server",
                    modifier = Modifier.weight(2f)
                )
                ConnectTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = "Port",
                    modifier = Modifier.weight(1f)
                )
            }

            // Animated error message
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.DangerDim)
                        .border(1.dp, AppColors.Danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(AppColors.Danger, CircleShape)
                    )
                    Text(text = errorMessage ?: "", color = AppColors.Danger, fontSize = 13.sp)
                }
            }

            // Gradient connect button with press animation
            val btnInteraction = remember { MutableInteractionSource() }
            val isPressed by btnInteraction.collectIsPressedAsState()
            val btnScale by animateFloatAsState(
                targetValue = if (isPressed) 0.97f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "btnScale"
            )
            val canConnect = nickname.isNotBlank() && !isConnecting

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (canConnect)
                            Brush.horizontalGradient(listOf(AppColors.AccentLight, AppColors.Accent, Color(0xFF7C3AED)))
                        else
                            Brush.horizontalGradient(listOf(AppColors.TextMuted, AppColors.TextMuted))
                    )
                    .clickable(
                        interactionSource = btnInteraction,
                        indication = null,
                        enabled = canConnect
                    ) {
                        viewModel.clearError()
                        viewModel.connect(nickname, serverHost, serverPort.toIntOrNull() ?: 8080)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isConnecting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Text("Connecting...", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Connect", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConnectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, color = AppColors.TextMuted) }) else null,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AppColors.TextPrimary,
            unfocusedTextColor = AppColors.TextPrimary,
            cursorColor = AppColors.Accent,
            focusedBorderColor = AppColors.Accent,
            unfocusedBorderColor = AppColors.Border,
            focusedLabelColor = AppColors.AccentLight,
            unfocusedLabelColor = AppColors.TextMuted,
            focusedContainerColor = AppColors.Surface2,
            unfocusedContainerColor = AppColors.Surface2,
        )
    )
}

private fun DrawScope.drawMicIcon() {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    val style = Stroke(width = sw, cap = StrokeCap.Round)
    val color = Color.White

    // Mic capsule body
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.35f, h * 0.04f),
        size = Size(w * 0.3f, h * 0.52f),
        cornerRadius = CornerRadius(w * 0.15f),
        style = style
    )
    // Stand arc
    drawArc(
        color = color,
        startAngle = 0f, sweepAngle = 180f, useCenter = false,
        style = style,
        topLeft = Offset(w * 0.18f, h * 0.35f),
        size = Size(w * 0.64f, h * 0.36f)
    )
    // Stem
    drawLine(
        color = color,
        start = Offset(w * 0.5f, h * 0.71f),
        end = Offset(w * 0.5f, h * 0.88f),
        strokeWidth = sw, cap = StrokeCap.Round
    )
    // Base
    drawLine(
        color = color,
        start = Offset(w * 0.3f, h * 0.88f),
        end = Offset(w * 0.7f, h * 0.88f),
        strokeWidth = sw, cap = StrokeCap.Round
    )
}
