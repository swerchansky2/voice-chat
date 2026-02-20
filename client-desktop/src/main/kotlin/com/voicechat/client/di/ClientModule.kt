package com.voicechat.client.di

import com.voicechat.client.audio.AudioEngine
import com.voicechat.client.network.SignalingClient
import com.voicechat.client.viewmodel.VoiceChatViewModel
import org.koin.dsl.module

val clientModule = module {
    single { SignalingClient() }
    single { AudioEngine() }
    single { VoiceChatViewModel(get(), get()) }
}
