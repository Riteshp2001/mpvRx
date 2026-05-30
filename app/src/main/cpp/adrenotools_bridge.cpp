#include <jni.h>
#include <string>
#include <dlfcn.h>
#include <unistd.h>
#include <map>
#include <memory>
#include <atomic>
#include <mutex>
#include <fstream>
#include <sstream>
#include <cstring>
#include <cstdint>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <android/log.h>

#define LOG_TAG "GpuDriverBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
#include <adrenotools/driver.h>
}

#include <bytehook.h>

// =============================================================================
// FreedrenoConfig — preserved from the Eden-style integration. Holds the
// in-memory env-var map plus the on-disk .freedreno.conf used to persist HUD
// and TU_DEBUG flags across launches. Surface exposed to Kotlin via
// NativeFreedrenoConfig.
// =============================================================================
namespace {
struct FreedrenoConfig {
    std::map<std::string, std::string> env_vars;
    std::string base_path;
};

std::unique_ptr<FreedrenoConfig> g_config;

std::string GetConfigPath() {
    if (g_config && !g_config->base_path.empty()) {
        return g_config->base_path + "/.freedreno.conf";
    }
    return "";
}

bool ApplyEnvironmentVariable(const std::string& key, const std::string& value) {
    // bylaws/libadrenotools does not expose adrenotools_set_freedreno_env;
    // use plain setenv directly. Adrenotools reads these via getenv() at
    // driver init time, which is exactly what setenv populates.
    if (setenv(key.c_str(), value.c_str(), 1) != 0) {
        LOGE("[Freedreno] setenv %s=%s failed", key.c_str(), value.c_str());
        return false;
    }
    return true;
}

std::string GetJString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string str(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
}
} // namespace

