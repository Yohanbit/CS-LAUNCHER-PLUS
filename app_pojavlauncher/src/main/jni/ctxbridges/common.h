//
// Created by maks on 18.10.2023.
//

#ifndef POJAVLAUNCHER_COMMON_H
#define POJAVLAUNCHER_COMMON_H

#include <stdbool.h>
#include <stdint.h>

#define STATE_RENDERER_ALIVE 0
#define STATE_RENDERER_NEW_WINDOW 1

/* Shared prefix of both opaque backend bundles. Window references are owned:
 * publication acquires them under window_mutex; only the rendering thread
 * transfers pending -> nativeSurface. bound_threads/next are protected by the
 * bridge lifecycle mutex, never by the UI window-publication mutex. */
#define POJAV_WINDOW_FIELDS \
    char state; \
    struct ANativeWindow *nativeSurface; \
    struct ANativeWindow *newNativeSurface; \
    uint64_t window_generation; \
    unsigned int bound_threads; \
    struct basic_render_window *next

typedef struct basic_render_window {
    POJAV_WINDOW_FIELDS;
} basic_render_window_t;

/* Returned non-NULL references must be released or transferred exactly once.
 * NULL is a real release-window event, distinguished by the generation. */
struct ANativeWindow *pojav_acquire_window(uint64_t *generation);
bool pojav_take_window_update(uint64_t *generation, struct ANativeWindow **window);

#endif //POJAVLAUNCHER_COMMON_H
