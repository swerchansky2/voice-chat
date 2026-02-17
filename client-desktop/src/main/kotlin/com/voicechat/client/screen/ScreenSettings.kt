package com.voicechat.client.screen

enum class ScreenResolution(val width: Int, val height: Int, val label: String, val bitrate: Int) {
    FULL_HD(1920, 1080, "1080p (Full HD)", 6_000_000),
    QHD(2560, 1440, "1440p (2K)", 12_000_000),
    UHD(3840, 2160, "2160p (4K)", 24_000_000);
}

data class ScreenShareSettings(
    val resolution: ScreenResolution = ScreenResolution.FULL_HD,
    val fps: Int = 60
)