// =============================================================================
// ByteHook scaffolding — intercept libmpv/libplacebo/libav* dlopen and
// vk* entry points so the system Vulkan loader returns OUR pre-loaded custom
// driver handle instead of the OEM libvulkan.so. Only meaningful when
// setDriver() has successfully opened a custom driver (g_vulkan_handle != null).
// =============================================================================
namespace {
// All driver-state mutations go through this mutex. setDriver, unloadDriver,
// and isDriverActive can race with each other from different threads (App
// startup, MPV worker, settings UI), and a half-installed hook table is much
// worse than serialising the rare reconfigure path.
std::mutex g_driver_mutex;
void* g_vulkan_handle = nullptr;

// Set by my_vkCreateInstance the first time it routes a call through our
// custom handle. UI uses this (via wasHookUsed()) to distinguish "bridge
// armed and hook was actually exercised" from "bridge armed but the Android
// Vulkan loader bypassed us entirely". When the latter happens, the Vulkan
// instance is silently created against the OEM driver — the user thinks
// the custom driver is active but it isn't.
std::atomic<bool> g_hook_used{false};

bytehook_stub_t g_dlopen_stub = nullptr;
bytehook_stub_t g_android_dlopen_ext_stub = nullptr;
bytehook_stub_t g_dlsym_stub = nullptr;
bytehook_stub_t g_vkGetInstanceProcAddr_stub = nullptr;
bytehook_stub_t g_vkCreateInstance_stub = nullptr;
bytehook_stub_t g_vkEnumerateInstanceExtensionProperties_stub = nullptr;
bytehook_stub_t g_vkEnumerateInstanceVersion_stub = nullptr;
bytehook_stub_t g_vkEnumeratePhysicalDevices_stub = nullptr;
bytehook_stub_t g_vkGetDeviceProcAddr_stub = nullptr;

// Caller filter: intercept calls originating from the media stack AND from
// the Android Vulkan loader itself. libvulkan.so does an internal dlopen on
// /vendor/lib64/hw/vulkan.adreno.so when libplacebo calls vkCreateInstance —
// without hooking that path, the loader bypasses our redirect and silently
// uses the OEM driver (visible in logs as DRIVER_ID_QUALCOMM_PROPRIETARY
// while our bridge thinks the custom driver is armed).
// Unknown callers are allowed (some wrappers may report nullptr) to be safe;
// system libraries and unrelated native code fall through unchanged.
bool my_caller_allow_filter(const char* caller_path_name, void* /* arg */) {
    if (!caller_path_name) return true;
    return strstr(caller_path_name, "libvulkan.so")     != nullptr ||
           strstr(caller_path_name, "libmpv.so")        != nullptr ||
           strstr(caller_path_name, "libplayer.so")     != nullptr ||
           strstr(caller_path_name, "libplacebo.so")    != nullptr ||
           strstr(caller_path_name, "libavcodec.so")    != nullptr ||
           strstr(caller_path_name, "libavformat.so")   != nullptr ||
           strstr(caller_path_name, "libavutil.so")     != nullptr ||
           strstr(caller_path_name, "libavfilter.so")   != nullptr ||
           strstr(caller_path_name, "libavdevice.so")   != nullptr ||
           strstr(caller_path_name, "libswscale.so")    != nullptr ||
           strstr(caller_path_name, "libswresample.so") != nullptr;
}

// Exact match for the system Vulkan loader filename. We DO NOT match custom
// driver names like "libvulkan_freedreno.so" — those go through mpv's
// --vulkan-library option and the linker's normal refcounted dlopen path.
// Both libvulkan.so and the soname-versioned libvulkan.so.1 are covered.
bool is_libvulkan(const char* filename) {
    if (!filename) return false;
    if (strcmp(filename, "libvulkan.so") == 0 || strcmp(filename, "libvulkan.so.1") == 0) return true;
    size_t len = strlen(filename);
    if (len >= 13 && strcmp(filename + len - 13, "/libvulkan.so") == 0) return true;
    if (len >= 15 && strcmp(filename + len - 15, "/libvulkan.so.1") == 0) return true;
    return false;
}

typedef void* (*PFN_vkGetInstanceProcAddr)(void*, const char*);
typedef int (*PFN_vkCreateInstance)(const void*, const void*, void**);
typedef int (*PFN_vkEnumerateInstanceExtensionProperties)(const char*, uint32_t*, void*);
typedef int (*PFN_vkEnumerateInstanceVersion)(uint32_t*);
typedef int (*PFN_vkEnumeratePhysicalDevices)(void*, uint32_t*, void**);
typedef void* (*PFN_vkGetDeviceProcAddr)(void*, const char*);

void* my_vkGetInstanceProcAddr(void* instance, const char* name);
int my_vkCreateInstance(const void* pCreateInfo, const void* pAllocator, void** pInstance);
int my_vkEnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pPropertyCount, void* pProperties);
int my_vkEnumerateInstanceVersion(uint32_t* pApiVersion);
int my_vkEnumeratePhysicalDevices(void* instance, uint32_t* pPhysicalDeviceCount, void** pPhysicalDevices);
void* my_vkGetDeviceProcAddr(void* device, const char* pName);

void* my_dlopen(const char* filename, int flags) {
    if (filename && g_vulkan_handle && is_libvulkan(filename)) {
        LOGI("Intercepted dlopen(%s) -> custom driver handle", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_dlopen, filename, flags);
}

void* my_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    if (filename && g_vulkan_handle && is_libvulkan(filename)) {
        LOGI("Intercepted android_dlopen_ext(%s) -> custom driver handle", filename);
        return g_vulkan_handle;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_android_dlopen_ext, filename, flags, extinfo);
}

