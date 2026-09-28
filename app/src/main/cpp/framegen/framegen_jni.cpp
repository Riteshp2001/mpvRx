// SPDX-FileCopyrightText: Copyright 2026 mpvRx Project
// SPDX-License-Identifier: AGPL-3.0-or-later

#include <jni.h>
#include <string>

#include "lossless_dll.h"

using namespace VideoCore::FrameGen;

extern "C" {

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_initStorageRoot(
    JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path) {
        SetStorageRoot(path);
        env->ReleaseStringUTFChars(jpath, path);
    }
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_supportsFrameGeneration(
    JNIEnv*, jclass) {
    return static_cast<jboolean>(GetFrameGenerationSupport());
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_getLosslessDllPath(
    JNIEnv* env, jclass) {
    std::string path = GetLosslessDllPath().string();
    return env->NewStringUTF(path.c_str());
}

JNIEXPORT jint JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_validateLosslessDll(
    JNIEnv*, jclass) {
    return static_cast<jint>(GetInstalledLosslessStatus());
}

JNIEXPORT jint JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_prepareLosslessDll(
    JNIEnv*, jclass) {
    return static_cast<jint>(BuildShaderCache());
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_removeLosslessDll(
    JNIEnv*, jclass) {
    return static_cast<jboolean>(RemoveInstalledLosslessDll());
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_setFrameGenEnabled(
    JNIEnv*, jclass, jboolean enabled, jint multiplier) {
    SetFrameGenEnabled(enabled, multiplier);
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_getGpuModel(
    JNIEnv* env, jclass) {
    auto info = QueryGpuDeviceInfo();
    return env->NewStringUTF(info.deviceName.c_str());
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_getVulkanDriverVersion(
    JNIEnv* env, jclass) {
    auto info = QueryGpuDeviceInfo();
    return env->NewStringUTF(info.driverVersion.c_str());
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_getVulkanApiVersion(
    JNIEnv* env, jclass) {
    auto info = QueryGpuDeviceInfo();
    return env->NewStringUTF(info.apiVersion.c_str());
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_isGpuHardwareSupported(
    JNIEnv*, jclass) {
    auto info = QueryGpuDeviceInfo();
    return static_cast<jboolean>(info.isSupported);
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_framegen_FrameGenNative_setGpuDeviceInfo(
    JNIEnv* env, jclass, jstring name, jstring api, jstring driver, jboolean supported) {
    const char* c_name = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    const char* c_api = api ? env->GetStringUTFChars(api, nullptr) : nullptr;
    const char* c_driver = driver ? env->GetStringUTFChars(driver, nullptr) : nullptr;
    SetGpuDeviceInfo(c_name ? c_name : "", c_api ? c_api : "", c_driver ? c_driver : "", supported == JNI_TRUE);
    if (c_name) env->ReleaseStringUTFChars(name, c_name);
    if (c_api) env->ReleaseStringUTFChars(api, c_api);
    if (c_driver) env->ReleaseStringUTFChars(driver, c_driver);
}

}

