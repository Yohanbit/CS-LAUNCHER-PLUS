//
// Created by maks on 05.06.2023.
//
#include "nsbypass.h"
#include <dlfcn.h>
#include <android/dlext.h>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/user.h>
#include <string.h>
#include <stdio.h>
#include <linux/limits.h>
#include <errno.h>
#include <unistd.h>
#include <asm/unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <elf.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <pthread.h>

/* upper 6 bits of an ARM64 instruction are the instruction name */
#define OP_MS 0b11111100000000000000000000000000
/* Branch Label instruction opcode and immediate mask */
#define BL_OP 0b10010100000000000000000000000000
#define BL_IM 0b00000011111111111111111111111111
/* Library search path */
#ifndef SEARCH_PATH
#define SEARCH_PATH "/system/lib64"
#endif
#define ELF_EHDR Elf64_Ehdr
#define ELF_SHDR Elf64_Shdr
#define ELF_HALF Elf64_Half
#define ELF_XWORD Elf64_Xword
#define ELF_DYN Elf64_Dyn

//#define ADRENO_POSSIBLE

typedef void* (*loader_dlopen_t)(const char* filename, int flags, const void* caller_addr);

typedef struct android_namespace_t* (*ld_android_create_namespace_t)(
        const char* name, const char* ld_library_path, const char* default_library_path, uint64_t type,
        const char* permitted_when_isolated_path, struct android_namespace_t* parent, const void* caller_addr);

typedef bool (*ld_android_link_namespaces_t)(struct android_namespace_t* namespace_from,
                                              struct android_namespace_t* namespace_to,
                                              const char* shared_libs_sonames);

static ld_android_create_namespace_t android_create_namespace;
static struct android_namespace_t* driver_namespace;
static void *namespace_linker_handle; /* root for retained private function pointers */
static bool namespace_failed;

struct android_namespace_t* local_android_create_namespace(
        const char* name, const char* ld_library_path, const char* default_library_path, uint64_t type,
        const char* permitted_when_isolated_path, struct android_namespace_t* parent) {
    void* caller = __builtin_return_address(0);
    if (android_create_namespace == NULL) return NULL;
    return android_create_namespace(name, ld_library_path, default_library_path, type, permitted_when_isolated_path, parent, caller);
}

// Find the first "branch to label" function in the function provided in func_start
static void* find_branch_label(void* func_start) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (func_start == NULL || page_size <= 0 || ((uintptr_t)func_start & 3u) != 0) return NULL;
    // round down the pointer to get the start of the function's page
    void* func_page_start = (void*)(((uintptr_t)func_start) & ~(page_size-1));
    // remap to r-x to bypass "execute only" protections on MIUI
    if (mprotect(func_page_start, (size_t)page_size, PROT_READ | PROT_EXEC) != 0) return NULL;
    uint32_t* bl_addr = func_start;
    /* The Android wrapper should contain its BL in this page. Do not scan
     * into an unowned/unmapped following page if a vendor changed its ABI. */
    uintptr_t page_end = (uintptr_t)func_page_start + (uintptr_t)page_size;
    while ((uintptr_t)bl_addr <= page_end - sizeof(*bl_addr)) {
        uint32_t instruction = *bl_addr;
        if ((instruction & OP_MS) == BL_OP) {
            int64_t displacement = (int64_t)(instruction & BL_IM);
            if (displacement & (1ll << 25)) displacement -= 1ll << 26;
            return (void *)((uintptr_t)bl_addr + displacement * 4);
        }
        ++bl_addr;
    }
    // offset the address to find where the "branch to label" instrunction
    // points to.
    return NULL;
}

