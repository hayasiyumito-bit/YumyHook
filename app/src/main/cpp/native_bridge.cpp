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
#include <sys/stat.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fstream>
#include <sys/syscall.h>
#include <unordered_set>

#define LOG_TAG "YH-NATIVE-PROP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define STEALTH_TAG "YH-NATIVE-STEALTH"
#define SLOGI(...) __android_log_print(ANDROID_LOG_INFO, STEALTH_TAG, __VA_ARGS__)
#define SLOGE(...) __android_log_print(ANDROID_LOG_ERROR, STEALTH_TAG, __VA_ARGS__)

static const char kLineageWatermark[] = "YH-LIN-8d4e2f91-yumito|CC-BY-NC-4.0|Yumito";

static pthread_rwlock_t g_lock = PTHREAD_RWLOCK_INITIALIZER;
static std::unordered_map<std::string, std::string> g_props;
static bool g_hook_installed = false;
static bool g_property_get_hooked = false;
static bool g_system_property_get_hooked = false;
static bool g_system_property_read_hooked = false;
static bool g_read_callback_hooked = false;
static bool g_libc_only_mode = false;
static std::atomic<bool> g_shadowhook_engine_ready{false};
static std::atomic<uint32_t> g_get_hits{0};
static std::atomic<uint32_t> g_get_spoofs{0};
static bool g_proc_stealth_installed = false;
static std::string g_proc_cache_dir;

// --- Prototypes ---
static int hooked_access(const char *pathname, int mode);
static int hooked_faccessat(int dirfd, const char *pathname, int mode, int flags);
static int hooked_faccessat2(int dirfd, const char *pathname, int mode, int flags);
static int hooked_stat(const char *pathname, struct stat *buf);
static int hooked_lstat(const char *pathname, struct stat *buf);
static int hooked_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
static int hooked_statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf);
static int hooked_open(const char *pathname, int flags, ...);
static int hooked_openat(int dirfd, const char *pathname, int flags, ...);
static FILE *hooked_fopen(const char *pathname, const char *mode);
static int yh_dlsym_property_get(const char *key, char *value, const char *default_value);
static bool is_sensitive_dlsym_name(const char *symbol);
static int hooked___faccessat(int dirfd, const char *pathname, int mode, int flags);
static int hooked___openat(int dirfd, const char *pathname, int flags, int mode);
static char *hooked_fgets(char *buf, int size, FILE *stream);
static ssize_t hooked_read(int fd, void *buf, size_t count);
static ssize_t hooked_readlink(const char *pathname, char *buf, size_t bufsiz);
static ssize_t hooked_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
static long hooked_syscall(long number, ...);
static long hooked___syscall(long number, ...);
static int hooked_close(int fd);

static void (*orig_read_callback)(const prop_info *pi, void (*callback)(void *, const char *, const char *, uint32_t), void *cookie) = nullptr;
static std::mutex g_cb_mutex;
static std::unordered_map<void *, void (*)(void *, const char *, const char *, uint32_t)> g_callback_map;

static std::atomic<bool> g_spoof_active{true};

static void write_property_value(char *value, const char *src, size_t src_len) {
    if (!value || !src) return;
    size_t copy_len = std::min(src_len, static_cast<size_t>(PROP_VALUE_MAX - 1));
    memcpy(value, src, copy_len);
    value[copy_len] = '\0';
}

static bool lookup_spoofed(const char *name, char *value) {
    if (!g_spoof_active.load(std::memory_order_relaxed) || !name || !value) return false;
    pthread_rwlock_rdlock(&g_lock);
    auto it = g_props.find(name);
    if (it == g_props.end()) { pthread_rwlock_unlock(&g_lock); return false; }
    if (it->second.length() > 91) { value[0] = '\0'; pthread_rwlock_unlock(&g_lock); return true; }
    write_property_value(value, it->second.c_str(), it->second.length());
    pthread_rwlock_unlock(&g_lock);
    return true;
}

static int hooked_system_property_get(const char *name, char *value) {
    SHADOWHOOK_STACK_SCOPE();
    g_get_hits.fetch_add(1, std::memory_order_relaxed);
    if (lookup_spoofed(name, value)) { g_get_spoofs.fetch_add(1, std::memory_order_relaxed); return static_cast<int>(strlen(value)); }
    return SHADOWHOOK_CALL_PREV(hooked_system_property_get, name, value);
}

