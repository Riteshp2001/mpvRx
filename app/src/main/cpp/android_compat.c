#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#define LOG_TAG "MpvAndroidCompat"

/*
 * mpv 0.41-dev currently creates subprocesses with a raw
 * clone(CLONE_VM | CLONE_VFORK). On Android this bypasses bionic's fork/vfork
 * bookkeeping, so the child and parent corrupt the same fdsan ownership table.
 * The resulting false ownership failures abort the app on Android 16, usually
 * while ytdl_hook is launching yt-dlp.
 *
 * Keep this workaround until the bundled libmpv is rebuilt with HAVE_CLONE
 * disabled on Android (or otherwise uses bionic's Android-aware spawn path).
 * The Kotlin caller only loads this library on API 36+.
 */
typedef int (*fdsan_set_error_level_fn)(int error_level);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;

    dlerror();
    fdsan_set_error_level_fn set_error_level =
        (fdsan_set_error_level_fn)dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level");
    const char *error = dlerror();
    if (error != NULL || set_error_level == NULL) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG,
            "Unable to resolve android_fdsan_set_error_level: %s",
            error != NULL ? error : "symbol not found"
        );
        return JNI_ERR;
    }

    /* ANDROID_FDSAN_ERROR_LEVEL_DISABLED is the stable enum value 0. */
    int previous_level = set_error_level(0);
    __android_log_print(
        ANDROID_LOG_WARN,
        LOG_TAG,
        "Disabled fdsan for the affected libmpv subprocess path (previous level %d)",
        previous_level
    );
    return JNI_VERSION_1_6;
}
