plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.voicechat.server.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    
    // Ktor
    implementation("io.ktor:ktor-server-core:3.0.1")
    implementation("io.ktor:ktor-server-netty:3.0.1")
    implementation("io.ktor:ktor-server-websockets:3.0.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    
    // Koin
    implementation("io.insert-koin:koin-core:3.5.6")
    implementation("io.insert-koin:koin-ktor:3.5.6")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // WebRTC (native JNI)
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-aarch64")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
}

kotlin {
    jvmToolchain(17)
}
