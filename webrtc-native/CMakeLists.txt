cmake_minimum_required(VERSION 3.16)
project(webrtc_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Export compile_commands.json for clangd / IDEs
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Allow overriding via -DWEBRTC_INCLUDE_DIR and -DWEBRTC_LIB_DIR or via environment
if(NOT DEFINED WEBRTC_INCLUDE_DIR)
  if(DEFINED ENV{WEBRTC_INCLUDE_DIR})
    set(WEBRTC_INCLUDE_DIR $ENV{WEBRTC_INCLUDE_DIR})
  endif()
endif()
if(NOT DEFINED WEBRTC_LIB_DIR)
  if(DEFINED ENV{WEBRTC_LIB_DIR})
    set(WEBRTC_LIB_DIR $ENV{WEBRTC_LIB_DIR})
  endif()
endif()

# Detect in-repo webrtc-src GN output (common default out dir)
set(IN_REPO_WEBRTC_DIR "${CMAKE_SOURCE_DIR}/webrtc-src")
set(GN_OUT_DIR "${IN_REPO_WEBRTC_DIR}/out/Default")
if(EXISTS "${GN_OUT_DIR}/include")
  message(STATUS "Found in-repo libwebrtc GN output: ${GN_OUT_DIR}")
  list(APPEND EXTRA_INCLUDE_DIRS "${GN_OUT_DIR}/include")
  list(APPEND EXTRA_LIB_DIRS "${GN_OUT_DIR}")
endif()

if(DEFINED WEBRTC_INCLUDE_DIR)
  message(STATUS "Using WEBRTC_INCLUDE_DIR=${WEBRTC_INCLUDE_DIR}")
  list(APPEND EXTRA_INCLUDE_DIRS "${WEBRTC_INCLUDE_DIR}")
else()
  # default to source include dir (may exist if submodule placed here)
  list(APPEND EXTRA_INCLUDE_DIRS "${CMAKE_SOURCE_DIR}/webrtc-src/include")
endif()
if(DEFINED WEBRTC_LIB_DIR)
  message(STATUS "Using WEBRTC_LIB_DIR=${WEBRTC_LIB_DIR}")
  list(APPEND EXTRA_LIB_DIRS "${WEBRTC_LIB_DIR}")
else()
  list(APPEND EXTRA_LIB_DIRS "${CMAKE_SOURCE_DIR}/webrtc-src/out/Default")
endif()

# Find JNI once
find_package(JNI REQUIRED)
if(JNI_FOUND)
  message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")
else()
  message(FATAL_ERROR "JNI not found. Set JAVA_HOME or install JDK.")
endif()

# Gather source files (search common locations)
file(GLOB_RECURSE SRC_FILES
     "${CMAKE_SOURCE_DIR}/src/**/*.cpp"
     "${CMAKE_SOURCE_DIR}/src/**/*.c"
     "${CMAKE_SOURCE_DIR}/src/*.cpp"
     "${CMAKE_SOURCE_DIR}/src/*.c"
     "${CMAKE_SOURCE_DIR}/src/main/cpp/*.cpp"
     "${CMAKE_SOURCE_DIR}/src/main/cpp/*.c"
)
if(NOT SRC_FILES)
  message(FATAL_ERROR "No native sources found under ${CMAKE_SOURCE_DIR}/src or src/main/cpp")
endif()

# If a target with the same name was already created by another build system (Gradle native, etc.),
# avoid re-creating it to prevent CMP0002 errors. Skip add_library if target exists.
if(NOT TARGET webrtc_native)
  add_library(webrtc_native SHARED ${SRC_FILES})
else()
  message(STATUS "Target 'webrtc_native' already exists in this CMake context; skipping add_library call.")
endif()

if(TARGET webrtc_native)
  target_include_directories(webrtc_native PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}/linux
    ${CMAKE_SOURCE_DIR}/src/main/cpp
  )

  if(EXTRA_INCLUDE_DIRS)
    target_include_directories(webrtc_native PRIVATE ${EXTRA_INCLUDE_DIRS})
  endif()

  if(EXTRA_LIB_DIRS)
    foreach(_dir IN LISTS EXTRA_LIB_DIRS)
      if(EXISTS ${_dir})
        link_directories(${_dir})
      endif()
    endforeach()

    # Try to find libwebrtc in the provided lib dirs
    find_library(WEBDRTC_LIB NAMES webrtc PATHS ${EXTRA_LIB_DIRS} NO_DEFAULT_PATH)
    if(WEBDRTC_LIB)
      message(STATUS "Linking against libwebrtc: ${WEBDRTC_LIB}")
      target_link_libraries(webrtc_native PRIVATE ${WEBDRTC_LIB})
      # set RPATH so runtime finds the in-repo/shared libs without LD_LIBRARY_PATH
      set_target_properties(webrtc_native PROPERTIES INSTALL_RPATH "${EXTRA_LIB_DIRS}")
    else()
      message(STATUS "libwebrtc not found in EXTRA_LIB_DIRS; you may need to set WEBRTC_LIB_DIR or build in-repo")
    endif()
    endif()

  # Compiler flags: enable warnings and position independent code
  target_compile_options(webrtc_native PRIVATE -Wall -Wextra -Wno-unused-parameter)

  install(TARGETS webrtc_native
          LIBRARY DESTINATION lib)
endif()

message(STATUS "CMake configuration finished. Build with: cmake -S . -B build && cmake --build build -- -j$(nproc)")
