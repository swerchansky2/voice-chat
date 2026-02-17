package com.voicechat.client.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onToggleMute,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMuted) AppColors.TextSecondary else AppColors.Accent,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isMuted) "Unmute" else "Mute",
                    fontSize = 14.sp
                )
            }

            Button(
                onClick = onToggleScreenShare,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = canToggleScreenShare,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScreenSharing) AppColors.Danger else AppColors.Success,
                    contentColor = Color.White,
                    disabledContainerColor = AppColors.TextSecondary
                )
            ) {
                Text(
                    text = if (isScreenSharing) "Stop Share" else "Share Screen",
                    fontSize = 14.sp
                )
            }
        }

        Button(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Danger,
                contentColor = Color.White
            )
        ) {
            Text("Disconnect", fontSize = 14.sp)
        }
    }
}