static int hooked_property_get(const char *key, char *value, const char *default_value) {
    SHADOWHOOK_STACK_SCOPE();
    if (lookup_spoofed(key, value)) return static_cast<int>(strlen(value));
    return SHADOWHOOK_CALL_PREV(hooked_property_get, key, value, default_value);
}

static int (*orig_property_get_fn)(const char *, char *, const char *) = nullptr;
static int yh_dlsym_property_get(const char *key, char *value, const char *default_value) {
    if (!key || !value) return 0;
    if (lookup_spoofed(key, value)) return static_cast<int>(strlen(value));
    if (orig_property_get_fn) return orig_property_get_fn(key, value, default_value);
    int len = __system_property_get(key, value);
    if (len > 0) return len;
    if (default_value) { write_property_value(value, default_value, strlen(default_value)); return static_cast<int>(strlen(value)); }
    value[0] = '\0'; return 0;
}

static void *(*orig_dlsym)(void *, const char *) = nullptr;
static void *hooked_dlsym(void *handle, const char *symbol) {
    if (!symbol) return orig_dlsym ? orig_dlsym(handle, symbol) : nullptr;
    // property_get: symbol may not be exported in libcutils.so; provide our spoofing wrapper
    if (strcmp(symbol, "property_get") == 0) return (void *)yh_dlsym_property_get;
    if (strcmp(symbol, "access") == 0) return (void *)hooked_access;
    if (strcmp(symbol, "faccessat") == 0) return (void *)hooked_faccessat;
    if (strcmp(symbol, "open") == 0) return (void *)hooked_open;
    if (strcmp(symbol, "openat") == 0) return (void *)hooked_openat;
    if (strcmp(symbol, "fopen") == 0) return (void *)hooked_fopen;
    if (strcmp(symbol, "stat") == 0) return (void *)hooked_stat;
    if (strcmp(symbol, "lstat") == 0) return (void *)hooked_lstat;
    if (is_sensitive_dlsym_name(symbol)) return nullptr;
    return orig_dlsym ? orig_dlsym(handle, symbol) : nullptr;
}

static int hooked_system_property_read(const prop_info *pi, char *name, char *value) {
    SHADOWHOOK_STACK_SCOPE();
    int rc = SHADOWHOOK_CALL_PREV(hooked_system_property_read, pi, name, value);
    if (name && value && lookup_spoofed(name, value)) return static_cast<int>(strlen(value));
    return rc;
}

static void modify_read_callback(void *cookie, const char *name, const char *value, uint32_t serial) {
    void (*user_cb)(void *, const char *, const char *, uint32_t) = nullptr;
    { std::lock_guard<std::mutex> lock(g_cb_mutex); auto it = g_callback_map.find(cookie); if (it != g_callback_map.end()) { user_cb = it->second; g_callback_map.erase(it); } }
    if (!user_cb) return;
    char buf[PROP_VALUE_MAX];
    if (name && lookup_spoofed(name, buf)) { user_cb(cookie, name, buf, serial); return; }
    user_cb(cookie, name, value, serial);
}

static void hooked_read_callback(const prop_info *pi, void (*callback)(void *, const char *, const char *, uint32_t), void *cookie) {
    SHADOWHOOK_STACK_SCOPE();
    if (!callback) { SHADOWHOOK_CALL_PREV(hooked_read_callback, pi, callback, cookie); return; }
    { std::lock_guard<std::mutex> lock(g_cb_mutex); g_callback_map[cookie] = callback; }
    SHADOWHOOK_CALL_PREV(hooked_read_callback, pi, modify_read_callback, cookie);
}

static bool hook_sym(const char *lib, const char *sym, void *new_func, void **orig_func, const char *label) {
    void *stub = shadowhook_hook_sym_name(lib, sym, new_func, orig_func);
    if (stub) { LOGI("hooked %s %s", lib, label); return true; }
    int err = shadowhook_get_errno();
    if (err == SHADOWHOOK_ERRNO_HOOK_DUP) { LOGI("skip %s %s: already hooked", lib, label); return false; }
    LOGE("hook %s %s fail: %s", lib, label, shadowhook_to_errmsg(err));
    return false;
}

