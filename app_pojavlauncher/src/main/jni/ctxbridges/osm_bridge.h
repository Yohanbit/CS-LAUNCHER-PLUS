//
// Created by maks on 18.10.2023.
//
#include <android/native_window.h>
#include <stdbool.h>
#ifndef POJAVLAUNCHER_OSM_BRIDGE_H
#define POJAVLAUNCHER_OSM_BRIDGE_H
#include "osmesa_loader.h"
#include "common.h"


typedef struct {
    POJAV_WINDOW_FIELDS;
    ANativeWindow_Buffer buffer;
    int32_t last_stride;
    bool disable_rendering;
    bool buffer_locked;
    OSMesaContext context;
} osm_render_window_t;

bool osm_init();
osm_render_window_t* osm_get_current();
osm_render_window_t* osm_init_context(osm_render_window_t* share);
void osm_make_current(osm_render_window_t* bundle);
bool osm_swap_buffers();
void osm_setup_window();
void osm_swap_interval(int swapInterval);
bool osm_destroy_context(osm_render_window_t* bundle);

#endif //POJAVLAUNCHER_OSM_BRIDGE_H
