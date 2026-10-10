//
// Created by maks on 17.09.2022.
//
#include <EGL//egl.h>
#include <stdbool.h>
#ifndef POJAVLAUNCHER_GL_BRIDGE_H
#define POJAVLAUNCHER_GL_BRIDGE_H

#include "common.h"

typedef struct {
    POJAV_WINDOW_FIELDS;
    EGLConfig  config;
    EGLint     format;
    EGLContext context;
    EGLSurface surface;
} gl_render_window_t;

bool gl_init();
gl_render_window_t* gl_get_current();
gl_render_window_t* gl_init_context(gl_render_window_t* share);
void gl_make_current(gl_render_window_t* bundle);
bool gl_swap_buffers();
void gl_setup_window();
void gl_swap_interval(int swapInterval);
/* Caller holds the lifecycle lock; no bundle may still be current elsewhere. */
bool gl_destroy_context(gl_render_window_t* bundle);
bool gl_terminate(void);


#endif //POJAVLAUNCHER_GL_BRIDGE_H
