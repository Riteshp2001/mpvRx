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
    // Use adrenotools specialized function for setting env vars
    if (!adrenotools_set_freedreno_env(key.c_str(), value.c_str())) {
        LOGE("[Freedreno] Failed to set %s=%s via adrenotools", key.c_str(), value.c_str());
        // Fallback to standard setenv
        if (setenv(key.c_str(), value.c_str(), 1) != 0) {
            LOGE("[Freedreno] Fallback setenv also failed");
            return false;
        }
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

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_gpu_GpuDriverBridge_setDriver(
    JNIEnv* env,
    jobject /* this */,
    jint dlopenFlags,
    jint featureFlags,
    jstring tmpLibDir,
    jstring hookLibDir,
    jstring driverDir,
    jstring driverName) {

    const char* nativeTmpLibDir = tmpLibDir ? env->GetStringUTFChars(tmpLibDir, nullptr) : nullptr;
    const char* nativeHookLibDir = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    const char* nativeDriverDir = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* nativeDriverName = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;

    void* handle = adrenotools_open_libvulkan(
        dlopenFlags,
        featureFlags,
        nativeTmpLibDir,
        nativeHookLibDir,
        nativeDriverDir,
        nativeDriverName,
        nullptr, // fileRedirectDir
        nullptr  // userMappingHandle
    );

    if (nativeTmpLibDir) env->ReleaseStringUTFChars(tmpLibDir, nativeTmpLibDir);
    if (nativeHookLibDir) env->ReleaseStringUTFChars(hookLibDir, nativeHookLibDir);
    if (nativeDriverDir) env->ReleaseStringUTFChars(driverDir, nativeDriverDir);
    if (nativeDriverName) env->ReleaseStringUTFChars(driverName, nativeDriverName);

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

// NativeFreedrenoConfig Implementations

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
