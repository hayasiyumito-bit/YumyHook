#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>
#include <pthread.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unordered_map>
#include <string>
#include <algorithm>

#define LOG_TAG "YumyHookNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pthread_rwlock_t g_lock = PTHREAD_RWLOCK_INITIALIZER;
static std::unordered_map<std::string, std::string> g_props;
static bool g_hook_installed = false;

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static int (*orig_property_get)(const char *key, char *value, const char *default_value) = nullptr;

static const size_t kMaxSafePropLen = 91;

static bool is_safe_property_value(const std::string &value) {
    return value.length() <= kMaxSafePropLen;
}

static void write_property_value(char *value, const char *src, size_t src_len) {
    if (value == nullptr || src == nullptr) {
        return;
    }
    size_t copy_len = std::min(src_len, static_cast<size_t>(PROP_VALUE_MAX - 1));
    if (copy_len > 0) {
        memcpy(value, src, copy_len);
    }
    value[copy_len] = '\0';
}

static bool lookup_spoofed(const char *name, char *value) {
    if (name == nullptr || value == nullptr) {
        return false;
    }
    pthread_rwlock_rdlock(&g_lock);
    auto it = g_props.find(name);
    if (it == g_props.end()) {
        pthread_rwlock_unlock(&g_lock);
        return false;
    }
    const std::string &spoofed = it->second;
    if (!is_safe_property_value(spoofed)) {
        value[0] = '\0';
        pthread_rwlock_unlock(&g_lock);
        return true;
    }
    write_property_value(value, spoofed.c_str(), spoofed.length());
    pthread_rwlock_unlock(&g_lock);
    return true;
}

static int hooked_system_property_get(const char *name, char *value) {
    if (name == nullptr || value == nullptr) {
        if (orig_system_property_get != nullptr) {
            return orig_system_property_get(name, value);
        }
        return 0;
    }

    if (lookup_spoofed(name, value)) {
        return static_cast<int>(strlen(value));
    }

    if (orig_system_property_get != nullptr) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

static int hooked_property_get(const char *key, char *value, const char *default_value) {
    if (key == nullptr || value == nullptr) {
        if (orig_property_get != nullptr) {
            return orig_property_get(key, value, default_value);
        }
        return 0;
    }

    if (lookup_spoofed(key, value)) {
        return static_cast<int>(strlen(value));
    }

    if (orig_property_get != nullptr) {
        return orig_property_get(key, value, default_value);
    }
    if (default_value != nullptr) {
        write_property_value(value, default_value, strlen(default_value));
    } else {
        value[0] = '\0';
    }
    return static_cast<int>(strlen(value));
}

static bool install_property_hook() {
    if (g_hook_installed) {
        return true;
    }
    int init_rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (init_rc != 0) {
        LOGE("shadowhook_init failed: %d", init_rc);
        return false;
    }
    bool any = false;
    void *stub = shadowhook_hook_sym_name(
        "libc.so",
        "__system_property_get",
        reinterpret_cast<void *>(hooked_system_property_get),
        reinterpret_cast<void **>(&orig_system_property_get)
    );
    if (stub != nullptr) {
        any = true;
        LOGI("hooked libc __system_property_get");
    } else {
        LOGE("hook __system_property_get failed: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    }
    void *stub2 = shadowhook_hook_sym_name(
        "libcutils.so",
        "property_get",
        reinterpret_cast<void *>(hooked_property_get),
        reinterpret_cast<void **>(&orig_property_get)
    );
    if (stub2 != nullptr) {
        any = true;
        LOGI("hooked libcutils property_get");
    } else {
        LOGE("hook property_get failed: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    }
    if (!any) {
        return false;
    }
    g_hook_installed = true;
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeInstallPropertyHook(JNIEnv *, jclass) {
    return install_property_hook() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeUpdateProperties(
    JNIEnv *env,
    jclass,
    jobjectArray keys,
    jobjectArray values
) {
    if (keys == nullptr || values == nullptr) {
        return;
    }
    jsize count = env->GetArrayLength(keys);
    if (count != env->GetArrayLength(values)) {
        return;
    }

    std::unordered_map<std::string, std::string> next;
    next.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto keyObj = (jstring) env->GetObjectArrayElement(keys, i);
        auto valObj = (jstring) env->GetObjectArrayElement(values, i);
        if (keyObj == nullptr || valObj == nullptr) {
            continue;
        }
        const char *keyChars = env->GetStringUTFChars(keyObj, nullptr);
        const char *valChars = env->GetStringUTFChars(valObj, nullptr);
        if (keyChars != nullptr && valChars != nullptr) {
            std::string val(valChars);
            if (!is_safe_property_value(val)) {
                val.clear();
            }
            next.emplace(keyChars, std::move(val));
        }
        if (keyChars != nullptr) env->ReleaseStringUTFChars(keyObj, keyChars);
        if (valChars != nullptr) env->ReleaseStringUTFChars(valObj, valChars);
        env->DeleteLocalRef(keyObj);
        env->DeleteLocalRef(valObj);
    }

    pthread_rwlock_wrlock(&g_lock);
    g_props.swap(next);
    pthread_rwlock_unlock(&g_lock);
}
