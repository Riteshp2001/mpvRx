cmake_minimum_required(VERSION 3.22.1)

project("mpvrx_native" C CXX)

# Android 16 compatibility for libmpv's raw clone-based subprocess path.
add_library(android_compat SHARED android_compat.c)
target_link_libraries(android_compat log dl)

# YTDLP execution wrapper for SDK 29+ bypass
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")

# libcurl JNI bridge for Lua/JS scripting HTTP support
# BoringSSL is C++; use C++ linker so libc++ is linked at the correct position
add_library(curl_bridge SHARED curl_bridge.c cJSON.c)
set_target_properties(curl_bridge PROPERTIES LINKER_LANGUAGE CXX)
find_package(curl REQUIRED CONFIG)
target_link_libraries(curl_bridge curl::curl_static log z)
target_include_directories(curl_bridge PRIVATE ${CURL_INCLUDE_DIRS})