static bool hook_libc_sym(const char *sym, void *new_func, void **orig_func, const char *label) {
    static const char *kLibcPaths[] = {"libc.so", "/apex/com.android.runtime/lib64/bionic/libc.so", "/apex/com.android.runtime/lib/bionic/libc.so", nullptr};
    for (const char **lib = kLibcPaths; *lib; ++lib) if (hook_sym(*lib, sym, new_func, orig_func, label)) return true;
    return false;
}

static thread_local int g_proc_io_bypass_depth = 0;
struct ProcIoBypassGuard { ProcIoBypassGuard() { ++g_proc_io_bypass_depth; } ~ProcIoBypassGuard() { --g_proc_io_bypass_depth; } };
static bool proc_io_bypass() { return g_proc_io_bypass_depth > 0; }

static std::string to_lower_ascii(std::string v) { std::transform(v.begin(), v.end(), v.begin(), [](unsigned char c){ return static_cast<char>(std::tolower(c)); }); return v; }

static const char *kProcFilterKeywords[] = {"frida", "xposed", "lsposed", "lspatch", "substrate", "yumyhook", "yumyhook_native", "yumito", "lspd", "zygisk", "shadowhook", "bytehook", "whale", "sandhook", "dobby", "magisk", "magiskpolicy", "resetprop", "kernelsu", "ksu", "ksud", "apatch", "bmax", "apd", "supersu", "busybox", nullptr};

static bool proc_line_should_hide(const std::string &line) {
    std::string lower = to_lower_ascii(line);
    for (const char **kw = kProcFilterKeywords; *kw; ++kw) if (lower.find(*kw) != std::string::npos) return true;
    if (lower.find("r-xp") != std::string::npos && lower.find("[anon:") != std::string::npos) {
        if (lower.find("hook") != std::string::npos || lower.find("shadow") != std::string::npos || lower.find("trampoline") != std::string::npos) return true;
    }
    return false;
}

static std::string filter_content(const std::string &raw, bool is_status) {
    std::ostringstream out; std::istringstream in(raw); std::string line; bool first = true;
    while (std::getline(in, line)) {
        if (is_status) { if (line.rfind("TracerPid:", 0) == 0) line = "TracerPid:\t0"; else if (line.rfind("Ptrace:", 0) == 0) line = "Ptrace:\t0"; }
        else if (proc_line_should_hide(line)) continue;
        if (!first) out << '\n'; out << line; first = false;
    }
    return out.str();
}

enum class ProcPathKind { NONE, MAPS, STATUS, MOUNTINFO, MOUNTS, MEM, NET_UNIX };
static FILE *(*orig_fopen)(const char *, const char *) = nullptr;
static int (*orig_open)(const char *, int, ...) = nullptr;
static int (*orig_openat)(int, const char *, int, ...) = nullptr;
static int (*orig_access)(const char *, int) = nullptr;
static int (*orig_faccessat)(int, const char *, int, int) = nullptr;
static int (*orig_stat)(const char *, struct stat *) = nullptr;
static int (*orig_lstat)(const char *, struct stat *) = nullptr;
static ssize_t (*orig_readlink)(const char *, char *, size_t) = nullptr;
static int (*orig___faccessat)(int, const char *, int, int) = nullptr;
static int (*orig___openat)(int, const char *, int, int) = nullptr;
static char *(*orig_fgets)(char *, int, FILE *) = nullptr;
static ssize_t (*orig_read)(int, void *, size_t) = nullptr;
static long (*orig_syscall)(long, ...) = nullptr;
static long (*orig___syscall)(long, ...) = nullptr;
static int (*orig_close)(int) = nullptr;
static std::unordered_map<int, std::string> g_read_line_buf;
static std::mutex g_proc_fds_mutex;
static std::unordered_set<int> g_proc_fds;
static std::mutex g_proc_file_ptrs_mutex;
static std::unordered_set<FILE *> g_proc_file_ptrs;

static ProcPathKind classify_proc_path(const char *path) {
    if (!path || !strstr(path, "/proc/")) return ProcPathKind::NONE;
    if (strstr(path, "/map_files") || strstr(path, "/maps") || strstr(path, "/smaps")) return ProcPathKind::MAPS;
    if (strstr(path, "/status")) return ProcPathKind::STATUS;
    if (strstr(path, "/mountinfo")) return ProcPathKind::MOUNTINFO;
    if (strstr(path, "/mounts")) return ProcPathKind::MOUNTS;
    if (strstr(path, "/mem")) return ProcPathKind::MEM;
    if (strstr(path, "/proc/net/unix")) return ProcPathKind::NET_UNIX;
    return ProcPathKind::NONE;
}