// Intercept dlsym only when the caller queries against our custom handle
// or asks for global resolution (RTLD_DEFAULT/RTLD_NEXT). libplacebo and
// libmpv use the global path in some configurations — without this the
// Vulkan entry points slip past the hook table.
void* my_dlsym(void* handle, const char* symbol) {
    if (g_vulkan_handle && symbol &&
        (handle == g_vulkan_handle || handle == RTLD_DEFAULT || handle == RTLD_NEXT)) {
        if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) return (void*) my_vkGetInstanceProcAddr;
        if (strcmp(symbol, "vkCreateInstance") == 0) return (void*) my_vkCreateInstance;
        if (strcmp(symbol, "vkEnumerateInstanceExtensionProperties") == 0) return (void*) my_vkEnumerateInstanceExtensionProperties;
        if (strcmp(symbol, "vkEnumerateInstanceVersion") == 0) return (void*) my_vkEnumerateInstanceVersion;
        if (strcmp(symbol, "vkEnumeratePhysicalDevices") == 0) return (void*) my_vkEnumeratePhysicalDevices;
        if (strcmp(symbol, "vkGetDeviceProcAddr") == 0) return (void*) my_vkGetDeviceProcAddr;
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_dlsym, handle, symbol);
}

void* my_vkGetInstanceProcAddr(void* instance, const char* name) {
    if (g_vulkan_handle) {
        // Intercept known entry points so subsequent calls go through our
        // hooks regardless of whether the caller used dlsym or this GPA.
        if (name) {
            if (strcmp(name, "vkEnumeratePhysicalDevices") == 0) return (void*) my_vkEnumeratePhysicalDevices;
            if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (void*) my_vkGetDeviceProcAddr;
        }
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
        if (func) {
            int result = func(pCreateInfo, pAllocator, pInstance);
            // Only mark the hook as "actually used" once the custom driver's
            // vkCreateInstance succeeds — a non-zero VkResult means the
            // custom driver rejected the params, and we shouldn't claim the
            // hook is working in that case.
            if (result == 0) g_hook_used.store(true, std::memory_order_relaxed);
            return result;
        }
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

int my_vkEnumeratePhysicalDevices(void* instance, uint32_t* pPhysicalDeviceCount, void** pPhysicalDevices) {
    if (g_vulkan_handle) {
        LOGI("Intercepted vkEnumeratePhysicalDevices");
        auto func = (PFN_vkEnumeratePhysicalDevices) dlsym(g_vulkan_handle, "vkEnumeratePhysicalDevices");
        if (func) return func(instance, pPhysicalDeviceCount, pPhysicalDevices);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkEnumeratePhysicalDevices, instance, pPhysicalDeviceCount, pPhysicalDevices);
}

void* my_vkGetDeviceProcAddr(void* device, const char* pName) {
    if (g_vulkan_handle) {
        auto func = (PFN_vkGetDeviceProcAddr) dlsym(g_vulkan_handle, "vkGetDeviceProcAddr");
        if (func) return func(device, pName);
    }
    BYTEHOOK_STACK_SCOPE();
    return BYTEHOOK_CALL_PREV(my_vkGetDeviceProcAddr, device, pName);
}

void unhook_all_stubs() {
    auto unhook = [](bytehook_stub_t& stub) {
        if (stub) {
            bytehook_unhook(stub);
            stub = nullptr;
        }
    };
    unhook(g_dlopen_stub);
    unhook(g_android_dlopen_ext_stub);
    unhook(g_dlsym_stub);
    unhook(g_vkGetInstanceProcAddr_stub);
    unhook(g_vkCreateInstance_stub);
    unhook(g_vkEnumerateInstanceExtensionProperties_stub);
    unhook(g_vkEnumerateInstanceVersion_stub);
    unhook(g_vkEnumeratePhysicalDevices_stub);
    unhook(g_vkGetDeviceProcAddr_stub);
}

void install_all_hooks() {
    static bool bytehook_initialized = false;
    if (!bytehook_initialized) {
        bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
        bytehook_initialized = true;
    }
    auto hook = [](bytehook_stub_t& stub, const char* sym, void* new_func) {
        if (!stub) {
            stub = bytehook_hook_partial(my_caller_allow_filter, nullptr, nullptr, sym, new_func, nullptr, nullptr);
            if (!stub) LOGW("Failed to install hook for %s", sym);
        }
    };
    hook(g_dlopen_stub,                                    "dlopen",                                 (void*) my_dlopen);
    hook(g_android_dlopen_ext_stub,                        "android_dlopen_ext",                     (void*) my_android_dlopen_ext);
    hook(g_dlsym_stub,                                     "dlsym",                                  (void*) my_dlsym);
    hook(g_vkGetInstanceProcAddr_stub,                     "vkGetInstanceProcAddr",                  (void*) my_vkGetInstanceProcAddr);
    hook(g_vkCreateInstance_stub,                          "vkCreateInstance",                       (void*) my_vkCreateInstance);
    hook(g_vkEnumerateInstanceExtensionProperties_stub,    "vkEnumerateInstanceExtensionProperties", (void*) my_vkEnumerateInstanceExtensionProperties);
    hook(g_vkEnumerateInstanceVersion_stub,                "vkEnumerateInstanceVersion",             (void*) my_vkEnumerateInstanceVersion);
    hook(g_vkEnumeratePhysicalDevices_stub,                "vkEnumeratePhysicalDevices",             (void*) my_vkEnumeratePhysicalDevices);
    hook(g_vkGetDeviceProcAddr_stub,                       "vkGetDeviceProcAddr",                    (void*) my_vkGetDeviceProcAddr);
    LOGI("ByteHook hooks registered (9 entry points, caller-filtered to media stack)");
}

// Read a single line from a sysfs path. Returns an empty string when the
// file is missing or unreadable (very common when running on non-Adreno or
// on devices that don't expose the kgsl debug surface).
std::string read_sysfs(const char* path) {
    std::ifstream file(path);
    if (!file.is_open()) return "";
    std::string line;
    std::getline(file, line);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) {
        line.pop_back();
    }
    return line;
}
} // namespace

