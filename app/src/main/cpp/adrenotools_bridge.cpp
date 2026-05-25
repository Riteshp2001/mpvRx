#include <jni.h>
#include <string>
#include <dlfcn.h>
#include <unistd.h>
#include <map>
#include <memory>
#include <sys/stat.h>
#include <android/log.h>
#include <sys/prctl.h>

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
bytehook_stub_t g_dlsym_stub = nullptr;

bytehook_stub_t g_vkGetInstanceProcAddr_stub = nullptr;
bytehook_stub_t g_vkCreateInstance_stub = nullptr;
bytehook_stub_t g_vkEnumerateInstanceExtensionProperties_stub = nullptr;
bytehook_stub_t g_vkEnumerateInstanceVersion_stub = nullptr;

bool my_caller_allow_filter(const char *caller_path_name, void *arg) {
    if (caller_path_name) {
        // Log all callers to help debug
        // LOGI("Filter check caller: %s", caller_path_name);

        if (strstr(caller_path_name, "libmpv.so") ||
            strstr(caller_path_name, "libplayer.so") ||
            strstr(caller_path_name, "libplacebo.so") ||
            strstr(caller_path_name, "libavcodec.so") ||
            strstr(caller_path_name, "libavformat.so") ||
            strstr(caller_path_name, "libavutil.so") ||
            strstr(caller_path_name, "libavfilter.so") ||
            strstr(caller_path_name, "libavdevice.so") ||
            strstr(caller_path_name, "libswscale.so") ||
            strstr(caller_path_name, "libswresample.so")) {
            return true;
        }
    } else {
        // If caller is unknown, allow it for now to be safe (might be from libmpv via some wrapper)
        return true;
    }
    return false;
}

bool is_libvulkan(const char* filename) {
    if (!filename) return false;
    if (strcmp(filename, "libvulkan.so") == 0 || strcmp(filename, "libvulkan.so.1") == 0) return true;

    size_t len = strlen(filename);
    if (len >= 13) {
        if (strcmp(filename + len - 13, "/libvulkan.so") == 0) return true;
    }
    return false;
}

typedef void* (*PFN_vkGetInstanceProcAddr)(void*, const char*);
typedef int (*PFN_vkCreateInstance)(const void*, const void*, void**);
typedef int (*PFN_vkEnumerateInstanceExtensionProperties)(const char*, uint32_t*, void*);
typedef int (*PFN_vkEnumerateInstanceVersion)(uint32_t*);

void* my_vkGetInstanceProcAddr(void* instance, const char* name);
int my_vkCreateInstance(const void* pCreateInfo, const void* pAllocator, void** pInstance);
int my_vkEnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pPropertyCount, void* pProperties);
int my_vkEnumerateInstanceVersion(uint32_t* pApiVersion);

void* my_dlopen(const char* filename, int flags) {
    if (filename && g_vulkan_handle && is_libvulkan(filename)) {
        LOGI("Intercepted dlopen for %s -> redirecting to custom driver", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_dlopen, filename, flags);
}

void* my_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    if (filename && g_vulkan_handle && is_libvulkan(filename)) {
        LOGI("Intercepted android_dlopen_ext for %s -> redirecting to custom driver", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_android_dlopen_ext, filename, flags, extinfo);
}

void* my_dlsym(void* handle, const char* symbol) {
    if (g_vulkan_handle && symbol) {
        if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) return (void*)my_vkGetInstanceProcAddr;
        if (strcmp(symbol, "vkCreateInstance") == 0) return (void*)my_vkCreateInstance;
        if (strcmp(symbol, "vkEnumerateInstanceExtensionProperties") == 0) return (void*)my_vkEnumerateInstanceExtensionProperties;
        if (strcmp(symbol, "vkEnumerateInstanceVersion") == 0) return (void*)my_vkEnumerateInstanceVersion;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_dlsym, handle, symbol);
}

void* my_vkGetInstanceProcAddr(void* instance, const char* name) {
    if (g_vulkan_handle) {
        auto func = (PFN_vkGetInstanceProcAddr) dlsym(g_vulkan_handle, "vkGetInstanceProcAddr");
        if (func) return func(instance, name);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkGetInstanceProcAddr, instance, name);
}

int my_vkCreateInstance(const void* pCreateInfo, const void* pAllocator, void** pInstance) {
    if (g_vulkan_handle) {
        LOGI("Intercepted vkCreateInstance");
        auto func = (PFN_vkCreateInstance) dlsym(g_vulkan_handle, "vkCreateInstance");
        if (func) return func(pCreateInfo, pAllocator, pInstance);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkCreateInstance, pCreateInfo, pAllocator, pInstance);
}

