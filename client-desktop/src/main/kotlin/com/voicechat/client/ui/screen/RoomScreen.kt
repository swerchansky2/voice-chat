package com.voicechat.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.component.ControlPanel
import com.voicechat.client.ui.component.ScreenSettingsPanel
import com.voicechat.client.ui.component.ScreenViewer
import com.voicechat.client.ui.component.UserListItem
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
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val screenSharerNickname by viewModel.screenSharerNickname.collectAsState()
    val receivedFrame by viewModel.receivedScreenFrame.collectAsState()
    val screenSettings by viewModel.screenShareSettings.collectAsState()

    val isViewingScreenShare = screenSharerNickname != null && !isScreenSharing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Sidebar)
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

        // Screen viewer when receiving screen share
        if (isViewingScreenShare) {
            ScreenViewer(
                screenFrame = receivedFrame,
                sharerNickname = screenSharerNickname,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .padding(8.dp)
            )

            HorizontalDivider(color = AppColors.DarkBackground)
        }

        // User list
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
                    isMuted = user == currentNickname && isMuted,
                    isScreenSharing = user == screenSharerNickname
                )
            }
        }

        HorizontalDivider(color = AppColors.DarkBackground)

        // Screen share settings (show when not sharing and nobody else is sharing)
        if (!isScreenSharing && screenSharerNickname == null) {
            ScreenSettingsPanel(
                selectedResolution = screenSettings.resolution,
                onResolutionChanged = { viewModel.updateScreenShareSettings(it) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // Control panel
        ControlPanel(
            isMuted = isMuted,
            isScreenSharing = isScreenSharing,
            screenSharerNickname = screenSharerNickname,
            onToggleMute = { viewModel.toggleMute() },
            onToggleScreenShare = { viewModel.toggleScreenShare() },
            onDisconnect = {
                viewModel.disconnect()
                onDisconnected()
            }
        )
    }
}
