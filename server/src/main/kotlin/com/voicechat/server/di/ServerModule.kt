package com.voicechat.server.di

import com.voicechat.server.audio.UdpAudioRelay
import com.voicechat.server.room.RoomManager
import com.voicechat.server.video.UdpVideoRelay
import com.voicechat.server.websocket.SignalingHandler
import org.koin.dsl.module

val serverModule = module {
    single { RoomManager() }
    single { SignalingHandler(get()) }
    single { UdpAudioRelay(get()) }
    single { UdpVideoRelay(get()) }
}
