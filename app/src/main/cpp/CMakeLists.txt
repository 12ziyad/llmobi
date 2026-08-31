cmake_minimum_required(VERSION 3.22.1)
project(llmobi CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build only what we need from llama.cpp. Skipping tools/tests/examples cuts the
# build from minutes to seconds and keeps the APK small.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# OpenMP is unreliable across NDK versions and buys little at this thread count.
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON  CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
    EXCLUDE_FROM_ALL
)

add_library(llmobi SHARED llama-jni.cpp)

target_link_libraries(llmobi
    PRIVATE
        llama
        ggml
        android
        log
)