int my_vkEnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pPropertyCount, void* pProperties) {
    if (g_vulkan_handle) {
        auto func = (PFN_vkEnumerateInstanceExtensionProperties) dlsym(g_vulkan_handle, "vkEnumerateInstanceExtensionProperties");
        if (func) return func(pLayerName, pPropertyCount, pProperties);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkEnumerateInstanceExtensionProperties, pLayerName, pPropertyCount, pProperties);
}

int my_vkEnumerateInstanceVersion(uint32_t* pApiVersion) {
    if (g_vulkan_handle) {
        auto func = (PFN_vkEnumerateInstanceVersion) dlsym(g_vulkan_handle, "vkEnumerateInstanceVersion");
        if (func) return func(pApiVersion);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkEnumerateInstanceVersion, pApiVersion);
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
    jstring fileRedirectDir,
    jstring tmpDir) {

    const char* nativeHookLibDir = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    const char* nativeDriverDirRaw = customDriverDir ? env->GetStringUTFChars(customDriverDir, nullptr) : nullptr;
    const char* nativeDriverName = customDriverName ? env->GetStringUTFChars(customDriverName, nullptr) : nullptr;
    const char* nativeFileRedirectDir = fileRedirectDir ? env->GetStringUTFChars(fileRedirectDir, nullptr) : nullptr;
    const char* nativeTmpDir = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;

    std::string driverDirStr = nativeDriverDirRaw ? nativeDriverDirRaw : "";
    if (!driverDirStr.empty() && driverDirStr.back() != '/') {
        driverDirStr += '/';
    }
    const char* nativeDriverDir = driverDirStr.empty() ? nullptr : driverDirStr.c_str();

    LOGI("setDriver: hookLibDir=%s, customDriverDir=%s, customDriverName=%s, tmpDir=%s",
         nativeHookLibDir ? nativeHookLibDir : "null",
         nativeDriverDir ? nativeDriverDir : "null",
         nativeDriverName ? nativeDriverName : "null",
         nativeTmpDir ? nativeTmpDir : "null");

    void* handle = nullptr;
    int featureFlags = 0;

    if (nativeFileRedirectDir && strlen(nativeFileRedirectDir) > 0) {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    }

    if (nativeDriverName && strlen(nativeDriverName) > 0) {
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags | ADRENOTOOLS_DRIVER_CUSTOM, nativeTmpDir, nativeHookLibDir,
            nativeDriverDir, nativeDriverName, nativeFileRedirectDir, nullptr);
        if (handle) {
            LOGI("Successfully loaded custom driver: %s", nativeDriverName);
        } else {
            LOGE("Failed to load custom driver: %s", nativeDriverName);
        }
    }

    if (!handle) {
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags, nativeTmpDir, nativeHookLibDir,
            nullptr, nullptr, nativeFileRedirectDir, nullptr);
        if (handle) {
            LOGI("Successfully initialized system driver with adrenotools hooks");
        }
    }

    if (handle) {
        g_vulkan_handle = handle;
        bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);

        // Register hooks with caller filtering
        if (!g_dlopen_stub) g_dlopen_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "dlopen", (void*)my_dlopen, nullptr, nullptr);
        if (!g_android_dlopen_ext_stub) g_android_dlopen_ext_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "android_dlopen_ext", (void*)my_android_dlopen_ext, nullptr, nullptr);
        if (!g_dlsym_stub) g_dlsym_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "dlsym", (void*)my_dlsym, nullptr, nullptr);
        if (!g_vkGetInstanceProcAddr_stub) g_vkGetInstanceProcAddr_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "vkGetInstanceProcAddr", (void*)my_vkGetInstanceProcAddr, nullptr, nullptr);
        if (!g_vkCreateInstance_stub) g_vkCreateInstance_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "vkCreateInstance", (void*)my_vkCreateInstance, nullptr, nullptr);
        if (!g_vkEnumerateInstanceExtensionProperties_stub) g_vkEnumerateInstanceExtensionProperties_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "vkEnumerateInstanceExtensionProperties", (void*)my_vkEnumerateInstanceExtensionProperties, nullptr, nullptr);
        if (!g_vkEnumerateInstanceVersion_stub) g_vkEnumerateInstanceVersion_stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, "vkEnumerateInstanceVersion", (void*)my_vkEnumerateInstanceVersion, nullptr, nullptr);

        LOGI("ByteHook initialized for libmpv redirection");
    }

    if (nativeHookLibDir) env->ReleaseStringUTFChars(hookLibDir, nativeHookLibDir);
    if (nativeDriverDirRaw) env->ReleaseStringUTFChars(customDriverDir, nativeDriverDirRaw);
    if (nativeDriverName) env->ReleaseStringUTFChars(customDriverName, nativeDriverName);
    if (nativeFileRedirectDir) env->ReleaseStringUTFChars(fileRedirectDir, nativeFileRedirectDir);
    if (nativeTmpDir) env->ReleaseStringUTFChars(tmpDir, nativeTmpDir);

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
