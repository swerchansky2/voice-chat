package com.voicechat.client

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.voicechat.client.di.clientModule
import com.voicechat.client.ui.VoiceChatApp
import org.koin.core.context.startKoin

fun main() = application {
    startKoin {
        modules(clientModule)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Voice Chat",
        state = rememberWindowState(width = 400.dp, height = 600.dp)
    ) {
        VoiceChatApp()
    }
}
