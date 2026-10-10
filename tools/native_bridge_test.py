#!/usr/bin/env python3
"""Compile the production bridge-selection function with host bridge doubles."""
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(sys.argv[1])
source = (root / "app_pojavlauncher/src/main/jni/egl_bridge.c").read_text()
start = source.index("int pojavInitOpenGL() {")
end = source.index("extern void updateMonitorSize", start)
function = source[start:end]
table = (root / "app_pojavlauncher/src/main/jni/ctxbridges/bridge_tbl.h").read_text()
function = table[table.index("void clear_bridge_tbl() {"):table.index("void set_osm_bridge_tbl() {")] + function
prefix = r'''
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <stdint.h>
enum { RENDERER_GL4ES = 1, RENDERER_VK_ZINK = 2 };
struct state { int force_vsync; int config_renderer; } state;
static struct state *pojav_environ = &state;
static int (*br_init)(void);
static void (*br_setup_window)(void);
static int (*br_init_context)(void), (*br_make_current)(void), (*br_get_current)(void);
static int (*br_swap_buffers)(void), (*br_swap_interval)(void);
static int coverage, inits, setups, vulkan_loads, incomplete;
static uint32_t native_counter_mode;
static void *live_contexts;
static int bridge_backend;
static void pojav_frame_set_coverage(int value) { coverage=value; }
static int init(void) { inits++; return 1; }
static void setup(void) { setups++; }
static void set_gl_bridge_tbl(void) {
    br_init=incomplete?NULL:init; br_setup_window=setup;
    br_init_context=br_make_current=br_get_current=br_swap_buffers=br_swap_interval=init;
}
static void set_osm_bridge_tbl(void) { set_gl_bridge_tbl(); }
static void *maybe_load_vulkan(void) { vulkan_loads++; return (void *)1; }
'''
suffix = r'''
static void reset(const char *renderer) {
    live_contexts=NULL; bridge_backend=0;
    br_init=NULL; br_setup_window=NULL; coverage=1; inits=setups=vulkan_loads=incomplete=0;
    if(renderer) assert(setenv("AMETHYST_RENDERER",renderer,1)==0);
    else assert(unsetenv("AMETHYST_RENDERER")==0);
}
int main(void) {
    reset(NULL); pojavInitOpenGL(); assert(!inits&&!setups&&!vulkan_loads&&!coverage);
    reset("gallium_panfrost"); pojavInitOpenGL(); assert(!inits&&!setups&&!vulkan_loads&&!coverage);
    reset("gallium_freedreno"); pojavInitOpenGL(); assert(!inits&&!setups&&!vulkan_loads&&!coverage);
    reset("opengles_mobileglues"); pojavInitOpenGL(); assert(inits==1&&setups==1&&!vulkan_loads);
    reset("opengles3_desktopgl_zink_kopper"); pojavInitOpenGL(); assert(inits==1&&setups==1&&vulkan_loads==1);
    reset("vulkan_zink"); pojavInitOpenGL(); assert(inits==1&&setups==1&&vulkan_loads==1);
    reset("opengles_mobileglues"); incomplete=1; pojavInitOpenGL(); assert(!inits&&!setups&&!coverage);
    puts("PASS 7 production native bridge selection cases (host doubles, not renderer execution)");
}
'''
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory)
    c = path / "bridge.c"
    c.write_text(prefix + function + suffix)
    binary = path / "test"
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(c), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
