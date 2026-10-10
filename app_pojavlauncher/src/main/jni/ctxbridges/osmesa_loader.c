//
// Created by maks on 21.09.2022.
//
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include "loader_dlopen.h"
#include "osmesa_loader.h"

GLboolean (*OSMesaMakeCurrent_p) (OSMesaContext ctx, void *buffer, GLenum type,
                                         GLsizei width, GLsizei height);
OSMesaContext (*OSMesaGetCurrentContext_p) (void);
OSMesaContext  (*OSMesaCreateContext_p) (GLenum format, OSMesaContext sharelist);
void (*OSMesaDestroyContext_p) (OSMesaContext ctx);
void (*OSMesaPixelStore_p) ( GLint pname, GLint value );
GLubyte* (*glGetString_p) (GLenum name);
void (*glFinish_p) (void);
void (*glClearColor_p) (GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha);
void (*glClear_p) (GLbitfield mask);
void (*glReadPixels_p) (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void * data);
void* (*OSMesaGetProcAddress_p)(const char* funcName);

#define OSMESA_BRIDGE_SYMBOLS(X) \
    X(OSMesaMakeCurrent) X(OSMesaGetCurrentContext) X(OSMesaCreateContext) \
    X(OSMesaDestroyContext) X(OSMesaPixelStore) X(glGetString) \
    X(glClearColor) X(glClear) X(glFinish) X(glReadPixels)

static void clear_osmesa_symbols(void) {
#define CLEAR(N) N##_p = NULL;
    OSMESA_BRIDGE_SYMBOLS(CLEAR)
#undef CLEAR
    OSMesaGetProcAddress_p = NULL;
}

static void* osmesa_bridge_symbol(void* handle, const char* name) {
    void* symbol = OSMesaGetProcAddress_p != NULL ? OSMesaGetProcAddress_p(name) : NULL;
    return symbol != NULL ? symbol : dlsym(handle, name);
}

bool dlsym_OSMesa() {
    clear_osmesa_symbols();
    void* dl_handle = loader_dlopen("libOSMesa.so.8", "libOSMesa.so", RTLD_LOCAL | RTLD_LAZY);
    if(dl_handle == NULL) return false;
    OSMesaGetProcAddress_p = dlsym(dl_handle, "OSMesaGetProcAddress");
#define LOAD(N) \
    N##_p = osmesa_bridge_symbol(dl_handle, #N); \
    if (N##_p == NULL) { \
        fprintf(stderr, "OSMesaBridge: missing required symbol %s\n", #N); \
        goto fail; \
    }
    OSMESA_BRIDGE_SYMBOLS(LOAD)
#undef LOAD
    return true;

fail:
    clear_osmesa_symbols();
    dlclose(dl_handle);
    return false;
}