static std::string read_file_to_string(const char *path) {
    if (!path || path[0] == '\0') return {};
    ProcIoBypassGuard guard;
    FILE *fp = (orig_fopen) ? orig_fopen(path, "r") : fopen(path, "r");
    if (!fp) return {};
    std::ostringstream ss; char buf[4096]; while (fgets(buf, sizeof(buf), fp)) ss << buf; fclose(fp);
    return ss.str();
}

static std::string write_filtered_proc_temp(ProcPathKind kind) {
    if (g_proc_cache_dir.empty()) return {};
    const char *src = "/proc/self/maps";
    if (kind == ProcPathKind::STATUS) src = "/proc/self/status";
    else if (kind == ProcPathKind::MOUNTINFO) src = "/proc/self/mountinfo";
    else if (kind == ProcPathKind::MOUNTS) src = "/proc/self/mounts";
    else if (kind == ProcPathKind::NET_UNIX) src = "/proc/net/unix";

    std::string raw = read_file_to_string(src);
    if (raw.empty()) return {};
    std::string filtered = filter_content(raw, kind == ProcPathKind::STATUS);

    static std::atomic<uint32_t> seq{0};
    std::ostringstream path; path << g_proc_cache_dir << "/.yh_p" << static_cast<int>(kind) << "_" << getpid() << "_" << seq.fetch_add(1);
    std::ofstream out(path.str(), std::ios::trunc); if (!out.is_open()) return {};
    out << filtered; out.close(); return path.str();
}

static bool is_root_sensitive_path(const char *path) {
    if (!path || path[0] == '\0') return false;
    std::string lower = to_lower_ascii(path);
    if (lower.length() >= 3 && lower.compare(lower.length() - 3, 3, "/su") == 0) return true;
    static const char *kKws[] = {"magisk", "zygisk", "kernelsu", "ksu", "ksud", "apatch", "bmax", "apd", "supersu", "daemonsu", "busybox", "/data/adb", "/debug_ramdisk", "frida-server", nullptr};
    for (const char **kw = kKws; *kw; ++kw) if (lower.find(*kw) != std::string::npos) return true;
    return lower.find("/proc/") != std::string::npos && lower.find("/mem") != std::string::npos;
}

static int deny_root_path_errno() { errno = ENOENT; return -1; }

static int hooked_access(const char *p, int m) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_access, p, m);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_access, p, m);
}
static int hooked_faccessat(int d, const char *p, int m, int f) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_faccessat, d, p, m, f);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_faccessat, d, p, m, f);
}
static int hooked_faccessat2(int d, const char *p, int m, int f) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_faccessat2, d, p, m, f);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_faccessat2, d, p, m, f);
}
static int hooked_stat(const char *p, struct stat *b) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_stat, p, b);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_stat, p, b);
}
static int hooked_lstat(const char *p, struct stat *b) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_lstat, p, b);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_lstat, p, b);
}
static int hooked_fstatat(int d, const char *p, struct stat *b, int f) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_fstatat, d, p, b, f);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_fstatat, d, p, b, f);
}
static int hooked_statx(int d, const char *p, int f, unsigned int m, void *b) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_statx, d, p, f, m, b);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked_statx, d, p, f, m, b);
}
static int hooked___faccessat(int d, const char *p, int m, int f) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked___faccessat, d, p, m, f);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    return SHADOWHOOK_CALL_PREV(hooked___faccessat, d, p, m, f);
}
static ssize_t hooked_readlink(const char *p, char *b, size_t s) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_readlink, p, b, s);
    if (is_root_sensitive_path(p)) { errno = ENOENT; return -1; }
    return SHADOWHOOK_CALL_PREV(hooked_readlink, p, b, s);
}
static ssize_t hooked_readlinkat(int d, const char *p, char *b, size_t s) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_readlinkat, d, p, b, s);
    if (is_root_sensitive_path(p)) { errno = ENOENT; return -1; }
    return SHADOWHOOK_CALL_PREV(hooked_readlinkat, d, p, b, s);
}

static long hooked_syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);
    long args[6];
    for (int i = 0; i < 6; i++) args[i] = va_arg(ap, long);
    va_end(ap);

