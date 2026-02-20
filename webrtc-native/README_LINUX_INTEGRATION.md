Linux integration notes — libwebrtc (Chromium) for webrtc-native

Goal

This document explains how to integrate a prebuilt libwebrtc into the `webrtc-native` Gradle native library so the C++ `PeerConnectionManager` can be implemented using the real WebRTC stack on Linux.

Options

1) Use prebuilt libwebrtc binaries (.so)
   - Easiest for development: place headers and libraries from a compatible libwebrtc build on disk and point Gradle at them.
2) Build libwebrtc from source
   - Production-quality but heavy: requires depot_tools, GN, Ninja and substantial disk/time to build. See the official build docs.

File layout expected

Provide two locations (either via environment variables or Gradle properties):

- Headers: WEBRTC_INCLUDE_DIR or -PwebrtcIncludeDir=/path/to/webrtc/include
  Expected to contain top-level headers (for example, <api/peer_connection_interface.h>, <rtc_base/thread.h>, etc.)

- Libraries: WEBRTC_LIB_DIR or -PwebrtcLibDir=/path/to/webrtc/libs
  Expected to contain libwebrtc.so (or a set of .so files). The current Gradle wiring links with `-lwebrtc` and adds `-L` to the link path.

How to build/link locally (example)

1) If you already have prebuilt headers/libs, export environment variables:

```bash
export WEBRTC_INCLUDE_DIR=/opt/webrtc/include
export WEBRTC_LIB_DIR=/opt/webrtc/lib

# Or pass as Gradle properties:
./gradlew :webrtc-native:assemble -PwebrtcIncludeDir=/opt/webrtc/include -PwebrtcLibDir=/opt/webrtc/lib
```

2) Build the native library (Gradle will pick up include dirs and pass -L/path -lwebrtc at link time):

```bash
# Linux (example using Java 21 toolchain already configured in repo):
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :webrtc-native:assemble
```

In-repo GN/Ninja build (Linux-first)

If you prefer to build libwebrtc from source inside the repository (useful for development and debugging), you can place the libwebrtc source tree under `webrtc-native/webrtc-src` and use GN + Ninja to build it. This project includes a convenience Gradle task `:webrtc-native:buildWebrtcInRepo` which runs `gn gen out/Default && ninja -C out/Default` when `webrtc-src` is present.

There is also a helper script `webrtc-native/scripts/build_webrtc.sh` which wraps the common steps and performs basic checks (gn, ninja in PATH). Usage:

```bash
cd webrtc-native
./scripts/build_webrtc.sh
```

By default the Gradle wiring uses `out/Default` as the GN output. After a successful in-repo build, Gradle will add `out/Default/include` to the native compile includes and link against libraries in the GN out dir. If your GN configuration places artifacts elsewhere, update the Gradle script accordingly.

Notes & troubleshooting

- Building libwebrtc locally is non-trivial. See the official docs:
  https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/development/index.md

- The produced library layout and required linker flags may vary depending on GN args and platform. You may need to tweak `webrtc-native/build.gradle.kts` (linker args or rpath) to match the actual produced files.

- At runtime the Java process must be able to find the libwebrtc .so. The `client-desktop` module copies native libs into `client-desktop/build/native-libs`; ensure libwebrtc .so files are staged there or set `-Djava.library.path` when launching.

- CI: consider building libwebrtc in CI and publishing artifacts for developers to consume instead of forcing every developer to build from source.

Next steps to implement full integration

- Implement `PeerConnectionManager` using libwebrtc APIs:
  - Create PeerConnectionFactory, create PeerConnection with ICE servers, set up audio/video tracks, and implement onIceCandidate/onAddTrack callbacks.
  - Implement screen-capture (PipeWire recommended on modern Linux) and create a video source that feeds the PeerConnection.
  - Ensure thread-safety: libwebrtc expects certain threads (signaling, worker, network) — use rtc::Thread.

- I can also:
  - Add a small example implementation in `PeerConnectionManager` that calls into libwebrtc (requires headers available locally). The implementation will rely on libwebrtc being present (we removed the USE_WEBRTC compile guard), so ensure either the in-repo build or prebuilt headers are available.
  - Draft CI steps (GitHub Actions) to fetch/build libwebrtc and produce `.so` artifacts for Linux.

Tell me which next step you'd like me to take and I'll proceed with code changes and a verification run.
