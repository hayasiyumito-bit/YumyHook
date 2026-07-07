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
#include <cerrno>
#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <fcntl.h>
#include <link.h>
#include <sstream>
#include <unistd.h>

#define LOG_TAG "YumyHookNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** CC BY-NC 4.0 — lineage fingerprint for commercial-use tracing; do not remove. */
static const char kLineageWatermark[] = "YH-LIN-8d4e2f91-yumito|CC-BY-NC-4.0|Yumito";

static pthread_rwlock_t g_lock = PTHREAD_RWLOCK_INITIALIZER;
static std::unordered_map<std::string, std::string> g_props;
static bool g_hook_installed = false;
static bool g_read_callback_hooked = false;
static bool g_property_get_hooked = false;
static bool g_system_property_get_hooked = false;
static bool g_system_property_read_hooked = false;
static bool g_libc_only_mode = false;
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

static std::atomic<bool> g_spoof_active{true};

static bool lookup_spoofed(const char *name, char *value) {
    if (!g_spoof_active.load(std::memory_order_relaxed)) {
        return false;
    }
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
    int err = shadowhook_get_errno();
    // 宿主 App 已 hook 同一符号（如微信自带 shadowhook）— 非致命
    if (err == SHADOWHOOK_ERRNO_HOOK_DUP) {
        LOGI("skip %s %s: already hooked by host", lib, label);
        return false;
    }
    LOGE("hook %s %s failed: %s", lib, label, shadowhook_to_errmsg(err));
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

#include <dlfcn.h>
#include <fstream>

static bool host_shadowhook_present() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) {
        return false;
    }
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("libshadowhook.so") == std::string::npos) {
            continue;
        }
        if (line.find("yumyhook_native") != std::string::npos) {
            continue;
        }
        LOGI("skip native: host libshadowhook mapped");
        return true;
    }
    return false;
}

static bool host_crash_lib_mapped() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) {
        return false;
    }
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("yumyhook_native") != std::string::npos) {
            continue;
        }
        if (line.find("wechatcrash") != std::string::npos ||
            line.find("libbugly") != std::string::npos ||
            line.find("crashsdk") != std::string::npos) {
            return true;
        }
    }
    return false;
}

static bool host_hook_engine_ready() {
    if (host_shadowhook_present()) {
        return true;
    }
    if (!host_crash_lib_mapped()) {
        return false;
    }
    if (dlsym(RTLD_DEFAULT, "shadowhook_hook_sym_name") != nullptr) {
        LOGI("host shadowhook symbols via RTLD_DEFAULT (embedded crash lib)");
        return true;
    }
    return false;
}

