#!/usr/bin/env python3
"""Run production Vulkan loader/readiness functions, not copied decision logic.

Graphics/JNI/dynamic-linker doubles do not establish physical GPU compatibility.
The companion shell test compiles the complete Android translation unit in CI.
"""
import os
import pathlib
import re
import subprocess
import sys
import tempfile

import native_graphics_round2_test as prior

ROOT = pathlib.Path(sys.argv[1]).resolve()
BRIDGE = ROOT / "app_pojavlauncher/src/main/jni/egl_bridge.c"


def function(path, name):
    source = path.read_text()
    match = re.search(r"(?m)^[^\n;{}]*\b" + re.escape(name) + r"\s*\([^;{}]*?\)\s*\{", source)
    if match is None:
        raise ValueError("production declaration not found: " + name)
    start = match.start()
    previous = source.rfind("\n", 0, start - 1) + 1
    if "JNICALL" in source[previous:start]:
        start = previous
    brace = match.end() - 1
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end] + "\n"

PREFIX = '#define _GNU_SOURCE\n' + prior.COMMON + r'''
#include <inttypes.h>
#include <pthread.h>
#include <errno.h>
#define ABI_COMPAT __attribute__((unused))
#define JNIEXPORT
#define JNICALL
typedef void JNIEnv;
typedef void *jclass;
typedef int jint;
typedef long long jlong;
static pthread_mutex_t vulkan_loader_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *vulkan_loader_handle;
static bool vulkan_loader_in_namespace;
static const char *vulkan_loader_path;
static int opens, closes, lookups, publications, clears, leases, refs, path_lookups;
static int open_fail, publish_fail, clear_fail, symbol_error;
static int lease_fail, lease_mismatch, path_fail;
static int device_api=28, turnip_enabled, turnip_calls;
static const char *missing;
static void *expected;
static const char *link_error;
static void dummy(void) {}
static int fake_setenv(const char *name, const char *value, int overwrite) {
    assert(!strcmp(name,"VULKAN_PTR")); publications++;
    if (publish_fail) { errno=ENOMEM; return -1; }
    return setenv(name,value,overwrite);
}
static int fake_unsetenv(const char *name) {
    assert(!strcmp(name,"VULKAN_PTR")); clears++;
    if (clear_fail) { errno=ENOMEM; return -1; }
    return unsetenv(name);
}
static void *fake_dlopen(const char *name, int flags) {
    assert(flags==(RTLD_NOW|RTLD_LOCAL));
    if (!strcmp(name,"/selected/libvulkan.so")) {
        leases++;
        if(lease_fail) { link_error="injected lease failure"; return NULL; }
        refs++;
        return lease_mismatch?(void *)3:expected;
    }
    assert(!strcmp(name,"libvulkan.so"));
    assert(getenv("VULKAN_PTR")==NULL); opens++;
    if(open_fail) { link_error="injected dependency/open failure"; return NULL; }
    refs++;
    return expected;
}
static void *fake_dlsym(void *handle, const char *name) {
    assert(handle==expected || handle==(void *)2); lookups++;
    if (missing && !strcmp(missing,name) &&
        (turnip_enabled != 2 || handle==(void *)2)) {
        link_error="injected missing global";
        return NULL;
    }
    if(symbol_error) link_error="injected nonnull symbol with dlerror";
    return (void *)dummy;
}
static char *fake_dlerror(void) {
    char *error=(char *)link_error; link_error=NULL; return error;
}
static int fake_dlclose(void *handle) {
    assert(handle==expected || handle==(void *)2 || handle==(void *)3);
    assert(refs>0); refs--; closes++; return 0;
}
static int fake_dladdr(void *entry, Dl_info *info) {
    assert(entry==(void *)dummy); path_lookups++;
    if(path_fail) return 0;
    info->dli_fname=vulkan_loader_in_namespace?"/selected/turnip.so":"/selected/libvulkan.so";
    return 1;
}
static void *linker_ns_dlopen(const char *name, int flags) {
    assert(vulkan_loader_in_namespace && !strcmp(name,"/selected/turnip.so"));
    assert(flags==(RTLD_NOW|RTLD_LOCAL)); leases++;
    if(lease_fail) return NULL;
    refs++; return lease_mismatch?(void *)3:(void *)2;
}
static int android_get_device_api_level(void) { return device_api; }
static void *load_turnip_vulkan(void) {
    turnip_calls++; if(turnip_enabled) refs++;
    return turnip_enabled?(void *)2:NULL;
}
#define ADRENO_POSSIBLE
#define setenv fake_setenv
#define unsetenv fake_unsetenv
#define dlopen fake_dlopen
#define dlsym fake_dlsym
#define dlerror fake_dlerror
#define dlclose fake_dlclose
#define dladdr fake_dladdr
'''

