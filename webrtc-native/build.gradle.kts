plugins {
    id("cpp-library")
}

// Allow opting out of Gradle native builds in favor of separately-built prebuilt .so
val usePrebuiltNativeProp = if (project.hasProperty("usePrebuiltNative")) project.property("usePrebuiltNative") as String else null
val usePrebuiltNativeEnv = System.getenv("USE_PREBUILT_NATIVE")
val usePrebuiltNative = (usePrebuiltNativeProp != null && usePrebuiltNativeProp.toBoolean()) || (usePrebuiltNativeEnv == "1" || usePrebuiltNativeEnv == "true")

group = "com.voicechat"
version = "1.0.0-SNAPSHOT"

// Minimal cpp-library configuration — using Gradle's default native layout
library {
    baseName.set("webrtc_native")
    // Let Gradle pick the host machine and default linkages; no custom sources block used so defaults apply
}

// Add JNI include directories so jni.h can be found when we compile JNI bindings.
// Prefer JAVA_HOME environment variable; fallback to the running JVM home.
val javaHomeDir = System.getenv("JAVA_HOME")?.let { file(it) } ?: file(System.getProperty("java.home"))
val jniInclude = file(javaHomeDir.resolve("include"))
val jniPlatformInclude = file(javaHomeDir.resolve("include").resolve("linux"))

library.binaries.configureEach {
    // compileTask is present for each native compilation binary
    compileTask.get().includes.from(jniInclude, jniPlatformInclude)
}

// Optional: allow developers/CI to point to a prebuilt libwebrtc installation (headers + libs).
// Set either environment variables or Gradle project properties:
//   - WEBRTC_INCLUDE_DIR or -PwebrtcIncludeDir=/path/to/webrtc/include
//   - WEBRTC_LIB_DIR     or -PwebrtcLibDir=/path/to/webrtc/libs
val webrtcIncludeDirEnv = System.getenv("WEBRTC_INCLUDE_DIR")
val webrtcLibDirEnv = System.getenv("WEBRTC_LIB_DIR")
val webrtcIncludeDirProp = if (project.hasProperty("webrtcIncludeDir")) project.property("webrtcIncludeDir") as String else null
val webrtcLibDirProp = if (project.hasProperty("webrtcLibDir")) project.property("webrtcLibDir") as String else null

val webrtcIncludeDir = webrtcIncludeDirEnv ?: webrtcIncludeDirProp
val webrtcLibDir = webrtcLibDirEnv ?: webrtcLibDirProp

// Auto-detect prebuilt .so locations so developers don't need to pass properties.
val autoDetectedWebrtcLibDir: File? = run {
    val candidates = listOf(
        file("${projectDir}/build/lib"),
        file("${projectDir}/build"),
        file("${projectDir}/out/Default"),
        file("${projectDir}/../webrtc-native/build/lib"),
        file("${projectDir}/../webrtc-native/build")
    )
    candidates.firstOrNull { dir ->
        dir.exists() && dir.listFiles()?.any { it.name.endsWith(".so") && (it.name.contains("webrtc") || it.name.contains("webrtc_native")) } == true
    }?.absoluteFile
}

// Resolve final lib dir File if provided as env/prop or auto-detected. Accept a path to a .so as well.
val resolvedWebrtcLibDirFile: File? = when {
    webrtcLibDir != null -> {
        val f = file(webrtcLibDir)
        if (f.isFile) f.parentFile.absoluteFile else f.absoluteFile
    }
    autoDetectedWebrtcLibDir != null -> autoDetectedWebrtcLibDir
    else -> null
}

// Decide which lib dir to use for copy/link actions: prefer resolved path (env/prop or auto-detected)
val libDirToUse: String? = resolvedWebrtcLibDirFile?.path ?: webrtcLibDir

// If we have either an explicit opt-in or an auto-detected prebuilt .so, enable prebuilt mode
val effectiveUsePrebuiltNative = usePrebuiltNative || (resolvedWebrtcLibDirFile != null)
if (effectiveUsePrebuiltNative) {
    logger.lifecycle("Using prebuilt native library mode: Gradle will not compile C++ sources. Using lib dir: ${libDirToUse}")
    // disable tasks whose names indicate native compilation/linking so Gradle doesn't try to build C++ code
    tasks.matching { t ->
        val n = t.name.lowercase()
        n.contains("compile") || n.contains("link") || n.contains("shared")
    }.configureEach {
        enabled = false
    }
}

if (webrtcIncludeDir != null) {
    library.binaries.configureEach {
        compileTask.get().includes.from(file(webrtcIncludeDir))
    }
}

