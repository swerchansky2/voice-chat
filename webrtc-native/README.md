# webrtc-native

This module contains a minimal C++ stub for future WebRTC screen-sharing integration.

What it contains:
- `src/native/webrtc_screen_share.h` — header declaring C functions to initialize/shutdown the stub.
- `src/native/webrtc_screen_share.cpp` — trivial implementation that logs to stderr.

Notes:
- The module is intentionally lightweight and doesn't use Gradle native toolchains yet.
- Next steps: add a Gradle native config (cpp-library / cxx) or a CMake build and link the produced library from the JVM code via JNI.