SUFFIX = r'''
#undef setenv
#undef unsetenv
#undef dlopen
#undef dlsym
#undef dlerror
#undef dlclose
#undef dladdr
static void reset(void) {
    vulkan_loader_handle=NULL;
    vulkan_loader_path=NULL; vulkan_loader_in_namespace=false;
    opens=closes=lookups=publications=clears=0;
    leases=refs=path_lookups=lease_fail=lease_mismatch=path_fail=0;
    open_fail=publish_fail=clear_fail=symbol_error=turnip_enabled=turnip_calls=0;
    missing=NULL; link_error="old unrelated dlerror"; device_api=28;
    expected=(void *)(uintptr_t)UINT64_C(0x1234567887654321);
    assert(unsetenv("VULKAN_PTR")==0);
}
static void assert_published(void *handle) {
    const char *value=getenv("VULKAN_PTR"); assert(value);
    assert((uintptr_t)strtoull(value,NULL,16)==(uintptr_t)handle);
}
static void *caller(void *unused) {
    (void)unused;
    for(int i=0;i<100;i++) {
        assert(maybe_load_vulkan()==expected);
        load_vulkan();
    }
    return NULL;
}
int main(void) {
    const char *stale[]={"", "0", "garbage", "-1", "1bad", "fffffffffffffffff", "1234trailing"};
    for(size_t i=0;i<sizeof(stale)/sizeof(stale[0]);i++) {
        reset(); assert(setenv("VULKAN_PTR",stale[i],1)==0);
        assert(maybe_load_vulkan()==expected); assert_published(expected);
        assert(opens==1&&lookups==4&&closes==0&&publications==1);
        load_vulkan(); assert(opens==1&&lookups==4&&publications==1);
        assert(Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(NULL,NULL)==(jlong)(uintptr_t)expected);
        assert(refs==2); fake_dlclose(expected); assert(refs==1);
    }
    reset(); open_fail=1; setenv("VULKAN_PTR","dead",1);
    assert(maybe_load_vulkan()==NULL); assert(!vulkan_loader_handle&&!getenv("VULKAN_PTR"));
    assert(opens==1&&!lookups&&!closes&&!publications);
    open_fail=0; assert(maybe_load_vulkan()==expected); assert(opens==2); assert_published(expected);
    const char *required[]={"vkGetInstanceProcAddr", "vkCreateInstance",
        "vkEnumerateInstanceExtensionProperties", "vkEnumerateInstanceLayerProperties"};
    for(size_t i=0;i<sizeof(required)/sizeof(required[0]);i++) {
        reset(); missing=required[i]; assert(!maybe_load_vulkan());
        assert(opens==1&&closes==1&&lookups==(int)i+1&&!publications);
        assert(!vulkan_loader_handle&&!getenv("VULKAN_PTR"));
        missing=NULL; assert(maybe_load_vulkan()==expected); assert(opens==2&&closes==1);
    }
    reset(); symbol_error=1; assert(!maybe_load_vulkan()); assert(closes==1&&!publications);
    reset(); clear_fail=1; setenv("VULKAN_PTR","dead",1);
    assert(!maybe_load_vulkan()); assert(!opens&&!lookups&&!closes);
    clear_fail=0; assert(maybe_load_vulkan()==expected); assert(opens==1);
    reset(); publish_fail=1; assert(!maybe_load_vulkan());
    assert(opens==1&&closes==1&&!vulkan_loader_handle&&!getenv("VULKAN_PTR"));
    publish_fail=0; assert(maybe_load_vulkan()==expected); assert(opens==2&&closes==1);
    /* Never unload a cached handle on environment publication failure. */
    reset(); assert(maybe_load_vulkan()==expected); unsetenv("VULKAN_PTR"); publish_fail=1;
    assert(!maybe_load_vulkan()); assert(opens==1&&!closes&&vulkan_loader_handle==expected);
    assert(!getenv("VULKAN_PTR")); publish_fail=0;
    assert(maybe_load_vulkan()==expected); assert(opens==1&&!closes); assert_published(expected);
    setenv("VULKAN_PTR","forged",1); assert(maybe_load_vulkan()==expected); assert_published(expected);
    /* Existing optional Adreno selection remains optional, not a Mali hack. */
    reset(); device_api=27; turnip_enabled=1;
    assert(maybe_load_vulkan()==expected); assert(!turnip_calls&&opens==1);
    reset(); turnip_enabled=1; assert(maybe_load_vulkan()==(void *)2);
    assert(!opens&&!closes&&lookups==4); assert_published((void *)2);
    assert(acquire_vulkan_loader()==(void *)2); assert(refs==2&&leases==1);
    fake_dlclose((void *)2); assert(refs==1);
    reset(); turnip_enabled=2; missing=required[0];
    assert(maybe_load_vulkan()==expected); assert(turnip_calls==1&&opens==1&&closes==1);
    puts("PASS Vulkan globals1.0/1.1: stale/malformed env ignored, dlopen + each dlsym/error + env failures fail closed/retry, optional loader fallback, full uintptr publication and cached lifetime");
    reset(); assert(maybe_load_vulkan()==expected && refs==1);
    void *one=(void *)(uintptr_t)Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(NULL,NULL);
    const char *vulkan="libvulkan.so.1";
    void *two=(void *)(uintptr_t)ndlopen_bugfix(NULL,NULL,(jlong)(uintptr_t)vulkan,RTLD_LAZY|RTLD_LOCAL);
    assert(one==expected&&two==expected&&refs==3&&leases==2&&path_lookups==1);
    fake_dlclose(one); assert(refs==2); assert(maybe_load_vulkan()==expected);
    fake_dlclose(two); assert(refs==1); assert(maybe_load_vulkan()==expected);
    for(int i=0;i<100;i++) {
        one=acquire_vulkan_loader(); assert(one==expected&&refs==2);
        fake_dlclose(one); assert(refs==1);
        assert(maybe_load_vulkan()==expected);
    }
    assert(opens==1&&path_lookups==1&&leases==102&&refs==1);
    lease_fail=1; assert(!acquire_vulkan_loader()); assert(refs==1);
    lease_fail=0; lease_mismatch=1; assert(!acquire_vulkan_loader()); assert(refs==1);
    lease_mismatch=0; assert(acquire_vulkan_loader()==expected); fake_dlclose(expected);
    vulkan_loader_path=NULL; path_fail=1;
    assert(!acquire_vulkan_loader()); assert(refs==1&&vulkan_loader_handle==expected);
    path_fail=0; assert(acquire_vulkan_loader()==expected); fake_dlclose(expected); assert(refs==1);
    puts("PASS JNI + production LWJGL ndlopen hook: owned root + two consumers, balanced destroy/init leases, same-namespace acquire, path resolution cached, retain/mismatch/dladdr failures preserve root");
    for(int round=0;round<20;round++) {
        reset(); setenv("VULKAN_PTR","stale",1);
        pthread_t threads[32];
        for(size_t i=0;i<32;i++) assert(pthread_create(&threads[i],NULL,caller,NULL)==0);
        for(size_t i=0;i<32;i++) assert(pthread_join(threads[i],NULL)==0);
        assert(opens==1&&lookups==4&&publications==1&&!closes&&refs==1&&!leases);
        assert(vulkan_loader_handle==expected); assert_published(expected);
    }
    puts("PASS 20 x 32 concurrent callers x 200 requests: exactly one dlopen/4 symbol lookups/1 env publication per fresh cache, no repeated opens or per-query env allocation");
}
'''

