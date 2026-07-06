#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>
#include <pthread.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unordered_map>
#include <mutex>
#include <string>
#include <algorithm>
#include <atomic>

#define LOG_TAG "YumyHookNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** CC BY-NC 4.0 — lineage fingerprint for commercial-use tracing; do not remove. */
static const char kLineageWatermark[] = "YH-LIN-8d4e2f91-yumito|CC-BY-NC-4.0|Yumito";

static pthread_rwlock_t g_lock = PTHREAD_RWLOCK_INITIALIZER;
static std::unordered_map<std::string, std::string> g_props;
static bool g_hook_installed = false;
static bool g_read_callback_hooked = false;
static std::atomic<uint32_t> g_get_hits{0};
static std::atomic<uint32_t> g_get_spoofs{0};

static void (*orig_read_callback)(
    const prop_info *pi,
    void (*callback)(void *, const char *, const char *, uint32_t),
    void *cookie) = nullptr;

static std::mutex g_cb_mutex;
static std::unordered_map<void *, void (*)(void *, const char *, const char *, uint32_t)> g_callback_map;

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
    SHADOWHOOK_STACK_SCOPE();
    if (name == nullptr || value == nullptr) {
        return SHADOWHOOK_CALL_PREV(hooked_system_property_get, name, value);
    }

    g_get_hits.fetch_add(1, std::memory_order_relaxed);
    if (lookup_spoofed(name, value)) {
        g_get_spoofs.fetch_add(1, std::memory_order_relaxed);
        return static_cast<int>(strlen(value));
    }

    return SHADOWHOOK_CALL_PREV(hooked_system_property_get, name, value);
}

static int hooked_property_get(const char *key, char *value, const char *default_value) {
    SHADOWHOOK_STACK_SCOPE();
    if (key == nullptr || value == nullptr) {
        return SHADOWHOOK_CALL_PREV(hooked_property_get, key, value, default_value);
    }

    if (lookup_spoofed(key, value)) {
        return static_cast<int>(strlen(value));
    }

    return SHADOWHOOK_CALL_PREV(hooked_property_get, key, value, default_value);
}

static int hooked_system_property_read(const prop_info *pi, char *name, char *value) {
    SHADOWHOOK_STACK_SCOPE();
    int rc = SHADOWHOOK_CALL_PREV(hooked_system_property_read, pi, name, value);
    const char *key = name;
    if (key != nullptr && value != nullptr && lookup_spoofed(key, value)) {
        return static_cast<int>(strlen(value));
    }
    return rc;
}

static void modify_read_callback(void *cookie, const char *name, const char *value, uint32_t serial) {
    void (*user_cb)(void *, const char *, const char *, uint32_t) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_cb_mutex);
        auto it = g_callback_map.find(cookie);
        if (it != g_callback_map.end()) {
            user_cb = it->second;
            g_callback_map.erase(it);
        }
    }
    if (user_cb == nullptr) {
        return;
    }
    char buf[PROP_VALUE_MAX];
    if (name != nullptr && lookup_spoofed(name, buf)) {
        user_cb(cookie, name, buf, serial);
        return;
    }
    user_cb(cookie, name, value, serial);
}

static void hooked_read_callback(
    const prop_info *pi,
    void (*callback)(void *, const char *, const char *, uint32_t),
    void *cookie) {
    SHADOWHOOK_STACK_SCOPE();
    if (callback == nullptr) {
        SHADOWHOOK_CALL_PREV(hooked_read_callback, pi, callback, cookie);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_cb_mutex);
        g_callback_map[cookie] = callback;
    }
    SHADOWHOOK_CALL_PREV(hooked_read_callback, pi, modify_read_callback, cookie);
}

static bool hook_sym(const char *lib, const char *sym, void *new_func, void **orig_func, const char *label) {
    void *stub = shadowhook_hook_sym_name(lib, sym, new_func, orig_func);
    if (stub != nullptr) {
        LOGI("hooked %s %s", lib, label);
        return true;
    }
    LOGE("hook %s %s failed: %s", lib, label, shadowhook_to_errmsg(shadowhook_get_errno()));
    return false;
}

static bool install_read_callback_hook() {
    if (g_read_callback_hooked) {
        return true;
    }
    if (hook_sym(
            "libc.so",
            "__system_property_read_callback",
            reinterpret_cast<void *>(hooked_read_callback),
            reinterpret_cast<void **>(&orig_read_callback),
            "__system_property_read_callback")) {
        g_read_callback_hooked = true;
    }
    return g_read_callback_hooked;
}

static bool install_property_hook() {
    if (g_hook_installed) {
        install_read_callback_hook();
        return true;
    }
    int init_rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (init_rc != 0) {
        LOGE("shadowhook_init failed: %d (%s)", init_rc, shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    LOGI("shadowhook_init ok mode=SHARED");
    bool any = false;
    void *orig_get = nullptr;
    if (hook_sym(
            "libc.so",
            "__system_property_get",
            reinterpret_cast<void *>(hooked_system_property_get),
            &orig_get,
            "__system_property_get")) {
        any = true;
    }
    void *orig_pg = nullptr;
    if (hook_sym(
            "libcutils.so",
            "property_get",
            reinterpret_cast<void *>(hooked_property_get),
            &orig_pg,
            "property_get")) {
        any = true;
    }
    void *orig_read = nullptr;
    if (hook_sym(
            "libc.so",
            "__system_property_read",
            reinterpret_cast<void *>(hooked_system_property_read),
            &orig_read,
            "__system_property_read")) {
        any = true;
    }
    if (install_read_callback_hook()) {
        any = true;
    }
    if (!any) {
        LOGE("no property hooks installed");
        return false;
    }
    g_hook_installed = true;
    LOGI("attribution %s", kLineageWatermark);
    return true;
}

static bool retry_deferred_hooks() {
    if (!g_hook_installed) {
        return install_property_hook();
    }
    bool any = g_read_callback_hooked;
    void *orig_pg = nullptr;
    if (hook_sym(
            "libcutils.so",
            "property_get",
            reinterpret_cast<void *>(hooked_property_get),
            &orig_pg,
            "property_get(deferred)")) {
        any = true;
    }
    if (!g_read_callback_hooked) {
        any = install_read_callback_hook() || any;
    }
    return any;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeInstallPropertyHook(JNIEnv *, jclass) {
    return install_property_hook() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeRetryDeferredHooks(JNIEnv *, jclass) {
    return retry_deferred_hooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeProbeProperty(JNIEnv *env, jclass, jstring jname) {
    if (jname == nullptr) {
        return env->NewStringUTF("");
    }
    const char *name = env->GetStringUTFChars(jname, nullptr);
    if (name == nullptr) {
        return env->NewStringUTF("");
    }
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get(name, value);
    env->ReleaseStringUTFChars(jname, name);
    return env->NewStringUTF(value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yumito_yumyhook_xposed_NativeBridge_nativeHookStats(JNIEnv *env, jclass) {
    char buf[128];
    snprintf(
        buf,
        sizeof(buf),
        "hits=%u spoofs=%u props=%zu hooks=%d read_cb=%d",
        g_get_hits.load(std::memory_order_relaxed),
        g_get_spoofs.load(std::memory_order_relaxed),
        g_props.size(),
        g_hook_installed ? 1 : 0,
        g_read_callback_hooked ? 1 : 0);
    return env->NewStringUTF(buf);
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
    size_t prop_count = g_props.size();
    pthread_rwlock_unlock(&g_lock);
    LOGI("properties synced count=%zu hooks=%d read_cb=%d", prop_count, g_hook_installed, g_read_callback_hooked);
}