// =============================================================================
// JNI entry points
// =============================================================================
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

    std::lock_guard<std::mutex> lock(g_driver_mutex);

    const char* nativeHookLibDir = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    const char* nativeDriverDirRaw = customDriverDir ? env->GetStringUTFChars(customDriverDir, nullptr) : nullptr;
    const char* nativeDriverName = customDriverName ? env->GetStringUTFChars(customDriverName, nullptr) : nullptr;
    const char* nativeFileRedirectDir = fileRedirectDir ? env->GetStringUTFChars(fileRedirectDir, nullptr) : nullptr;
    const char* nativeTmpDir = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;

    // adrenotools expects the driver directory to end with '/' — normalise.
    std::string driverDirStr = nativeDriverDirRaw ? nativeDriverDirRaw : "";
    if (!driverDirStr.empty() && driverDirStr.back() != '/') driverDirStr += '/';
    const char* nativeDriverDir = driverDirStr.empty() ? nullptr : driverDirStr.c_str();

    LOGI("setDriver: hookLibDir=%s driverDir=%s driverName=%s redirect=%s tmp=%s",
         nativeHookLibDir ? nativeHookLibDir : "(null)",
         nativeDriverDir ? nativeDriverDir : "(null)",
         nativeDriverName ? nativeDriverName : "(null)",
         nativeFileRedirectDir ? nativeFileRedirectDir : "(null)",
         nativeTmpDir ? nativeTmpDir : "(null)");

    // Re-init path: clean up the previous load before opening the new one.
    // We deliberately do NOT dlclose the old handle — any live Vulkan
    // objects (instances, devices, command buffers) still hold pointers
    // into that .so's code; closing it would be use-after-free. The handle
    // leaks for the lifetime of the process, which is the safe trade.
    if (g_vulkan_handle) {
        LOGI("setDriver: previous handle present, unhooking before re-init");
        unhook_all_stubs();
        g_vulkan_handle = nullptr;
    }
    // Reset the "hook was used" flag on every (re)init so wasHookUsed()
    // reflects only the current driver session, not a stale signal from a
    // previously-armed driver.
    g_hook_used.store(false, std::memory_order_relaxed);

    void* handle = nullptr;
    int featureFlags = 0;
    if (nativeFileRedirectDir && strlen(nativeFileRedirectDir) > 0) {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    }

    // System-driver mode: no custom name supplied. Hooks stay armed for
    // file-redirect features but we don't try to swap libvulkan.
    if (!nativeDriverName || strlen(nativeDriverName) == 0) {
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags,
            nativeTmpDir, nativeHookLibDir,
            nullptr, nullptr,
            nativeFileRedirectDir, nullptr);
        if (handle) LOGI("setDriver: loaded system driver via adrenotools");
    } else {
        // Custom driver: open it via adrenotools. No silent fallback to the
        // system loader — if the custom .so fails to load we want the call
        // to return false so the Kotlin side can surface a real error
        // instead of pretending the driver is active while libplacebo ends
        // up on the OEM Vulkan blob.
        handle = adrenotools_open_libvulkan(
            2 /* RTLD_NOW */, featureFlags | ADRENOTOOLS_DRIVER_CUSTOM,
            nativeTmpDir, nativeHookLibDir,
            nativeDriverDir, nativeDriverName,
            nativeFileRedirectDir, nullptr);
        if (handle) {
            LOGI("setDriver: loaded custom driver %s", nativeDriverName);
        } else {
            LOGE("setDriver: failed to load custom driver %s (no fallback)", nativeDriverName);
        }
    }

    if (handle) {
        g_vulkan_handle = handle;
        install_all_hooks();
    } else {
        LOGE("setDriver: driver load failed");
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
    JNIEnv* /* env */,
    jobject /* this */) {
    return access("/dev/kgsl-3d0", F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_getGpuInfo(
    JNIEnv* env,
    jobject /* this */) {
    // Best source: the kgsl sysfs node that Qualcomm's kernel exposes,
    // e.g. "Adreno (TM) 740". World-readable, no permission needed.
    std::string gpuModel = read_sysfs("/sys/class/kgsl/kgsl-3d0/gpu_model");
    if (!gpuModel.empty()) {
        LOGI("GPU model from sysfs: %s", gpuModel.c_str());
        return env->NewStringUTF(gpuModel.c_str());
    }

    // Fallback: chip id reads as a hex string like "0x07030001". Won't
    // parse cleanly with parseAdrenoModel but at least tells the user
    // something more specific than the generic banner.
    std::string chipId = read_sysfs("/sys/class/kgsl/kgsl-3d0/gpu_chip_id");
    if (!chipId.empty()) {
        std::string result = "Adreno GPU (Chip ID: " + chipId + ")";
        LOGI("GPU chip ID from sysfs: %s", chipId.c_str());
        return env->NewStringUTF(result.c_str());
    }

    // Last resort: device-presence probe only.
    if (access("/dev/kgsl-3d0", F_OK) == 0) {
        return env->NewStringUTF("Qualcomm Adreno GPU Detected");
    }
    return env->NewStringUTF("Generic GPU Driver Active");
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_isDriverActive(
    JNIEnv* /* env */,
    jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_driver_mutex);
    return g_vulkan_handle != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_wasHookUsed(
    JNIEnv* /* env */,
    jobject /* this */) {
    return g_hook_used.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_unloadDriver(
    JNIEnv* /* env */,
    jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_driver_mutex);
    if (!g_vulkan_handle) {
        LOGI("unloadDriver: no driver loaded, nothing to do");
        return JNI_TRUE;
    }
    LOGI("unloadDriver: unhooking all stubs and clearing driver handle");
    unhook_all_stubs();
    // Intentionally NOT dlclose'ing the handle — Vulkan objects may still
    // reference the driver's code. The handle leaks for the process
    // lifetime; the alternative is a guaranteed use-after-free crash.
    g_vulkan_handle = nullptr;
    return JNI_TRUE;
}

// =============================================================================
// NativeFreedrenoConfig JNI — preserved Eden-style env-var storage. Used by
// GpuDriverHelper for HUD/TU_DEBUG toggles independently of the bytehook path.
// =============================================================================

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_setFreedrenoBasePath(
    JNIEnv* env, jclass /* clazz */, jstring jbasePath) {
    if (!g_config) g_config = std::make_unique<FreedrenoConfig>();
    g_config->base_path = GetJString(env, jbasePath);
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_initializeFreedrenoConfig(
    JNIEnv* /* env */, jclass /* clazz */) {
    if (!g_config) g_config = std::make_unique<FreedrenoConfig>();
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_saveFreedrenoConfig(
    JNIEnv* /* env */, jclass /* clazz */) {
    if (!g_config) return;
    const std::string config_path = GetConfigPath();
    if (config_path.empty()) return;
    FILE* file = fopen(config_path.c_str(), "w");
    if (!file) return;
    for (const auto& entry : g_config->env_vars) {
        fprintf(file, "%s=%s\n", entry.first.c_str(), entry.second.c_str());
    }
    fclose(file);
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_reloadFreedrenoConfig(
    JNIEnv* /* env */, jclass /* clazz */) {
    if (!g_config) return;
    const std::string config_path = GetConfigPath();
    if (config_path.empty()) return;
    g_config->env_vars.clear();
    FILE* file = fopen(config_path.c_str(), "r");
    if (!file) return;
    char line[512];
    while (fgets(line, sizeof(line), file)) {
        size_t len = strlen(line);
        if (len > 0 && line[len - 1] == '\n') line[--len] = '\0';
        if (len == 0 || line[0] == '#') continue;
        const char* eq = strchr(line, '=');
        if (!eq) continue;
        std::string key(line, eq - line);
        std::string value(eq + 1);
        g_config->env_vars[key] = value;
        ApplyEnvironmentVariable(key, value);
    }
    fclose(file);
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_setFreedrenoEnv(
    JNIEnv* env, jclass /* clazz */, jstring jvarName, jstring jvalue) {
    if (!g_config) return JNI_FALSE;
    auto var_name = GetJString(env, jvarName);
    auto value = GetJString(env, jvalue);
    if (var_name.empty()) return JNI_FALSE;
    g_config->env_vars[var_name] = value;
    return ApplyEnvironmentVariable(var_name, value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_getFreedrenoEnv(
    JNIEnv* env, jclass /* clazz */, jstring jvarName) {
    if (!g_config) return env->NewStringUTF("");
    auto var_name = GetJString(env, jvarName);
    auto it = g_config->env_vars.find(var_name);
    return it != g_config->env_vars.end() ? env->NewStringUTF(it->second.c_str()) : env->NewStringUTF("");
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_isFreedrenoEnvSet(
    JNIEnv* env, jclass /* clazz */, jstring jvarName) {
    if (!g_config) return JNI_FALSE;
    auto var_name = GetJString(env, jvarName);
    auto it = g_config->env_vars.find(var_name);
    return (it != g_config->env_vars.end() && !it->second.empty()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_clearFreedrenoEnv(
    JNIEnv* env, jclass /* clazz */, jstring jvarName) {
    if (!g_config) return JNI_FALSE;
    auto var_name = GetJString(env, jvarName);
    auto it = g_config->env_vars.find(var_name);
    if (it != g_config->env_vars.end()) {
        g_config->env_vars.erase(it);
        unsetenv(var_name.c_str());
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_clearAllFreedrenoEnv(
    JNIEnv* /* env */, jclass /* clazz */) {
    if (!g_config) return;
    for (const auto& entry : g_config->env_vars) {
        unsetenv(entry.first.c_str());
    }
    g_config->env_vars.clear();
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_utils_NativeFreedrenoConfig_getFreedrenoEnvSummary(
    JNIEnv* env, jclass /* clazz */) {
    if (!g_config || g_config->env_vars.empty()) return env->NewStringUTF("");
    std::string summary;
    for (const auto& entry : g_config->env_vars) {
        if (!summary.empty()) summary += ",";
        summary += entry.first + "=" + entry.second;
    }
    return env->NewStringUTF(summary.c_str());
}

} // extern "C"
