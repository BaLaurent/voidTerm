/*
 * voidterm_remap.c — LD_PRELOAD library that remaps /data/data/com.termux/
 * to /data/data/com.voidterm/ in all file-access syscall wrappers.
 *
 * Termux binaries (bash, dpkg, apt, etc.) are compiled with
 * --prefix=/data/data/com.termux/files/usr, so paths like
 * /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d are hardcoded
 * in ELF .rodata sections. This library transparently redirects those
 * accesses to our package's data directory.
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

/* --- Hooked functions --- */

int open(const char* path, int flags, ...) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = va_arg(ap, int);
        va_end(ap);
        return real_open(path, flags, mode);
    }
    return real_open(path, flags);
}

int openat(int dirfd, const char* path, int flags, ...) {
    char buf[PATH_MAX_BUF];
    path = remap(path, buf);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode_t mode = va_arg(ap, int);
        va_end(ap);
        return real_openat(dirfd, path, flags, mode);
    }
    return real_openat(dirfd, path, flags);
}

DIR* opendir(const char* path) {
    char buf[PATH_MAX_BUF];
    return real_opendir(remap(path, buf));
}

int stat(const char* path, struct stat* sb) {
    char buf[PATH_MAX_BUF];
    return real_stat(remap(path, buf), sb);
}

int lstat(const char* path, struct stat* sb) {
    char buf[PATH_MAX_BUF];
    return real_lstat(remap(path, buf), sb);
}

int access(const char* path, int mode) {
    char buf[PATH_MAX_BUF];
    return real_access(remap(path, buf), mode);
}

int faccessat(int dirfd, const char* path, int mode, int flags) {
    char buf[PATH_MAX_BUF];
    return real_faccessat(dirfd, remap(path, buf), mode, flags);
}

ssize_t readlink(const char* path, char* buf_out, size_t bufsiz) {
    char buf[PATH_MAX_BUF];
    return real_readlink(remap(path, buf), buf_out, bufsiz);
}

FILE* fopen(const char* path, const char* mode) {
    char buf[PATH_MAX_BUF];
    return real_fopen(remap(path, buf), mode);
}

int rename(const char* oldpath, const char* newpath) {
    char buf1[PATH_MAX_BUF], buf2[PATH_MAX_BUF];
    return real_rename(remap(oldpath, buf1), remap(newpath, buf2));
}

int unlink(const char* path) {
    char buf[PATH_MAX_BUF];
    return real_unlink(remap(path, buf));
}

int mkdir(const char* path, mode_t mode) {
    char buf[PATH_MAX_BUF];
    return real_mkdir(remap(path, buf), mode);
}

int chdir(const char* path) {
    char buf[PATH_MAX_BUF];
    return real_chdir(remap(path, buf));
}

char* realpath(const char* path, char* resolved) {
    char buf[PATH_MAX_BUF];
    return real_realpath(remap(path, buf), resolved);
}