val libDir = libDirToUse
if (libDir != null) {
    // Add the link flags to all native link tasks in this project. We avoid calling binary.linkTask
    // directly to keep the Kotlin DSL script compilation stable across Gradle versions.
    tasks.withType(org.gradle.nativeplatform.tasks.LinkSharedLibrary::class.java).configureEach {
        // Add library search path and default linkage. Projects can provide additional link args via
        // property `webrtcLinkArgs` (space-separated) or environment variable `WEBRTC_LINK_ARGS`.
        linkerArgs.addAll(listOf("-L${libDir}", "-lwebrtc"))
        // Add rpath so the runtime linker can find the prebuilt libs at runtime without LD_LIBRARY_PATH.
        linkerArgs.add("-Wl,-rpath,${libDir}")
        val extraLinkArgsEnv = System.getenv("WEBRTC_LINK_ARGS")
        val extraLinkArgsProp = if (project.hasProperty("webrtcLinkArgs")) project.property("webrtcLinkArgs") as String else null
        val extraLinkArgs = (extraLinkArgsEnv ?: extraLinkArgsProp)?.split("\\s+")?.filter { it.isNotBlank() } ?: listOf()
        if (extraLinkArgs.isNotEmpty()) {
            linkerArgs.addAll(extraLinkArgs)
        }
    }

    // If the developer provided a directory with prebuilt libwebrtc .so files, copy them into this
    // module's output directory so downstream consumers (client-desktop) will pick them up via the
    // existing copyNativeLibs task which reads from build/lib/main/debug.
    tasks.register("copyPrebuiltWebrtcLibs", Copy::class.java) {
        from(file(libDir)) {
            include("**/*.so")
        }
        into(layout.buildDirectory.dir("lib/main/debug"))
    }

    // Ensure prebuilt libs are staged as part of assemble
    tasks.named("assemble").configure {
        dependsOn("copyPrebuiltWebrtcLibs")
    }
}

// If the repo contains a vendored libwebrtc source tree at webrtc-src/, provide a
// lightweight Gradle wrapper to build it with GN/Ninja and wire the produced headers/libs
// into the native compilation. This is a convenience for Linux-first development; building
// libwebrtc locally requires depot_tools/gn/ninja and a substantial toolchain.
val inRepoWebrtcDir = file("${projectDir}/webrtc-src")
if (inRepoWebrtcDir.exists()) {
    val outDir = file(inRepoWebrtcDir.resolve("out/Default"))
    // Task that runs GN/Ninja to build libwebrtc; developers may customize this script.
    tasks.register("buildWebrtcInRepo", Exec::class.java) {
        workingDir = inRepoWebrtcDir
        // Use bash -lc to allow multi-step commands and to respect user's shell PATH for depot_tools
        commandLine("bash", "-lc", "gn gen out/Default && ninja -C out/Default")
    }

    // Add includes and link args that point at the in-repo build output.

    library.binaries.configureEach {
        // Prefer the GN out include path which holds generated headers.
        compileTask.get().includes.from(outDir.resolve("include"))
    }

    tasks.withType(org.gradle.nativeplatform.tasks.LinkSharedLibrary::class.java).configureEach {
        // Link against produced shared/static libs in the GN out directory. The exact lib name
        // and location may vary by GN args; this is a sensible default that can be adjusted.
        linkerArgs.addAll(listOf("-L${outDir}", "-lwebrtc"))
        linkerArgs.add("-Wl,-rpath,${outDir}")
    }

    // By default the in-repo GN/Ninja build is registered but NOT run automatically.
    // This prevents Gradle-based workflows from invoking GN/Ninja unless the developer
    // explicitly requests it. To opt-in, set the Gradle property `-PwebrtcBuildInRepo=true`
    // or environment variable `BUILD_WEBRTC_INREPO=1` when invoking Gradle.
    val webrtcBuildInRepoProp = if (project.hasProperty("webrtcBuildInRepo")) project.property("webrtcBuildInRepo") as String else null
    val webrtcBuildInRepoEnv = System.getenv("BUILD_WEBRTC_INREPO")
    if ((webrtcBuildInRepoProp != null && webrtcBuildInRepoProp.toBoolean()) || webrtcBuildInRepoEnv == "1") {
        tasks.named("assemble").configure {
            dependsOn("buildWebrtcInRepo")
        }
    } else {
        logger.lifecycle("Note: in-repo libwebrtc build registered (buildWebrtcInRepo), but not enabled. Set -PwebrtcBuildInRepo=true or BUILD_WEBRTC_INREPO=1 to enable GN/Ninja build.")
    }
}

tasks.register("packageNative") {
    doLast {
        println("If toolchain is available, a shared library will be produced under build/ (see Gradle output)")
    }
}