#if defined(__aarch64__)
    // faccessat (56) / faccessat2 (439) — block root-sensitive paths
    if (number == __NR_faccessat || number == 439) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass() && is_root_sensitive_path(pathname)) {
            SLOGI("syscall faccessat blocked: %s", pathname);
            errno = ENOENT;
            return -1;
        }
    }

    // newfstatat (79) — block root-sensitive paths
    if (number == __NR_newfstatat) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass() && is_root_sensitive_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
    }

    // openat (56 on arm64) — block root-sensitive paths + filter proc files
    if (number == __NR_openat) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass()) {
            if (is_root_sensitive_path(pathname)) {
                SLOGI("syscall openat blocked: %s", pathname);
                errno = ENOENT;
                return -1;
            }
            ProcPathKind kind = classify_proc_path(pathname);
            if (kind == ProcPathKind::MEM) {
                errno = ENOENT;
                return -1;
            }
            if (kind != ProcPathKind::NONE && !g_proc_cache_dir.empty()) {
                std::string red = write_filtered_proc_temp(kind);
                if (!red.empty()) {
                    return syscall(number, args[0], (long)red.c_str(), args[2], args[3]);
                }
                errno = ENOENT;
                return -1;
            }
        }
    }
#endif

    return syscall(number, args[0], args[1], args[2], args[3], args[4], args[5]);
}

static long hooked___syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);
    long args[6];
    for (int i = 0; i < 6; i++) args[i] = va_arg(ap, long);
    va_end(ap);

#if defined(__aarch64__)
    if (number == __NR_faccessat || number == 439) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass() && is_root_sensitive_path(pathname)) {
            SLOGI("__syscall faccessat blocked: %s", pathname);
            errno = ENOENT;
            return -1;
        }
    }
    if (number == __NR_newfstatat) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass() && is_root_sensitive_path(pathname)) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == __NR_openat) {
        const char *pathname = (const char *)args[1];
        if (!proc_io_bypass()) {
            if (is_root_sensitive_path(pathname)) {
                SLOGI("__syscall openat blocked: %s", pathname);
                errno = ENOENT;
                return -1;
            }
            ProcPathKind kind = classify_proc_path(pathname);
            if (kind == ProcPathKind::MEM) {
                errno = ENOENT;
                return -1;
            }
            if (kind != ProcPathKind::NONE && !g_proc_cache_dir.empty()) {
                std::string red = write_filtered_proc_temp(kind);
                if (!red.empty()) {
                    return syscall(number, args[0], (long)red.c_str(), args[2], args[3]);
                }
                errno = ENOENT;
                return -1;
            }
        }
    }
#endif

    return syscall(number, args[0], args[1], args[2], args[3], args[4], args[5]);
}

static int hooked_close(int fd) {
    SHADOWHOOK_STACK_SCOPE();
    { std::lock_guard<std::mutex> lock(g_proc_fds_mutex); g_proc_fds.erase(fd); }
    g_read_line_buf.erase(fd);
    return SHADOWHOOK_CALL_PREV(hooked_close, fd);
}

static FILE *hooked_fopen(const char *p, const char *m) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_fopen, p, m);
    if (is_root_sensitive_path(p)) { errno = ENOENT; return nullptr; }
    ProcPathKind kind = classify_proc_path(p); if (kind == ProcPathKind::MEM) { errno = ENOENT; return nullptr; }
    if (kind != ProcPathKind::NONE) {
        std::string red = write_filtered_proc_temp(kind);
        if (!red.empty()) {
            ProcIoBypassGuard g;
            FILE *fp = fopen(red.c_str(), m);
            if (fp) { std::lock_guard<std::mutex> lock(g_proc_file_ptrs_mutex); g_proc_file_ptrs.insert(fp); }
            return fp;
        }
        errno = ENOENT; return nullptr;
    }
    return SHADOWHOOK_CALL_PREV(hooked_fopen, p, m);
}