static bool install_property_hook() {
    if (g_hook_installed) {
        install_read_callback_hook();
        return true;
    }
    if (host_hook_engine_ready()) {
        LOGI("host shadowhook ready, hook via host (skip shadowhook_init)");
    } else if (!g_libc_only_mode) {
        int init_rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
        if (init_rc != 0) {
            LOGE("shadowhook_init failed: %d (%s)", init_rc, shadowhook_to_errmsg(shadowhook_get_errno()));
            return false;
        }
        LOGI("shadowhook_init ok mode=SHARED");
    } else {
        LOGE("libc_only: no host shadowhook engine");
        return false;
    }
    bool any = false;
    void *orig_get = nullptr;
    if (!g_system_property_get_hooked &&
        hook_sym(
            "libc.so",
            "__system_property_get",
            reinterpret_cast<void *>(hooked_system_property_get),
            &orig_get,
            "__system_property_get")) {
        g_system_property_get_hooked = true;
        any = true;
    }
    void *orig_pg = nullptr;
    if (!g_libc_only_mode && !g_property_get_hooked &&
        hook_sym(
            "libcutils.so",
            "property_get",
            reinterpret_cast<void *>(hooked_property_get),
            &orig_pg,
            "property_get")) {
        g_property_get_hooked = true;
        any = true;
    }
    void *orig_read = nullptr;
    if (!g_system_property_read_hooked &&
        hook_sym(
            "libc.so",
            "__system_property_read",
            reinterpret_cast<void *>(hooked_system_property_read),
            &orig_read,
            "__system_property_read")) {
        g_system_property_read_hooked = true;
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
    if (g_libc_only_mode) {
        return g_read_callback_hooked || g_system_property_get_hooked || g_system_property_read_hooked;
    }
    bool any = g_read_callback_hooked || g_property_get_hooked ||
        g_system_property_get_hooked || g_system_property_read_hooked;
    if (!g_property_get_hooked) {
        void *orig_pg = nullptr;
        if (hook_sym(
                "libcutils.so",
                "property_get",
                reinterpret_cast<void *>(hooked_property_get),
                &orig_pg,
                "property_get(deferred)")) {
            g_property_get_hooked = true;
            any = true;
        }
    }
    if (!g_read_callback_hooked) {
        any = install_read_callback_hook() || any;
    }
    return any;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeInstallPropertyHook(JNIEnv *, jclass, jboolean libc_only) {
    g_libc_only_mode = libc_only == JNI_TRUE;
    return install_property_hook() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeRetryDeferredHooks(JNIEnv *, jclass) {
    return retry_deferred_hooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeProbeProperty(JNIEnv *env, jclass, jstring jname) {
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
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeHookStats(JNIEnv *env, jclass) {
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
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeSetSpoofActive(JNIEnv *, jclass, jboolean active) {
    g_spoof_active.store(active == JNI_TRUE, std::memory_order_relaxed);
    if (!g_spoof_active.load(std::memory_order_relaxed)) {
        pthread_rwlock_wrlock(&g_lock);
        g_props.clear();
        pthread_rwlock_unlock(&g_lock);
        LOGI("native spoof deactivated, props cleared");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_yumito_yumyhook_xposed_channel_NativeBridge_nativeUpdateProperties(
    JNIEnv *env,
    jclass,
    jobjectArray keys,
    jobjectArray values
) {
    if (keys == nullptr || values == nullptr) {
        pthread_rwlock_wrlock(&g_lock);
        g_props.clear();
        g_spoof_active.store(false, std::memory_order_relaxed);
        pthread_rwlock_unlock(&g_lock);
        return;
    }
    jsize count = env->GetArrayLength(keys);
    if (count != env->GetArrayLength(values)) {
        return;
    }
    if (count == 0) {
        pthread_rwlock_wrlock(&g_lock);
        g_props.clear();
        g_spoof_active.store(false, std::memory_order_relaxed);
        pthread_rwlock_unlock(&g_lock);
        LOGI("properties cleared, native spoof off");
        return;
    }
    g_spoof_active.store(true, std::memory_order_relaxed);

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

// --- proc / linker stealth (hide LSPosed / shadowhook fingerprints) ---

static bool g_proc_stealth_installed = false;
static std::string g_proc_cache_dir;

static const char *kProcFilterKeywords[] = {
    "frida", "xposed", "lsposed", "lspatch", "substrate", "yumyhook", "yumyhook_native",
    "libyumyhook", "yumito", "lspd", "liblspd", "edxposed", "riru", "libriru", "zygisk",
    "shadowhook", "bytehook", "whale", "sandhook", "epic", "pine", "dobby", nullptr,
};

static std::string to_lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

static bool proc_line_should_hide(const std::string &line) {
    const std::string lower = to_lower_ascii(line);
    for (const char **kw = kProcFilterKeywords; *kw != nullptr; ++kw) {
        if (lower.find(*kw) != std::string::npos) {
            return true;
        }
    }
    if (lower.find("r-xp") == std::string::npos || lower.find("[anon:") == std::string::npos) {
        return false;
    }
    return lower.find("hook") != std::string::npos ||
        lower.find("shadow") != std::string::npos ||
        lower.find("trampoline") != std::string::npos ||
        lower.find("jit-cache") != std::string::npos;
}

static std::string filter_proc_maps_content(const std::string &raw) {
    std::ostringstream out;
    std::istringstream in(raw);
    std::string line;
    bool first = true;
    while (std::getline(in, line)) {
        if (proc_line_should_hide(line)) {
            continue;
        }
        if (!first) {
            out << '\n';
        }
        out << line;
        first = false;
    }
    return out.str();
}

static std::string filter_proc_status_content(const std::string &raw) {
    std::ostringstream out;
    std::istringstream in(raw);
    std::string line;
    bool first = true;
    while (std::getline(in, line)) {
        std::string next = line;
        if (line.rfind("TracerPid:", 0) == 0) {
            next = "TracerPid:\t0";
        } else if (line.rfind("Ptrace:", 0) == 0) {
            next = "Ptrace:\t0";
        }
        if (!first) {
            out << '\n';
        }
        out << next;
        first = false;
    }
    return out.str();
}

enum class ProcPathKind { NONE, MAPS, STATUS, MOUNTINFO, MEM };

static ProcPathKind classify_proc_path(const char *path) {
    if (path == nullptr || strstr(path, "/proc/") == nullptr) {
        return ProcPathKind::NONE;
    }
    if (strstr(path, "/map_files") != nullptr) {
        return ProcPathKind::MAPS;
    }
    const size_t len = strlen(path);
    if (len >= 4 && strcmp(path + len - 4, "/mem") == 0) {
        return ProcPathKind::MEM;
    }
    if (len >= 10 && strcmp(path + len - 10, "/mountinfo") == 0) {
        return ProcPathKind::MOUNTINFO;
    }
    if (len >= 5 && strcmp(path + len - 5, "/maps") == 0) {
        return ProcPathKind::MAPS;
    }
    if (len >= 6 && strcmp(path + len - 6, "/smaps") == 0) {
        return ProcPathKind::MAPS;
    }
    if (len >= 7 && strcmp(path + len - 7, "/status") == 0) {
        return ProcPathKind::STATUS;
    }
    return ProcPathKind::NONE;
}

static std::string read_file_to_string(const char *path) {
    std::ifstream in(path);
    if (!in.is_open()) {
        return {};
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

static std::string write_filtered_proc_temp(ProcPathKind kind) {
    if (g_proc_cache_dir.empty()) {
        return {};
    }
    const char *source = "/proc/self/maps";
    if (kind == ProcPathKind::STATUS) {
        source = "/proc/self/status";
    } else if (kind == ProcPathKind::MOUNTINFO) {
        source = "/proc/self/mountinfo";
    }
    const std::string raw = read_file_to_string(source);
    if (raw.empty()) {
        return {};
    }
    const std::string filtered = kind == ProcPathKind::STATUS
        ? filter_proc_status_content(raw)
        : filter_proc_maps_content(raw);
    const char *tag = "maps";
    if (kind == ProcPathKind::STATUS) {
        tag = "status";
    } else if (kind == ProcPathKind::MOUNTINFO) {
        tag = "mountinfo";
    }
    static std::atomic<uint32_t> seq{0};
    const uint32_t id = seq.fetch_add(1, std::memory_order_relaxed);
    std::ostringstream path;
    path << g_proc_cache_dir << "/.yh_" << tag << '_' << getpid() << '_' << id;
    std::ofstream out(path.str(), std::ios::trunc);
    if (!out.is_open()) {
        return {};
    }
    out << filtered;
    out.close();
    return path.str();
}

static bool is_sensitive_lib_name(const char *name) {
    if (name == nullptr || name[0] == '\0') {
        return false;
    }
    const std::string lower = to_lower_ascii(name);
    static const char *kLibs[] = {
        "shadowhook", "yumyhook", "xposed", "lsposed", "frida", "riru", "lspd", "zygisk",
        "edxposed", "substrate", "whale", "bytehook", nullptr,
    };
    for (const char **lib = kLibs; *lib != nullptr; ++lib) {
        if (lower.find(*lib) != std::string::npos) {
            return true;
        }
    }
    return false;
}

static bool is_sensitive_dlsym_name(const char *symbol) {
    if (symbol == nullptr) {
        return false;
    }
    const std::string lower = to_lower_ascii(symbol);
    return lower.find("shadowhook") != std::string::npos ||
        lower.find("xposedbridge") != std::string::npos ||
        lower.find("lsposed") != std::string::npos;
}

static bool is_sensitive_env_value(const char *value) {
    if (value == nullptr) {
        return false;
    }
    const std::string lower = to_lower_ascii(value);
    static const char *kMarkers[] = {
        "xposed", "lsposed", "frida", "magisk", "riru", "zygisk", "shadowhook", "yumyhook", nullptr,
    };
    for (const char **marker = kMarkers; *marker != nullptr; ++marker) {
        if (lower.find(*marker) != std::string::npos) {
            return true;
        }
    }
    return false;
}

static char *(*orig_getenv)(const char *) = nullptr;
static int (*orig_dladdr)(const void *, Dl_info *) = nullptr;

static char *hooked_getenv(const char *name) {
    char *value = orig_getenv(name);
    if (name == nullptr || value == nullptr) {
        return value;
    }
    const std::string key = to_lower_ascii(name);
    if (key == "ld_preload" || key == "ld_library_path" || key == "classpath") {
        if (is_sensitive_env_value(value)) {
            return nullptr;
        }
    }
    return value;
}

static int hooked_dladdr(const void *addr, Dl_info *info) {
    const int rc = orig_dladdr(addr, info);
    if (rc == 0 || info == nullptr) {
        return rc;
    }
    if (info->dli_fname != nullptr && is_sensitive_lib_name(info->dli_fname)) {
        info->dli_fname = "/system/lib64/libc.so";
        info->dli_sname = nullptr;
        info->dli_saddr = nullptr;
    }
    return rc;
}

static int (*orig_open)(const char *, int, ...) = nullptr;
static int (*orig_openat)(int, const char *, int, ...) = nullptr;
static FILE *(*orig_fopen)(const char *, const char *) = nullptr;
static void *(*orig_dlsym)(void *, const char *) = nullptr;
static int (*orig_dl_iterate_phdr)(int (*)(struct dl_phdr_info *, size_t, void *), void *) = nullptr;

static int call_orig_open(const char *pathname, int flags, mode_t mode, bool has_mode) {
    if (has_mode) {
        return orig_open(pathname, flags, mode);
    }
    return orig_open(pathname, flags);
}

static int call_orig_openat(int dirfd, const char *pathname, int flags, mode_t mode, bool has_mode) {
    if (has_mode) {
        return orig_openat(dirfd, pathname, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags);
}

static int hooked_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    const bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    const ProcPathKind kind = classify_proc_path(pathname);
    if (kind == ProcPathKind::MEM) {
        errno = ENOENT;
        return -1;
    }
    if (kind != ProcPathKind::NONE) {
        const std::string redirect = write_filtered_proc_temp(kind);
        if (!redirect.empty()) {
            return call_orig_open(redirect.c_str(), flags, mode, has_mode);
        }
    }
    return call_orig_open(pathname, flags, mode, has_mode);
}

static int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    const bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    const ProcPathKind kind = classify_proc_path(pathname);
    if (kind == ProcPathKind::MEM) {
        errno = ENOENT;
        return -1;
    }
    if (kind != ProcPathKind::NONE) {
        const std::string redirect = write_filtered_proc_temp(kind);
        if (!redirect.empty()) {
            return call_orig_openat(dirfd, redirect.c_str(), flags, mode, has_mode);
        }
    }
    return call_orig_openat(dirfd, pathname, flags, mode, has_mode);
}

static FILE *hooked_fopen(const char *pathname, const char *mode) {
    const ProcPathKind kind = classify_proc_path(pathname);
    if (kind == ProcPathKind::MEM) {
        errno = ENOENT;
        return nullptr;
    }
    if (kind != ProcPathKind::NONE) {
        const std::string redirect = write_filtered_proc_temp(kind);
        if (!redirect.empty()) {
            return orig_fopen(redirect.c_str(), mode);
        }
    }
    return orig_fopen(pathname, mode);
}

static void *hooked_dlsym(void *handle, const char *symbol) {
    if (is_sensitive_dlsym_name(symbol)) {
        return nullptr;
    }
    return orig_dlsym(handle, symbol);
}

struct DlIterateCtx {
    int (*callback)(struct dl_phdr_info *, size_t, void *);
    void *data;
};

static int dl_iterate_shim(struct dl_phdr_info *info, size_t size, void *data) {
    auto *ctx = static_cast<DlIterateCtx *>(data);
    if (info != nullptr && is_sensitive_lib_name(info->dlpi_name)) {
        return 0;
    }
    return ctx->callback(info, size, ctx->data);
}

static int hooked_dl_iterate_phdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data) {
    DlIterateCtx ctx{callback, data};
    return orig_dl_iterate_phdr(dl_iterate_shim, &ctx);
}

static bool ensure_hook_engine_for_stealth() {
    if (host_hook_engine_ready()) {
        return true;
    }
    const int init_rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (init_rc != 0) {
        LOGE("proc stealth shadowhook_init failed: %d (%s)", init_rc, shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    LOGI("proc stealth shadowhook_init ok");
    return true;
}

static bool install_proc_stealth_hooks() {
    if (g_proc_stealth_installed) {
        return true;
    }
    if (g_proc_cache_dir.empty()) {
        LOGE("proc stealth missing cache dir");
        return false;
    }
    if (!ensure_hook_engine_for_stealth()) {
        return false;
    }
    bool any = false;
    void *orig = nullptr;
    if (hook_sym("libc.so", "open", reinterpret_cast<void *>(hooked_open), &orig, "open(proc)")) {
        orig_open = reinterpret_cast<int (*)(const char *, int, ...)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libc.so", "openat", reinterpret_cast<void *>(hooked_openat), &orig, "openat(proc)")) {
        orig_openat = reinterpret_cast<int (*)(int, const char *, int, ...)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libc.so", "fopen", reinterpret_cast<void *>(hooked_fopen), &orig, "fopen(proc)")) {
        orig_fopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libdl.so", "dlsym", reinterpret_cast<void *>(hooked_dlsym), &orig, "dlsym(stealth)")) {
        orig_dlsym = reinterpret_cast<void *(*)(void *, const char *)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libdl.so", "dl_iterate_phdr", reinterpret_cast<void *>(hooked_dl_iterate_phdr), &orig, "dl_iterate_phdr")) {
        orig_dl_iterate_phdr = reinterpret_cast<int (*)(int (*)(struct dl_phdr_info *, size_t, void *), void *)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libc.so", "getenv", reinterpret_cast<void *>(hooked_getenv), &orig, "getenv(stealth)")) {
        orig_getenv = reinterpret_cast<char *(*)(const char *)>(orig);
        any = true;
    }
    orig = nullptr;
    if (hook_sym("libdl.so", "dladdr", reinterpret_cast<void *>(hooked_dladdr), &orig, "dladdr(stealth)")) {
        orig_dladdr = reinterpret_cast<int (*)(const void *, Dl_info *)>(orig);
        any = true;
    }
    g_proc_stealth_installed = any;
    LOGI("proc stealth hooks=%d", any ? 1 : 0);
    return any;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yumito_yumyhook_xposed_stealth_hide_NativeStealthBridge_nativeInstallProcStealth(
    JNIEnv *env,
    jclass,
    jstring jCacheDir
) {
    if (jCacheDir == nullptr) {
        return JNI_FALSE;
    }
    const char *cache_dir = env->GetStringUTFChars(jCacheDir, nullptr);
    if (cache_dir == nullptr) {
        return JNI_FALSE;
    }
    g_proc_cache_dir = cache_dir;
    env->ReleaseStringUTFChars(jCacheDir, cache_dir);
    return install_proc_stealth_hooks() ? JNI_TRUE : JNI_FALSE;
}