READINESS_SUFFIX = r'''
static jint mode(void) { return Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage(NULL,NULL); }
static void *reader(void *unused) {
    (void)unused;
    for(int i=0;i<300000;i++) { jint m=mode(); assert(m>=0&&m<=2); }
    return NULL;
}
int main(void) {
    reset("opengles_mobileglues"); assert(pojavInit()); assert(mode()==0);
    assert(pojavCreateContext(NULL)==&window); assert(mode()==1&&coverage==1);
    int old_inits=inits;
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_NO_API); assert(mode()==2&&!coverage);
    assert(pojavCreateContext(NULL)==state.pojavWindow); assert(mode()==2);
    assert(!vulkan_loads); /* Platform/native window does not need a GL loader. */
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API); assert(mode()==0);
    assert(pojavCreateContext(NULL)==&window); assert(mode()==1&&coverage==1&&inits==old_inits);
    reset("vulkan_zink"); assert(pojavInit()); vulkan_fail=1;
    assert(!pojavCreateContext(NULL)); assert(mode()==0&&!coverage&&!inits&&!setups);
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_NO_API); assert(mode()==2);
    assert(pojavCreateContext(NULL)==state.pojavWindow&&mode()==2);
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API); vulkan_fail=0;
    assert(pojavCreateContext(NULL)==&window); assert(mode()==1&&coverage==2);
    reset("opengles_mobileglues"); init_fail=1; assert(pojavInit());
    assert(!pojavCreateContext(NULL)); assert(mode()==0&&!coverage);
    init_fail=0; assert(pojavCreateContext(NULL)==&window); assert(mode()==1);
    /* Unsupported GL selection may not dispatch a previously loaded table. */
    setenv("AMETHYST_RENDERER","unsupported",1);
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API);
    assert(!pojavCreateContext(NULL)); assert(mode()==0&&!coverage&&!br_init_context);
    reset("opengles_mobileglues"); assert(pojavInit());
    pthread_t readers[8];
    for(size_t i=0;i<8;i++) assert(pthread_create(&readers[i],NULL,reader,NULL)==0);
    for(int i=0;i<30000;i++) {
        pojavSetWindowHint(GLFW_CLIENT_API,GLFW_NO_API); assert(mode()==2);
        pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API); assert(mode()==0);
        assert(pojavCreateContext(NULL)==&window); assert(mode()==1);
    }
    for(size_t i=0;i<8;i++) assert(pthread_join(readers[i],NULL)==0);
    puts("PASS init/GL/fail/NO_API/GL-return: atomic32 enum0/1/2, coverage restored without backend reinit; 8 readers x 300000 queries during 30000 transitions");
}
'''

