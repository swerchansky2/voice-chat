package com.voicechat.client.ui

import androidx.compose.runtime.*
import com.voicechat.client.ui.screen.ConnectScreen
import com.voicechat.client.ui.screen.RoomScreen
import com.voicechat.client.ui.theme.AppTheme
import com.voicechat.client.viewmodel.ConnectionState
import com.voicechat.client.viewmodel.VoiceChatViewModel
import org.koin.java.KoinJavaComponent.inject

sealed class Screen {
    data object Connect : Screen()
    data class Room(val nickname: String) : Screen()
}

@Composable
fun VoiceChatApp() {
    val viewModel: VoiceChatViewModel by inject(VoiceChatViewModel::class.java)
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Connect) }
    var currentNickname by remember { mutableStateOf("") }
    
    val connectionState by viewModel.connectionState.collectAsState()
    
    // Auto-navigate to room when connected
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected && currentScreen is Screen.Connect) {
            currentScreen = Screen.Room(currentNickname)
        } else if (connectionState is ConnectionState.Disconnected && currentScreen is Screen.Room) {
            currentScreen = Screen.Connect
        }
    }

    AppTheme {
        when (val screen = currentScreen) {
            is Screen.Connect -> {
                ConnectScreen(
                    viewModel = viewModel,
                    onConnected = { nickname ->
                        currentNickname = nickname
                    }
                )
            }
            is Screen.Room -> {
                RoomScreen(
                    viewModel = viewModel,
                    currentNickname = screen.nickname,
                    onDisconnected = {
                        currentScreen = Screen.Connect
                    }
                )
            }
        }
    }
}

