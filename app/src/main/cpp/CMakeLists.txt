cmake_minimum_required(VERSION 3.22.1)

project("mpvrx_native" C CXX)

# Release-only native optimization shared by MpvRx-owned JNI/helper targets.
# Keep this scoped to our small native targets; the prebuilt mpvlib AARs are
# optimized separately when their mpv/FFmpeg toolchain is built.
set(MPVRX_NATIVE_RELEASE_COMPILE_OPTIONS
    "$<$<CONFIG:Release>:-ffunction-sections>"
    "$<$<CONFIG:Release>:-fdata-sections>"
    "$<$<CONFIG:Release>:-fvisibility=hidden>"
)

set(MPVRX_NATIVE_RELEASE_LINK_OPTIONS
    "$<$<CONFIG:Release>:-Wl,-O1>"
    "$<$<CONFIG:Release>:-Wl,--gc-sections>"
    "$<$<CONFIG:Release>:-Wl,--icf=safe>"
    "$<$<CONFIG:Release>:-Wl,-z,max-page-size=16384>"
)

function(mpvrx_optimize_native_target target)
    target_compile_options(${target} PRIVATE ${MPVRX_NATIVE_RELEASE_COMPILE_OPTIONS})
    target_link_options(${target} PRIVATE ${MPVRX_NATIVE_RELEASE_LINK_OPTIONS})
endfunction()

# Android 16 compatibility for libmpv's raw clone-based subprocess path.
add_library(android_compat SHARED android_compat.c)
target_link_libraries(android_compat log dl)
mpvrx_optimize_native_target(android_compat)

# Isolated persistent libmpv core for interactive seek previews. It resolves libmpv dynamically
# from the bundled mpv AAR so every distribution flavor uses its matching native core.
add_library(thumbfast_preview SHARED thumbfast_preview.cpp)
target_link_libraries(thumbfast_preview log dl)
mpvrx_optimize_native_target(thumbfast_preview)

# YTDLP execution wrapper for SDK 29+ bypass
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")
mpvrx_optimize_native_target(ytdl_wrapper)

# libcurl JNI bridge for Lua/JS scripting HTTP support
# BoringSSL is C++; use C++ linker so libc++ is linked at the correct position
add_library(curl_bridge SHARED curl_bridge.c cJSON.c)
set_target_properties(curl_bridge PROPERTIES LINKER_LANGUAGE CXX)
find_package(curl REQUIRED CONFIG)
target_link_libraries(curl_bridge curl::curl_static log z)
target_include_directories(curl_bridge PRIVATE ${CURL_INCLUDE_DIRS})
# Prevent symbols from statically linked curl/BoringSSL dependencies from being
# re-exported by the JNI bridge. JNIEXPORT entry points retain default visibility.
target_link_options(curl_bridge PRIVATE "$<$<CONFIG:Release>:-Wl,--exclude-libs,ALL>")
mpvrx_optimize_native_target(curl_bridge)