static int hooked_open(const char *p, int f, ...) {
    SHADOWHOOK_STACK_SCOPE(); mode_t mode = 0; if (f & O_CREAT) { va_list ap; va_start(ap, f); mode = va_arg(ap, int); va_end(ap); }
    if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_open, p, f, mode);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    ProcPathKind kind = classify_proc_path(p); if (kind == ProcPathKind::MEM) return deny_root_path_errno();
    if (kind != ProcPathKind::NONE) {
        std::string red = write_filtered_proc_temp(kind);
        if (!red.empty()) {
            ProcIoBypassGuard g;
            int fd = orig_open ? orig_open(red.c_str(), f, mode) : open(red.c_str(), f, mode);
            if (fd >= 0) { std::lock_guard<std::mutex> lock(g_proc_fds_mutex); g_proc_fds.insert(fd); }
            return fd;
        }
        return deny_root_path_errno();
    }
    return SHADOWHOOK_CALL_PREV(hooked_open, p, f, mode);
}

static int hooked_openat(int d, const char *p, int f, ...) {
    SHADOWHOOK_STACK_SCOPE(); mode_t mode = 0; if (f & O_CREAT) { va_list ap; va_start(ap, f); mode = va_arg(ap, int); va_end(ap); }
    if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_openat, d, p, f, mode);
    if (is_root_sensitive_path(p)) return deny_root_path_errno();
    ProcPathKind kind = classify_proc_path(p); if (kind == ProcPathKind::MEM) return deny_root_path_errno();
    if (kind != ProcPathKind::NONE) {
        std::string red = write_filtered_proc_temp(kind);
        if (!red.empty()) {
            ProcIoBypassGuard g;
            int fd = orig_openat ? orig_openat(d, red.c_str(), f, mode) : openat(d, red.c_str(), f, mode);
            if (fd >= 0) { std::lock_guard<std::mutex> lock(g_proc_fds_mutex); g_proc_fds.insert(fd); }
            return fd;
        }
        return deny_root_path_errno();
    }
    return SHADOWHOOK_CALL_PREV(hooked_openat, d, p, f, mode);
}

static bool is_sensitive_dlsym_name(const char *s) { if (!s) return false; std::string l = to_lower_ascii(s); return l.find("shadowhook") != std::string::npos || l.find("xposedbridge") != std::string::npos || l.find("lsposed") != std::string::npos; }

static void *(*orig_android_dlopen_ext)(const char *, int, const void *) = nullptr;
static void *hooked_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    // Intercept android_dlopen_ext to ensure detection app can load libcutils.so
    // and find property_get via dlsym
    void *handle = orig_android_dlopen_ext ? orig_android_dlopen_ext(filename, flag, extinfo) : nullptr;
    if (handle && filename && strstr(filename, "libcutils")) {
        SLOGI("android_dlopen_ext: loaded libcutils.so");
    }
    return handle;
}

static bool host_shadowhook_present() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return false;
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("libshadowhook.so") != std::string::npos &&
            line.find("yumyhook_native") == std::string::npos) {
            SLOGI("host shadowhook already mapped (but not usable from app namespace)");
            return true;
        }
    }
    return false;
}

static bool ensure_shadowhook_engine() {
    if (g_shadowhook_engine_ready.load()) return true;
    // Always call shadowhook_init — host's instance is in a different namespace
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) == 0) {
        g_shadowhook_engine_ready.store(true);
        SLOGI("shadowhook_init ok mode=SHARED");
        return true;
    }
    LOGE("shadowhook_init failed: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    return false;
}

static bool proc_stealth_critical_ready() {
    bool path_block = orig_access != nullptr || orig_faccessat != nullptr || orig___faccessat != nullptr;
    bool proc_read = orig_fopen != nullptr || orig_open != nullptr;
    return path_block && proc_read;
}

