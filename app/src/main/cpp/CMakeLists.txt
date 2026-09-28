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

set(MPVRX_NATIVE_LINK_OPTIONS
    "-Wl,-z,max-page-size=16384"
    "$<$<CONFIG:Release>:-Wl,-O1>"
    "$<$<CONFIG:Release>:-Wl,--gc-sections>"
    "$<$<CONFIG:Release>:-Wl,--icf=safe>"
)

function(mpvrx_optimize_native_target target)
    target_compile_options(${target} PRIVATE ${MPVRX_NATIVE_RELEASE_COMPILE_OPTIONS})
    target_link_options(${target} PRIVATE ${MPVRX_NATIVE_LINK_OPTIONS})
endfunction()

add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")
mpvrx_optimize_native_target(ytdl_wrapper)
