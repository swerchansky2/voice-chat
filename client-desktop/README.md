# Voice Chat Desktop Client

Desktop client for the voice chat application built with Compose for Desktop.

## Features

- **Discord-style UI** with dark theme
- **WebSocket signaling** for room management  
- **UDP audio streaming** with PCM support
- **Microphone capture** and **speaker playback**
- **Real-time user list** with connection status
- **Mute/Unmute controls**

## Architecture

### UI Layer
- **ConnectScreen**: Nickname and server configuration
- **RoomScreen**: Voice chat room with user list
- **AppTheme**: Discord-style dark theme (#36393F background, #5865F2 accent)

### Network Layer
- **SignalingClient**: WebSocket client for room signaling (Ktor Client)
- **UdpAudioClient**: UDP client for audio packet transmission

### Audio Layer
- **AudioCapture**: Microphone input via javax.sound.sampled (48kHz, 16-bit, mono)
- **AudioPlayback**: Speaker output via javax.sound.sampled
- **OpusCodec**: Codec wrapper (currently PCM passthrough, Opus TODO)
- **AudioEngine**: Coordinates capture, encoding, transmission, and playback

### State Management
- **VoiceChatViewModel**: Manages connection state, user list, mute status using Kotlin Flow
- **Koin DI**: Dependency injection for all components

## Building

```bash
./gradlew :client-desktop:build
```

## Running

```bash
./gradlew :client-desktop:run
```

Note: In restricted network environments, you may need to pre-download dependencies or configure offline repositories.

## Configuration

Default settings:
- **Server host**: localhost
- **Server port**: 8080 (WebSocket)
- **UDP port**: 9001 (auto-assigned local port)
- **Audio format**: 48kHz, 16-bit, mono, 960 samples/frame (20ms)

## TODO

- [ ] Integrate Concentus library for Opus encoding/decoding
- [ ] Add voice activity detection (VAD)
- [ ] Add audio level indicators
- [ ] Add push-to-talk mode
- [ ] Add connection quality indicators
- [ ] Add settings panel for audio device selection
