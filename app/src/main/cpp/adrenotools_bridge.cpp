#include <jni.h>
#include <string>
#include <dlfcn.h>
#include <unistd.h>
#include <map>
#include <memory>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "GpuDriverBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
#include <adrenotools/driver.h>
}

#include <bytehook.h>

namespace {
void* g_vulkan_handle = nullptr;
bytehook_stub_t g_dlopen_stub = nullptr;
bytehook_stub_t g_android_dlopen_ext_stub = nullptr;

void* my_dlopen(const char* filename, int flags) {
    if (filename && strstr(filename, "vulkan.so") && g_vulkan_handle) {
        LOGI("Intercepted dlopen for %s, returning custom driver handle", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_dlopen, filename, flags);
}

void* my_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    if (filename && strstr(filename, "vulkan.so") && g_vulkan_handle) {
        LOGI("Intercepted android_dlopen_ext for %s", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_android_dlopen_ext, filename, flags, extinfo);
}
} // namespace

namespace {
std::string GetJString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string str(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
}
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_setDriver(
    JNIEnv* env,
    jobject /* this */,
    jstring hookLibDir,
    jstring customDriverDir,
    jstring customDriverName,
    jstring fileRedirectDir) {

    const char* nativeHookLibDir = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    const char* nativeDriverDir = customDriverDir ? env->GetStringUTFChars(customDriverDir, nullptr) : nullptr;
    const char* nativeDriverName = customDriverName ? env->GetStringUTFChars(customDriverName, nullptr) : nullptr;
    const char* nativeFileRedirectDir = fileRedirectDir ? env->GetStringUTFChars(fileRedirectDir, nullptr) : nullptr;

    void* handle = nullptr;
    int featureFlags = 0;

    // Enable driver file redirection when renderer debugging is enabled (or a directory is provided)
    if (nativeFileRedirectDir && strlen(nativeFileRedirectDir) > 0) {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    }

    // Try to load a custom driver
    if (nativeDriverName && strlen(nativeDriverName) > 0) {
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags | ADRENOTOOLS_DRIVER_CUSTOM, nullptr, nativeHookLibDir,
            nativeDriverDir, nativeDriverName, nativeFileRedirectDir, nullptr);
    }

    // Try to load the system driver
    if (!handle) {
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags, nullptr, nativeHookLibDir,
            nullptr, nullptr, nativeFileRedirectDir, nullptr);
    }

    if (handle) {
        g_vulkan_handle = handle;
        bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);

        if (!g_dlopen_stub) {
            g_dlopen_stub = bytehook_hook_single(
                "libmpv.so",
                nullptr,
                "dlopen",
                (void*)my_dlopen,
                nullptr,
                nullptr
            );
        }

        if (!g_android_dlopen_ext_stub) {
            g_android_dlopen_ext_stub = bytehook_hook_single(
                "libmpv.so",
                nullptr,
                "android_dlopen_ext",
                (void*)my_android_dlopen_ext,
                nullptr,
                nullptr
            );
        }
        LOGI("ByteHook initialized for libmpv.so");
    }

    if (nativeHookLibDir) env->ReleaseStringUTFChars(hookLibDir, nativeHookLibDir);
    if (nativeDriverDir) env->ReleaseStringUTFChars(customDriverDir, nativeDriverDir);
    if (nativeDriverName) env->ReleaseStringUTFChars(customDriverName, nativeDriverName);
    if (nativeFileRedirectDir) env->ReleaseStringUTFChars(fileRedirectDir, nativeFileRedirectDir);

    return handle != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_isAdrenoDevice(
    JNIEnv* env,
    jobject /* this */) {
    return access("/dev/kgsl-3d0", F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_getGpuInfo(
    JNIEnv* env,
    jobject /* this */) {
    if (access("/dev/kgsl-3d0", F_OK) == 0) {
        return env->NewStringUTF("Qualcomm Adreno GPU Detected");
    }
    return env->NewStringUTF("Generic GPU Driver Active");
}

} // extern "C"