bool linker_ns_load(const char* lib_search_path) {
#ifndef ADRENO_POSSIBLE
    (void)lib_search_path;
    return false;
#else
    if (driver_namespace != NULL) return true;
    if (namespace_failed) return false;
    if (lib_search_path == NULL || *lib_search_path == '\0') return false;
    loader_dlopen_t loader_dlopen = find_branch_label(&dlopen);
    if (loader_dlopen == NULL) return false;
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    void *loader_page = (void *)((uintptr_t)loader_dlopen & ~((uintptr_t)page_size - 1u));
    /* Remove execute-only/BTI protection only on a page-aligned range. Never
     * request writable executable loader memory. Failure uses regular Vulkan. */
    if (mprotect(loader_page, (size_t)page_size, PROT_READ | PROT_EXEC) != 0) return false;
    void* ld_android_handle = loader_dlopen("ld-android.so", RTLD_LAZY, &dlopen);
    if(ld_android_handle == NULL) {
        return false;
    }
    // load the two functions we need
    android_create_namespace = dlsym(ld_android_handle, "__loader_android_create_namespace");
    ld_android_link_namespaces_t android_link_namespaces = dlsym(ld_android_handle, "__loader_android_link_namespaces");
    if(android_create_namespace == NULL || android_link_namespaces == NULL) {
        dlclose(ld_android_handle);
        return false;
    }
    // assemble the full path search path
    char full_path[PATH_MAX];
    int length = snprintf(full_path, sizeof(full_path), "%s:%s", SEARCH_PATH, lib_search_path);
    if (length < 0 || (size_t)length >= sizeof(full_path)) { dlclose(ld_android_handle); return false; }
    driver_namespace = local_android_create_namespace("pojav-driver",
                                                      full_path,
                                                      full_path,
                                                      3 /* TYPE_SHAFED | TYPE_ISOLATED */,
                                                      "/system/:/data/:/vendor/:/apex/", NULL);
    if (driver_namespace == NULL) { dlclose(ld_android_handle); return false; }
    // THIS IS VERY IMPORTANT and how I trolled FoldCraft:
    // You need to link the new driver_namespace with NULL and and add ld-android.so
    // in the link list, to pass through the driver_namespace correctly.
    // Not doing this fucks up internal __loader symbol lookup
    // inside the new driver_namespace, thus breaking it on
    // a lot of android versions
    // FoldCraft got trolled because they copied the
    // old broken code verbatim and didn't even test it thoroughly
    bool linked = android_link_namespaces(driver_namespace, NULL, "ld-android.so");
    // Also establish links to use the libnativeloader(_lazy).so libraries
    // from the global namespace. This is a workaround for an EMUI issue where
    // the newly loaded libnativeloader_lazy for some unknown reason links
    // to itself and causes a deadlock when loading the vulkan driver.
    /* These OEM workaround libraries may be absent; only the loader link is
     * mandatory. The namespace API provides no safe namespace destroy ABI. */
    android_link_namespaces(driver_namespace, NULL, "libnativeloader.so");
    android_link_namespaces(driver_namespace, NULL, "libnativeloader_lazy.so");
    if (!linked) {
        /* No private destroy-namespace ABI exists. Do not accumulate one
         * unusable namespace on every retry after this irreversible failure. */
        namespace_failed = true;
        driver_namespace = NULL;
        dlclose(ld_android_handle);
        return false;
    }
    namespace_linker_handle = ld_android_handle;
    return true;
#endif
}

void* linker_ns_dlopen(const char* name, int flag) {
#ifndef ADRENO_POSSIBLE
    (void)name; (void)flag;
    return NULL;
#else
    if (name == NULL || driver_namespace == NULL) return NULL;
    android_dlextinfo dlextinfo = {0};
    dlextinfo.flags = ANDROID_DLEXT_USE_NAMESPACE;
    dlextinfo.library_namespace = driver_namespace;
    return android_dlopen_ext(name, flag, &dlextinfo);
#endif
}

static bool elf_range(uint64_t offset, uint64_t size, uint64_t length) {
    return offset <= length && size <= length - offset;
}

/* Borrow BOTH descriptors. The caller closes them on every path; this helper
 * owns only its mapping. Parse via memcpy: a malformed ELF may be unaligned. */
bool patch_elf_soname(int patchfd, int realfd, uint16_t patchid) {
    struct stat st;
    if (patchid > 0xfff || fstat(realfd, &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_size < (off_t)sizeof(ELF_EHDR) || st.st_size > 64 * 1024 * 1024) return false;
    size_t length = (size_t)st.st_size;
    if (ftruncate64(patchfd, st.st_size) != 0) return false;
    char *target = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, patchfd, 0);
    if (target == MAP_FAILED) return false;
    bool success = false;
    size_t copied = 0;
    while (copied < length) {
        ssize_t n = pread(realfd, target + copied, length - copied, (off_t)copied);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) goto done;
        copied += (size_t)n;
    }
    ELF_EHDR ehdr;
    memcpy(&ehdr, target, sizeof(ehdr));
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0 || ehdr.e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr.e_ident[EI_DATA] != ELFDATA2LSB || ehdr.e_ident[EI_VERSION] != EV_CURRENT ||
        ehdr.e_version != EV_CURRENT || ehdr.e_machine != EM_AARCH64 ||
        ehdr.e_type != ET_DYN || ehdr.e_ehsize != sizeof(ELF_EHDR) ||
        ehdr.e_shentsize != sizeof(ELF_SHDR) || ehdr.e_shnum == 0 ||
        !elf_range(ehdr.e_shoff, (uint64_t)ehdr.e_shnum * sizeof(ELF_SHDR), length)) goto done;
    for (ELF_HALF i = 0; i < ehdr.e_shnum; ++i) {
        ELF_SHDR hdr;
        memcpy(&hdr, target + ehdr.e_shoff + (size_t)i * sizeof(hdr), sizeof(hdr));
        if (hdr.sh_type != SHT_DYNAMIC) continue;
        if (hdr.sh_link >= ehdr.e_shnum || hdr.sh_entsize != sizeof(ELF_DYN) ||
            hdr.sh_size % sizeof(ELF_DYN) != 0 || !elf_range(hdr.sh_offset, hdr.sh_size, length)) goto done;
        ELF_SHDR strings;
        memcpy(&strings, target + ehdr.e_shoff + (size_t)hdr.sh_link * sizeof(strings), sizeof(strings));
        if (strings.sh_type != SHT_STRTAB || !elf_range(strings.sh_offset, strings.sh_size, length)) goto done;
        for (uint64_t k = 0; k < hdr.sh_size / sizeof(ELF_DYN); ++k) {
            ELF_DYN dyn;
            memcpy(&dyn, target + hdr.sh_offset + k * sizeof(dyn), sizeof(dyn));
            if (dyn.d_tag == DT_NULL) break;
            if (dyn.d_tag != DT_SONAME) continue;
            uint64_t offset = dyn.d_un.d_val;
            if (offset >= strings.sh_size) goto done;
            char *soname = target + strings.sh_offset + offset;
            size_t remaining = (size_t)(strings.sh_size - offset);
            char *end = memchr(soname, '\0', remaining);
            if (end == NULL || end - soname < 3) goto done;
            char prefix[4];
            snprintf(prefix, sizeof(prefix), "%03x", patchid);
            memcpy(soname, prefix, 3);
            success = msync(target, length, MS_SYNC) == 0;
            goto done;
        }
    }
