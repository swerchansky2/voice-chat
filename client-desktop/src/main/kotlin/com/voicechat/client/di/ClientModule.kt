package com.voicechat.client.di

import com.voicechat.client.AppConfig
import com.voicechat.client.audio.AudioEngine
import com.voicechat.client.network.SignalingClient
import com.voicechat.client.network.UdpAudioClient
import com.voicechat.client.screen.ScreenEngine
import com.voicechat.client.viewmodel.VoiceChatViewModel
import org.koin.dsl.module

val clientModule = module {
    single { SignalingClient(maxFrameSize = AppConfig.WS_MAX_FRAME_SIZE) }
    single { UdpAudioClient() }
    single { AudioEngine(get()) }
    single { ScreenEngine(get()) }
    single { VoiceChatViewModel(get(), get(), get(), get()) }
}
