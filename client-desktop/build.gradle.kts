plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

fun getWebRtcClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") && arch.contains("aarch64") -> "macos-aarch64"
        os.contains("mac") -> "macos-x86_64"
        os.contains("linux") && arch.contains("aarch64") -> "linux-aarch64"
        os.contains("linux") -> "linux-x86_64"
        os.contains("win") -> "windows-x86_64"
        else -> throw GradleException("Unsupported platform: $os $arch")
    }
}

dependencies {
    implementation(project(":shared"))
    
    // Compose for Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    
    // Ktor Client
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-websockets:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    
    // Koin
    implementation("io.insert-koin:koin-core:3.5.6")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    
    // Opus codec (pure Java)
    implementation("io.github.jaredmdobson:concentus:1.0.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")

    // WebRTC for screen sharing (P2P video with hardware encoding)
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:${getWebRtcClassifier()}")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.voicechat.client.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "VoiceChat"
            packageVersion = "1.0.0"
        }
    }
}