REAL_PREFIX = '#define _GNU_SOURCE\n' + prior.COMMON + r'''
#include <inttypes.h>
#include <pthread.h>
#include <unistd.h>
#define ABI_COMPAT __attribute__((unused))
#define JNIEXPORT
#define JNICALL
typedef void JNIEnv;
typedef void *jclass;
typedef int jint;
typedef long long jlong;
static pthread_mutex_t vulkan_loader_mutex=PTHREAD_MUTEX_INITIALIZER;
static void *vulkan_loader_handle;
static bool vulkan_loader_in_namespace;
static const char *vulkan_loader_path;
static void *mapped_dlopen(const char *name, int flags) {
    if(!strcmp(name,"libvulkan.so")) name=getenv("ROUND3_TEST_LIBRARY");
    return dlopen(name,flags);
}
#define dlopen mapped_dlopen
'''

REAL_SUFFIX = r'''
#undef dlopen
int main(void) {
    const char *marker=getenv("ROUND3_TEST_DESTRUCTOR"); assert(marker);
    assert(access(marker,F_OK)!=0);
    void *root=maybe_load_vulkan(); assert(root);
    void (*alive)(void)=dlsym(root,"vkCreateInstance"); assert(alive);
    for(int i=0;i<1000;i++) assert(maybe_load_vulkan()==root);
    void *one=(void *)(uintptr_t)Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(NULL,NULL);
    const char *name="libvulkan.so";
    void *two=(void *)(uintptr_t)ndlopen_bugfix(NULL,NULL,(jlong)(uintptr_t)name,RTLD_LAZY|RTLD_LOCAL);
    assert(one==root&&two==root);
    assert(dlclose(one)==0); alive(); assert(maybe_load_vulkan()==root);
    assert(dlclose(two)==0); alive(); assert(maybe_load_vulkan()==root);
    for(int i=0;i<20;i++) {
        one=acquire_vulkan_loader(); assert(one==root);
        assert(dlclose(one)==0); alive(); assert(maybe_load_vulkan()==root);
    }
    assert(access(marker,F_OK)!=0); /* Consumer destroys did not unload root. */
    /* Test-only: release the owning root to detect leaked extra query refs.
     * Production deliberately retains exactly this one until process exit. */
    assert(dlclose(root)==0); vulkan_loader_handle=NULL;
    FILE *file=fopen(marker,"rb"); assert(file);
    assert(fgetc(file)=='X'&&fgetc(file)==EOF); fclose(file);
    puts("PASS real host ELF linker: two owning consumers + repeated init/destroy + 1000 borrowed queries; final single root close unloads exactly once (no leaked query references)");
}
'''

