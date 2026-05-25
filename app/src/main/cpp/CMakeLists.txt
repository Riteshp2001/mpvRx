cmake_minimum_required(VERSION 3.22.1)

project("adrenotools_bridge")

# YTDLP execution wrapper for SDK 29+ bypass
# This works on all architectures
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
# Rename to libytdl.so so it gets extracted by Android
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")

# Adrenotools is only for arm64-v8a
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    include(FetchContent)

    FetchContent_Declare(
        adrenotools
        GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
        GIT_TAG        master
    )

    FetchContent_MakeAvailable(adrenotools)

    # Suppress warnings from adrenotools internal targets
    set_property(TARGET adrenotools PROPERTY COMPILE_OPTIONS "-w")
    set_property(TARGET hook_impl PROPERTY COMPILE_OPTIONS "-w")
    set_property(TARGET main_hook PROPERTY COMPILE_OPTIONS "-w")
    set_property(TARGET file_redirect_hook PROPERTY COMPILE_OPTIONS "-w")
    set_property(TARGET gsl_alloc_hook PROPERTY COMPILE_OPTIONS "-w")

    add_library(${CMAKE_PROJECT_NAME} SHARED
        adrenotools_bridge.cpp
    )

    target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${adrenotools_SOURCE_DIR}/include
    )

    find_package(bytehook REQUIRED CONFIG)

    target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        EGL
        adrenotools
        bytehook::bytehook
    )
else()
    message(STATUS "Skipping adrenotools_bridge for non-arm64 architecture: ${CMAKE_ANDROID_ARCH_ABI}")
endif()
