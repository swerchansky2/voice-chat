package com.voicechat.client.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import com.voicechat.client.ui.theme.AppColors
import dev.onvoid.webrtc.media.video.desktop.DesktopSource

data class SourceSelection(
    val sourceId: Long,
    val isWindow: Boolean,
    val title: String
)

@Composable
fun ScreenSourceDialog(
    screens: List<DesktopSource>,
    windows: List<DesktopSource>,
    onSourceSelected: (SourceSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Screens", "Windows")

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .heightIn(max = 500.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Sidebar)
                .padding(16.dp)
        ) {
            Text(
                text = "Choose source to share",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = AppColors.DarkBackground,
                contentColor = AppColors.TextPrimary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) AppColors.Accent else AppColors.TextSecondary
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val sources = if (selectedTab == 0) screens else windows
            val isWindow = selectedTab == 1

            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isWindow) "No windows found" else "No screens found",
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sources) { source ->
                        SourceItem(
                            title = source.title.ifEmpty { "Screen ${source.id}" },
                            onClick = {
                                onSourceSelected(
                                    SourceSelection(source.id, isWindow, source.title)
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.TextSecondary,
                    contentColor = Color.White
                )
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SourceItem(
    title: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.Background)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = AppColors.TextPrimary,
            fontSize = 14.sp
        )
    }
}
