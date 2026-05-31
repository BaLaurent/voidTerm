/*
 * voidterm_remap.c — LD_PRELOAD library that remaps /data/data/com.termux/
 * to /data/data/com.voidterm/ in all file-access syscall wrappers.
 *
 * Termux binaries (bash, dpkg, apt, etc.) are compiled with
 * --prefix=/data/data/com.termux/files/usr, so paths like
 * /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d are hardcoded
 * in ELF .rodata sections. This library transparently redirects those
 * accesses to our package's data directory.
 *
 * IMPORTANT: Hooks may be called BEFORE __attribute__((constructor)) runs,
 * because libc's __libc_preinit_impl calls access()/open() during its own
 * initialization (e.g. to seed arc4random from /dev/urandom). On some devices
 * (e.g. MediaTek), this preinit runs before our constructor.  All hooks must
 * therefore guard against NULL real_* pointers and fall back to raw syscalls.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define OLD_PREFIX "/data/data/com.termux/"
#define NEW_PREFIX "/data/data/com.voidterm/"
#define OLD_PREFIX_LEN 22  /* strlen("/data/data/com.termux/") */
#define NEW_PREFIX_LEN 23  /* strlen("/data/data/com.voidterm/") */
#define PATH_MAX_BUF PATH_MAX

/* Remap path if it starts with the old prefix. Returns a stack buffer or the original pointer. */
static const char* remap(const char* path, char buf[PATH_MAX_BUF]) {
    if (path == NULL) return path;
    if (strncmp(path, OLD_PREFIX, OLD_PREFIX_LEN) != 0) return path;

    size_t tail_len = strlen(path + OLD_PREFIX_LEN);
    if (NEW_PREFIX_LEN + tail_len >= PATH_MAX_BUF) return path; /* too long, skip */

    memcpy(buf, NEW_PREFIX, NEW_PREFIX_LEN);
    memcpy(buf + NEW_PREFIX_LEN, path + OLD_PREFIX_LEN, tail_len + 1);
    return buf;
}

/* --- Real function pointers, resolved once at library load --- */

static int     (*real_open)(const char*, int, ...);
static int     (*real_openat)(int, const char*, int, ...);
static DIR*    (*real_opendir)(const char*);
static int     (*real_stat)(const char*, struct stat*);
static int     (*real_lstat)(const char*, struct stat*);
static int     (*real_access)(const char*, int);
static int     (*real_faccessat)(int, const char*, int, int);
static ssize_t (*real_readlink)(const char*, char*, size_t);
static FILE*   (*real_fopen)(const char*, const char*);
static int     (*real_rename)(const char*, const char*);
static int     (*real_unlink)(const char*);
static int     (*real_mkdir)(const char*, mode_t);
static int     (*real_chdir)(const char*);
static char*   (*real_realpath)(const char*, char*);

__attribute__((constructor))
static void init_hooks(void) {
    real_open      = dlsym(RTLD_NEXT, "open");
    real_openat    = dlsym(RTLD_NEXT, "openat");
    real_opendir   = dlsym(RTLD_NEXT, "opendir");
    real_stat      = dlsym(RTLD_NEXT, "stat");
    real_lstat     = dlsym(RTLD_NEXT, "lstat");
    real_access    = dlsym(RTLD_NEXT, "access");
    real_faccessat = dlsym(RTLD_NEXT, "faccessat");
    real_readlink  = dlsym(RTLD_NEXT, "readlink");
    real_fopen     = dlsym(RTLD_NEXT, "fopen");
    real_rename    = dlsym(RTLD_NEXT, "rename");
    real_unlink    = dlsym(RTLD_NEXT, "unlink");
    real_mkdir     = dlsym(RTLD_NEXT, "mkdir");
    real_chdir     = dlsym(RTLD_NEXT, "chdir");
    real_realpath  = dlsym(RTLD_NEXT, "realpath");
}

/* --- Hooked functions ---
 *
 * Each hook guards against real_* being NULL (constructor not yet called).
 * For syscall-backed functions, the fallback is a raw syscall via syscall().
 * For libc-only functions (fopen, opendir, realpath), the fallback returns
 * an error, since these are never called during libc preinit.
 */

int open(const char* path, int flags, ...) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);

    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }

    if (__builtin_expect(real_open != NULL, 1))
        return (flags & (O_CREAT | O_TMPFILE)) ? real_open(path, flags, mode) : real_open(path, flags);
    return syscall(SYS_openat, AT_FDCWD, path, flags, mode);
}

int openat(int dirfd, const char* path, int flags, ...) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);

    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }

    if (__builtin_expect(real_openat != NULL, 1))
        return (flags & (O_CREAT | O_TMPFILE)) ? real_openat(dirfd, path, flags, mode) : real_openat(dirfd, path, flags);
    return syscall(SYS_openat, dirfd, path, flags, mode);
}

DIR* opendir(const char* path) {
    char buf[PATH_MAX_BUF];
    if (__builtin_expect(real_opendir != NULL, 1))
        return real_opendir(remap(path, buf));
    errno = ENOSYS;
    return NULL;
}

int stat(const char* path, struct stat* sb) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_stat != NULL, 1))
        return real_stat(path, sb);
    return syscall(SYS_newfstatat, AT_FDCWD, path, sb, 0);
}

int lstat(const char* path, struct stat* sb) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_lstat != NULL, 1))
        return real_lstat(path, sb);
    return syscall(SYS_newfstatat, AT_FDCWD, path, sb, AT_SYMLINK_NOFOLLOW);
}

int access(const char* path, int mode) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_access != NULL, 1))
        return real_access(path, mode);
    return syscall(SYS_faccessat, AT_FDCWD, path, mode, 0);
}

int faccessat(int dirfd, const char* path, int mode, int flags) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_faccessat != NULL, 1))
        return real_faccessat(dirfd, path, mode, flags);
    return syscall(SYS_faccessat, dirfd, path, mode, flags);
}

ssize_t readlink(const char* path, char* buf_out, size_t bufsiz) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_readlink != NULL, 1))
        return real_readlink(path, buf_out, bufsiz);
    return syscall(SYS_readlinkat, AT_FDCWD, path, buf_out, bufsiz);
}

FILE* fopen(const char* path, const char* mode) {
    char buf[PATH_MAX_BUF];
    if (__builtin_expect(real_fopen != NULL, 1))
        return real_fopen(remap(path, buf), mode);
    errno = ENOSYS;
    return NULL;
}

int rename(const char* oldpath, const char* newpath) {
    char buf1[PATH_MAX_BUF], buf2[PATH_MAX_BUF];
    oldpath = remap(oldpath, buf1);
    newpath = remap(newpath, buf2);
    if (__builtin_expect(real_rename != NULL, 1))
        return real_rename(oldpath, newpath);
    return syscall(SYS_renameat, AT_FDCWD, oldpath, AT_FDCWD, newpath);
}

int unlink(const char* path) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_unlink != NULL, 1))
        return real_unlink(path);
    return syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}

int mkdir(const char* path, mode_t mode) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_mkdir != NULL, 1))
        return real_mkdir(path, mode);
    return syscall(SYS_mkdirat, AT_FDCWD, path, mode);
}

int chdir(const char* path) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);
    if (__builtin_expect(real_chdir != NULL, 1))
        return real_chdir(path);
    return syscall(SYS_chdir, path);
}

char* realpath(const char* path, char* resolved) {
    char buf[PATH_MAX_BUF];
    if (__builtin_expect(real_realpath != NULL, 1))
        return real_realpath(remap(path, buf), resolved);
    errno = ENOSYS;
    return NULL;
}
