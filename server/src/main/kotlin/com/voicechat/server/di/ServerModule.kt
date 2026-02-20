package com.voicechat.server.di

import com.voicechat.server.room.RoomManager
import com.voicechat.server.sfu.SfuManager
import com.voicechat.server.websocket.SignalingHandler
import org.koin.dsl.module

val serverModule = module {
    single { RoomManager() }
    single { SfuManager() }
    single { SignalingHandler(get(), get()) }
}