LIBRARY_SOURCE = r'''
#include <stdio.h>
#include <stdlib.h>
void vkGetInstanceProcAddr(void) {}
void vkCreateInstance(void) {}
void vkEnumerateInstanceExtensionProperties(void) {}
void vkEnumerateInstanceLayerProperties(void) {}
__attribute__((destructor)) static void closed(void) {
    FILE *f=fopen(getenv("ROUND3_TEST_DESTRUCTOR"),"ab");
    if(f) { fputc('X',f); fclose(f); }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="vulkan-loader-round3-") as directory:
        out = pathlib.Path(directory)
        (out / "EGL").mkdir()
        (out / "EGL/egl.h").write_text(prior.EGL_HEADER)
        loader = PREFIX
        real = REAL_PREFIX
        for name in ["set_vulkan_ptr", "valid_vulkan_loader", "open_vulkan_loader",
                     "maybe_load_vulkan", "load_vulkan", "acquire_vulkan_loader",
                     "Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle"]:
            extracted = function(BRIDGE, name)
            loader += extracted
            real += extracted
        loader += function(ROOT / "app_pojavlauncher/src/main/jni/jvm_hooks/lwjgl_dlopen_hook.c", "ndlopen_bugfix")
        real += function(ROOT / "app_pojavlauncher/src/main/jni/jvm_hooks/lwjgl_dlopen_hook.c", "ndlopen_bugfix")
        library_source = out / "vulkan-double.c"
        library_source.write_text(LIBRARY_SOURCE)
        library = out / "libvulkan-double.so"
        subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-shared", "-fPIC",
                        str(library_source), "-o", str(library)], check=True)
        # Use the existing Android/bridge doubles but extract the actual new JNI
        # enum and all platform/GL dispatch functions again from production.
        readiness = prior.BRIDGE_PREFIX + r'''
#include <pthread.h>
#define JNIEXPORT
#define JNICALL
typedef void JNIEnv;
typedef void *jclass;
typedef int jint;
'''
        readiness += function(ROOT / "app_pojavlauncher/src/main/jni/ctxbridges/bridge_tbl.h", "clear_bridge_tbl")
        for name in ["pojavInitOpenGL", "pojavInit", "pojavGetCurrentContext", "pojavMakeCurrent",
                     "pojavCreateContext", "pojavSetWindowHint", "pojavSwapInterval",
                     "Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage"]:
            readiness += function(BRIDGE, name)
        readiness += prior.BRIDGE_SUFFIX[:prior.BRIDGE_SUFFIX.index("int main(void)")]
        # The old fixture's assert_failed helper isn't used by this new main.
        readiness += READINESS_SUFFIX.replace("int main(void) {", "int main(void) {\n    (void)assert_failed;", 1)
        for name, source in {"loader": loader + SUFFIX, "readiness": readiness,
                             "real-linker": real + REAL_SUFFIX}.items():
            c = out / (name + ".c")
            c.write_text(source)
            binary = out / name
            flags = [os.environ.get("CC", "cc"), "-std=c11", "-O2", "-pthread", "-Wall", "-Wextra", "-Werror"]
            if os.environ.get("VULKAN_ROUND3_SANITIZE") == "1":
                flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-fno-pie", "-no-pie"]
            subprocess.run(flags + ["-I" + str(out), str(c), "-ldl", "-o", str(binary)], check=True)
            env = dict(os.environ, ROUND3_TEST_LIBRARY=str(library),
                       ROUND3_TEST_DESTRUCTOR=str(out / "destructor-marker"))
            subprocess.run([str(binary)], env=env, check=True)


if __name__ == "__main__":
    main()
