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

# YTDLP execution wrapper for SDK 29+ bypass
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")
mpvrx_optimize_native_target(ytdl_wrapper)
