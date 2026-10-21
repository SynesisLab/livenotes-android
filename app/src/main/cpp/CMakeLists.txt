# On-device whisper: links the vendored whisper.cpp (git submodule at
# app/src/main/cpp/whisper — `git submodule add
# https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper`).
cmake_minimum_required(VERSION 3.22.1)
project(whisper-jni)

# Nothing but the library itself: no tests/examples/OpenMP on the NDK —
# examples expect SDL2 and OpenMP breaks Android toolchain configurations.
#
# PERFORMANCE-CRITICAL: with GGML_NATIVE on (the default), ggml tries to
# autodetect the CPU of the machine RUNNING THE BUILD and cross-compile
# against it — under the NDK that silently degrades to a scalar build.
# Observed on a device: one 6 s utterance took 98 s of inference. With
# GGML_NATIVE off, the aarch64 kernels compile with their NEON path.
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)

add_subdirectory(whisper)

add_library(whisper-jni SHARED whisper-jni.cpp)
target_link_libraries(whisper-jni PRIVATE whisper log)