static bool install_proc_stealth_hooks() {
    if (g_proc_stealth_installed && proc_stealth_critical_ready()) return true;
    if (g_proc_cache_dir.empty()) {
        SLOGE("proc stealth missing cache dir");
        return false;
    }
    if (!ensure_shadowhook_engine()) return false;
    void *orig = nullptr;
    bool any_success = false;
    if (hook_libc_sym("open", (void *)hooked_open, &orig, "open")) { orig_open = (int (*)(const char *, int, ...))orig; any_success = true; }
    if (hook_libc_sym("openat", (void *)hooked_openat, &orig, "openat")) { orig_openat = (int (*)(int, const char *, int, ...))orig; any_success = true; }
    if (hook_libc_sym("fopen", (void *)hooked_fopen, &orig, "fopen")) { orig_fopen = (FILE *(*)(const char *, const char *))orig; any_success = true; }
    if (hook_libc_sym("access", (void *)hooked_access, &orig, "access")) { orig_access = (int (*)(const char *, int))orig; any_success = true; }
    if (hook_libc_sym("faccessat", (void *)hooked_faccessat, &orig, "faccessat")) { orig_faccessat = (int (*)(int, const char *, int, int))orig; any_success = true; }
    hook_libc_sym("faccessat2", (void *)hooked_faccessat2, &orig, "faccessat2");
    if (hook_libc_sym("stat", (void *)hooked_stat, &orig, "stat")) { orig_stat = (int (*)(const char *, struct stat *))orig; any_success = true; }
    if (hook_libc_sym("lstat", (void *)hooked_lstat, &orig, "lstat")) { orig_lstat = (int (*)(const char *, struct stat *))orig; any_success = true; }
    hook_libc_sym("fstatat", (void *)hooked_fstatat, &orig, "fstatat");
    hook_libc_sym("statx", (void *)hooked_statx, &orig, "statx");
    if (hook_libc_sym("readlink", (void *)hooked_readlink, &orig, "readlink")) { orig_readlink = (ssize_t (*)(const char *, char *, size_t))orig; any_success = true; }
    hook_libc_sym("readlinkat", (void *)hooked_readlinkat, &orig, "readlinkat");
    hook_libc_sym("__faccessat", (void *)hooked___faccessat, &orig, "__faccessat"); orig___faccessat = (int (*)(int, const char *, int, int))orig;
    hook_libc_sym("__access", (void *)hooked_access, &orig, "__access");
    hook_libc_sym("__faccessat2", (void *)hooked_faccessat2, &orig, "__faccessat2");
    if (hook_libc_sym("fgets", (void *)hooked_fgets, &orig, "fgets")) { orig_fgets = (char *(*)(char *, int, FILE *))orig; any_success = true; }
    if (hook_libc_sym("read", (void *)hooked_read, &orig, "read")) { orig_read = (ssize_t (*)(int, void *, size_t))orig; any_success = true; }
    if (hook_libc_sym("close", (void *)hooked_close, &orig, "close")) { orig_close = (int (*)(int))orig; any_success = true; }

    hook_sym("libdl.so", "dlsym", (void *)hooked_dlsym, &orig, "dlsym"); orig_dlsym = (void *(*)(void *, const char *))orig;
    // Hook android_dlopen_ext to intercept detection app's library loading
    hook_sym("libdl.so", "android_dlopen_ext", (void *)hooked_android_dlopen_ext, &orig, "android_dlopen_ext"); orig_android_dlopen_ext = (void *(*)(const char *, int, const void *))orig;
    g_proc_stealth_installed = any_success; return any_success;
}

