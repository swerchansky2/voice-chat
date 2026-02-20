package com.voicechat.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.component.ControlPanel
import com.voicechat.client.ui.component.ScreenSourceDialog
import com.voicechat.client.ui.component.UserListItem
import com.voicechat.client.ui.component.VideoDisplay
import com.voicechat.client.ui.theme.AppColors
import com.voicechat.client.viewmodel.VoiceChatViewModel

@Composable
fun RoomScreen(
    viewModel: VoiceChatViewModel,
    currentNickname: String?,
    onDisconnected: () -> Unit
) {
    val userList by viewModel.userList.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val screenShareState by viewModel.screenShareState.collectAsState()
    val videoFrame by viewModel.currentVideoFrame.collectAsState()

    var showSourceDialog by remember { mutableStateOf(false) }

    if (showSourceDialog) {
        val screens = remember { viewModel.getAvailableScreens() }
        val windows = remember { viewModel.getAvailableWindows() }

        ScreenSourceDialog(
            screens = screens,
            windows = windows,
            onSourceSelected = { source ->
                showSourceDialog = false
                viewModel.startScreenShare(source.id, source.isWindow)
            },
            onDismiss = { showSourceDialog = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(AppColors.Sidebar)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Voice Room",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
            }

            HorizontalDivider(color = AppColors.DarkBackground)

            Text(
                text = "Users (${userList.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(userList) { user ->
                    UserListItem(
                        nickname = user,
                        isCurrentUser = user == currentNickname,
                        isMuted = user == currentNickname && isMuted
                    )
                }
            }

            HorizontalDivider(color = AppColors.DarkBackground)

            ControlPanel(
                isMuted = isMuted,
                isScreenSharing = screenShareState.isSelf,
                screenShareActive = screenShareState.active,
                onToggleMute = { viewModel.toggleMute() },
                onToggleScreenShare = {
                    if (screenShareState.isSelf) {
                        viewModel.stopScreenShare()
                    } else {
                        showSourceDialog = true
                    }
                },
                onDisconnect = {
                    viewModel.disconnect()
                    onDisconnected()
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AppColors.Background)
        ) {
            if (screenShareState.active) {
                VideoDisplay(
                    bitmap = if (!screenShareState.isSelf) videoFrame else null,
                    sharerNickname = screenShareState.sharerNickname,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active screen share",
                        color = AppColors.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
