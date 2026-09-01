cmake_minimum_required(VERSION 3.22.1)
project(llmobi C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Never build ggml unoptimised, not even in a debug APK.
#
# AGP sets CMAKE_BUILD_TYPE=Debug for debug builds, and CMake appends
# CMAKE_CXX_FLAGS_DEBUG (-O0 -g) after any flags we pass - so -O0 wins and the
# matrix kernels run roughly fifty times slower. Measured on a Dimensity 6100+:
# 870 ms per prompt token at -O0 against about 20 ms at -O3. Debug symbols are
# still kept, so native crashes remain readable.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELWITHDEBINFO   "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)

# Belt and braces. The cache variables above are the tidy fix, but they only
# survive if C is enabled in project() first - enabling a language re-runs the
# toolchain's flag initialisation and silently resets them. This directory-level
# option is applied to every target defined below, ggml included, and nothing
# downstream can undo it.
# Debug defaults to -O0 and RelWithDebInfo (what AGP uses for release) to -O2.
# ggml is measurably faster at -O3, and it is the only code here hot enough to
# care, so pin both.
add_compile_options($<$<CONFIG:Debug>:-O3> $<$<CONFIG:RelWithDebInfo>:-O3>)

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

# Say it explicitly as well as relying on the NDK default, so a future toolchain
# bump cannot silently reintroduce a library that will not load on a 16 KB device.
target_link_options(llmobi PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_link_libraries(llmobi
    PRIVATE
        llama
        ggml
        android
        log
)