static char *hooked_fgets(char *b, int s, FILE *f) {
    SHADOWHOOK_STACK_SCOPE(); if (proc_io_bypass()) return SHADOWHOOK_CALL_PREV(hooked_fgets, b, s, f);
    bool is_proc_fp;
    { std::lock_guard<std::mutex> lock(g_proc_file_ptrs_mutex); is_proc_fp = g_proc_file_ptrs.count(f) > 0; }
    if (!is_proc_fp) return SHADOWHOOK_CALL_PREV(hooked_fgets, b, s, f);
    while (true) { char *l = SHADOWHOOK_CALL_PREV(hooked_fgets, b, s, f); if (!l) return nullptr; if (!proc_line_should_hide(l)) return l; if (feof(f)) return nullptr; }
}
static ssize_t hooked_read(int fd, void *b, size_t c) {
    SHADOWHOOK_STACK_SCOPE();
    ssize_t n = SHADOWHOOK_CALL_PREV(hooked_read, fd, b, c);
    if (n <= 0 || proc_io_bypass()) return n;

    bool is_proc_fd;
    { std::lock_guard<std::mutex> lock(g_proc_fds_mutex); is_proc_fd = g_proc_fds.count(fd) > 0; }
    if (!is_proc_fd) return n;

    auto &buf = g_read_line_buf[fd];
    buf.append((char *)b, static_cast<size_t>(n));

    std::string output;
    size_t pos = 0;
    while (true) {
        size_t nl = buf.find('\n', pos);
        if (nl == std::string::npos) break;
        std::string line = buf.substr(pos, nl - pos + 1);
        if (!proc_line_should_hide(line)) {
            output += line;
        }
        pos = nl + 1;
    }
    buf.erase(0, pos);

    if (output.empty()) {
        return 0;
    }

    size_t copy_len = std::min(output.size(), c);
    memcpy(b, output.c_str(), copy_len);
    return static_cast<ssize_t>(copy_len);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeInstallPropertyHook(JNIEnv *env, jclass, jboolean libc_only, jstring j_cache_dir) {
    g_libc_only_mode = libc_only == JNI_TRUE;
    if (j_cache_dir) { const char *c = env->GetStringUTFChars(j_cache_dir, nullptr); if (c) { g_proc_cache_dir = c; env->ReleaseStringUTFChars(j_cache_dir, c); } }
    if (g_hook_installed) return JNI_TRUE;
    if (!ensure_shadowhook_engine()) return JNI_FALSE;
    void *orig = nullptr;
    hook_libc_sym("__system_property_get", (void *)hooked_system_property_get, &orig, "__system_property_get");
    hook_sym("libcutils.so", "property_get", (void *)hooked_property_get, &orig, "property_get"); orig_property_get_fn = (int (*)(const char *, char *, const char *))orig;
    hook_libc_sym("__system_property_read", (void *)hooked_system_property_read, &orig, "__system_property_read");
    hook_libc_sym("__system_property_read_callback", (void *)hooked_read_callback, &orig, "__system_property_read_callback"); orig_read_callback = (void (*)(const prop_info *, void (*)(void *, const char *, const char *, uint32_t), void *))orig;
    g_hook_installed = true; install_proc_stealth_hooks(); return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeRetryDeferredHooks(JNIEnv *, jclass) { return install_proc_stealth_hooks(); }
extern "C" JNIEXPORT void JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeSetSpoofActive(JNIEnv *, jclass, jboolean active) { g_spoof_active.store(active == JNI_TRUE); }
extern "C" JNIEXPORT jstring JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeProbeProperty(JNIEnv *env, jclass, jstring jname) { const char *n = env->GetStringUTFChars(jname, nullptr); char v[PROP_VALUE_MAX]={0}; __system_property_get(n, v); env->ReleaseStringUTFChars(jname, n); return env->NewStringUTF(v); }
extern "C" JNIEXPORT jstring JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeProbeLibcutilsProperty(JNIEnv *env, jclass, jstring jname) { const char *n = env->GetStringUTFChars(jname, nullptr); char v[PROP_VALUE_MAX]={0}; yh_dlsym_property_get(n, v, ""); env->ReleaseStringUTFChars(jname, n); return env->NewStringUTF(v); }
extern "C" JNIEXPORT jstring JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeHookStats(JNIEnv *env, jclass) { char b[128]; snprintf(b, 128, "hits=%u spoofs=%u props=%zu hooks=%d proc=%d", g_get_hits.load(), g_get_spoofs.load(), g_props.size(), g_hook_installed, g_proc_stealth_installed); return env->NewStringUTF(b); }
extern "C" JNIEXPORT void JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeUpdateProperties(JNIEnv *env, jclass, jobjectArray keys, jobjectArray values) {
    jsize count = env->GetArrayLength(keys); std::unordered_map<std::string, std::string> next; next.reserve(count);
    for (jsize i=0; i<count; i++) {
        jstring k = (jstring)env->GetObjectArrayElement(keys, i); jstring v = (jstring)env->GetObjectArrayElement(values, i);
        const char *kc = env->GetStringUTFChars(k, nullptr); const char *vc = env->GetStringUTFChars(v, nullptr);
        if (kc && vc) next[kc] = vc; if (kc) env->ReleaseStringUTFChars(k, kc); if (vc) env->ReleaseStringUTFChars(v, vc);
        env->DeleteLocalRef(k); env->DeleteLocalRef(v);
    }
    pthread_rwlock_wrlock(&g_lock); g_props.swap(next); pthread_rwlock_unlock(&g_lock);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yumito_yumyhook_xposed_channel_NativeJni_nativeInstallProcStealth(JNIEnv *env, jclass, jstring jCacheDir) {
    const char *c = env->GetStringUTFChars(jCacheDir, nullptr); if (c) { g_proc_cache_dir = c; env->ReleaseStringUTFChars(jCacheDir, c); }
    return install_proc_stealth_hooks();
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *res) {
    ensure_shadowhook_engine();
    return JNI_VERSION_1_6;
}
