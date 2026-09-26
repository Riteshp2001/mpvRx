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

# YTDLP execution wrapper for SDK 29+ bypass
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")
mpvrx_optimize_native_target(ytdl_wrapper)

# QuickJS-NG CLI used by current yt-dlp releases for YouTube n/sig challenges.
# Package it as an executable .so beside libytdl.so so Android permits subprocess execution.
set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs")
add_executable(
    qjs_runtime
    "${QUICKJS_DIR}/dtoa.c"
    "${QUICKJS_DIR}/gen/repl.c"
    "${QUICKJS_DIR}/gen/standalone.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${QUICKJS_DIR}/qjs.c"
    "${QUICKJS_DIR}/quickjs-libc.c"
    "${QUICKJS_DIR}/quickjs.c"
)
set_target_properties(
    qjs_runtime
    PROPERTIES
    OUTPUT_NAME "qjs"
    PREFIX "lib"
    SUFFIX ".so"
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
    ENABLE_EXPORTS TRUE
)
target_include_directories(qjs_runtime PRIVATE "${QUICKJS_DIR}")
target_compile_definitions(qjs_runtime PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
target_compile_options(qjs_runtime PRIVATE -funsigned-char)
target_link_libraries(qjs_runtime dl m)
mpvrx_optimize_native_target(qjs_runtime)

# Frame Generation (LSFG) shader cache and library manager
set(FRAMEGEN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/framegen")
add_library(
    framegen
    SHARED
    "${FRAMEGEN_DIR}/cityhash.cpp"
    "${FRAMEGEN_DIR}/lsfg_translate.cpp"
    "${FRAMEGEN_DIR}/lossless_dll.cpp"
    "${FRAMEGEN_DIR}/framegen_jni.cpp"
)
set_target_properties(
    framegen
    PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)
target_include_directories(framegen PRIVATE "${FRAMEGEN_DIR}")
target_link_libraries(framegen dl)
mpvrx_optimize_native_target(framegen)

