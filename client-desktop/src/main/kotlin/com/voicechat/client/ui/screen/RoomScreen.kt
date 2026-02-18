package com.voicechat.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

    Row(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {

        // ── Left sidebar ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(AppColors.Surface1)
        ) {
            // Room header with gradient accent line at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AppColors.Accent.copy(alpha = 0.15f), AppColors.Surface1),
                            start = Offset(0f, 0f),
                            end = Offset(0f, Float.POSITIVE_INFINITY)
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "# Voice Room",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = "${userList.size} connected",
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }

            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border))

            // Participants label
            Text(
                text = "PARTICIPANTS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = AppColors.TextMuted,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
            )

            // User list
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(userList) { user ->
                    UserListItem(
                        nickname = user,
                        isCurrentUser = user == currentNickname,
                        isMuted = user == currentNickname && isMuted,
                        isScreenSharing = user == screenSharerNickname
                    )
                }
            }

            // Screen share settings
            if (!isScreenSharing && screenSharerNickname == null) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border))
                ScreenSettingsPanel(
                    selectedResolution = screenSettings.resolution,
                    onResolutionChanged = { viewModel.updateScreenShareSettings(it) },
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Divider before controls
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border))

            // Controls
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

        // Sidebar right border
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(AppColors.Border))

        // ── Main content area ─────────────────────────────────────────────────
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().background(AppColors.Background),
            contentAlignment = Alignment.Center
        ) {
            if (isViewingScreenShare) {
                ScreenViewer(
                    screenFrame = receivedFrame,
                    sharerNickname = screenSharerNickname,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            } else {
                MainAreaPlaceholder(isScreenSharing = isScreenSharing)
            }
        }
    }
}

@Composable
private fun MainAreaPlaceholder(isScreenSharing: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Monitor icon via box shapes
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(52.dp)
                .background(AppColors.Surface3.copy(alpha = 0.6f))
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Background.copy(alpha = 0.8f))
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isScreenSharing) "You are sharing your screen" else "No screen share active",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary
        )
        Text(
            text = if (isScreenSharing) "Others in the room can see your screen"
            else "Press \"Share Screen\" to present",
            fontSize = 12.sp,
            color = AppColors.TextMuted
        )
    }
}