done:
    if (munmap(target, length) != 0) success = false;
    return success;
}

/* A successful unique loader needs its /proc/self/fd pathname alive so LWJGL
 * can retain the SAME library in the SAME namespace. Explicit process-root
 * ownership, not an untracked per-query FD leak. Rejected roots are forgotten. */
struct unique_loader_root { void *handle; int fd; struct unique_loader_root *next; };
static struct unique_loader_root *unique_loader_roots;
static pthread_mutex_t unique_root_mutex = PTHREAD_MUTEX_INITIALIZER;

void linker_ns_forget_unique(void *handle) {
    pthread_mutex_lock(&unique_root_mutex);
    struct unique_loader_root **entry = &unique_loader_roots;
    while (*entry != NULL) {
        if ((*entry)->handle == handle) {
            struct unique_loader_root *root = *entry;
            *entry = root->next;
            close(root->fd);
            free(root);
            break;
        }
        entry = &(*entry)->next;
    }
    pthread_mutex_unlock(&unique_root_mutex);
}

void* linker_ns_dlopen_unique(const char* tmpdir, const char* name, int flags) {
#ifndef ADRENO_POSSIBLE
    (void)tmpdir; (void)name; (void)flags;
    return NULL;
#else
    if (tmpdir == NULL || name == NULL || driver_namespace == NULL ||
        *tmpdir == '\0' || *name == '\0' || strchr(name, '/') != NULL) return NULL;
    char pathbuf[PATH_MAX];
    static uint32_t next_patch_id;
    uint32_t patch_id = __atomic_fetch_add(&next_patch_id, 1, __ATOMIC_RELAXED);
    if (patch_id > 0xfff) return NULL; /* Never reuse a patched SONAME in process. */
    int length = snprintf(pathbuf, sizeof(pathbuf), "%s/pojav-loader-XXXXXX", tmpdir);
    if (length < 0 || (size_t)length >= sizeof(pathbuf)) return NULL;
    int patch_fd = mkstemp(pathbuf);
    if(patch_fd == -1) return NULL;
    /* Unlink immediately: only our descriptor owns this temporary file. */
    if (unlink(pathbuf) != 0) { close(patch_fd); return NULL; }
    if (fcntl(patch_fd, F_SETFD, FD_CLOEXEC) != 0) { close(patch_fd); return NULL; }
    length = snprintf(pathbuf, sizeof(pathbuf), "%s/%s", SEARCH_PATH, name);
    if (length < 0 || (size_t)length >= sizeof(pathbuf)) { close(patch_fd); return NULL; }
    int real_fd = open(pathbuf, O_RDONLY | O_CLOEXEC);
    if(real_fd == -1) {
        close(patch_fd);
        return NULL;
    }
    bool patched = patch_elf_soname(patch_fd, real_fd, (uint16_t)patch_id);
    close(real_fd);
    if(!patched) {
        close(patch_fd);
        return NULL;
    }
    struct unique_loader_root *root = calloc(1, sizeof(*root));
    if (root == NULL) { close(patch_fd); return NULL; }
    android_dlextinfo extinfo = {0};
    extinfo.flags = ANDROID_DLEXT_USE_NAMESPACE | ANDROID_DLEXT_USE_LIBRARY_FD;
    extinfo.library_fd = patch_fd;
    extinfo.library_namespace = driver_namespace;
    snprintf(pathbuf, PATH_MAX, "/proc/self/fd/%d", patch_fd);
    void *handle = android_dlopen_ext(pathbuf, flags, &extinfo);
    if (handle == NULL) { close(patch_fd); free(root); return NULL; }
    root->handle = handle; root->fd = patch_fd;
    pthread_mutex_lock(&unique_root_mutex);
    root->next = unique_loader_roots; unique_loader_roots = root;
    pthread_mutex_unlock(&unique_root_mutex);
    return handle;
#endif
}
