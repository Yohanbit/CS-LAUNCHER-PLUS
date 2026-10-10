#!/usr/bin/env python3
"""Compile complete production bridge/backends + driver helper against host APIs.

No replacement production lifetime logic. EGL/OSMesa/Android APIs are spies.
Optional private-namespace behavior needs separate physical Android validation.
"""
import os,pathlib,subprocess,sys,tempfile,re
import native_graphics_round2_test as prior

ROOT=pathlib.Path(sys.argv[1]).resolve()
JNI=ROOT/'app_pojavlauncher/src/main/jni'
SUPPORT=ROOT/'tools/consolidation_native_support'
EGL=prior.EGL_HEADER.replace('#endif','''
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_BAD_SURFACE 0x300d
#define EGL_MIN_SWAP_INTERVAL 0x303b
#define EGL_MAX_SWAP_INTERVAL 0x303c
#endif
''')
WINDOW='''
#pragma once
#include <stdint.h>
#include <jni.h>
typedef struct ANativeWindow { unsigned int refs; int id; int locked; unsigned char pixels[16]; } ANativeWindow;
typedef struct { int32_t width,height,stride,format; void *bits; uint32_t reserved[6]; } ANativeWindow_Buffer;
#define WINDOW_FORMAT_RGBX_8888 2
#define AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM 2
void ANativeWindow_acquire(ANativeWindow*);
void ANativeWindow_release(ANativeWindow*);
int32_t ANativeWindow_getWidth(ANativeWindow*);
int32_t ANativeWindow_getHeight(ANativeWindow*);
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*,int32_t,int32_t,int32_t);
int32_t ANativeWindow_lock(ANativeWindow*,ANativeWindow_Buffer*,void*);
int32_t ANativeWindow_unlockAndPost(ANativeWindow*);
ANativeWindow *ANativeWindow_fromSurface(JNIEnv*,jobject);
'''
DLEXT='''
#pragma once
#include <stdint.h>
struct android_namespace_t;
typedef struct { uint64_t flags; void *reserved_addr; size_t reserved_size; int relro_fd; int library_fd; off_t library_fd_offset; struct android_namespace_t *library_namespace; } android_dlextinfo;
#define ANDROID_DLEXT_USE_NAMESPACE 0x200
#define ANDROID_DLEXT_USE_LIBRARY_FD 0x10
void *android_dlopen_ext(const char*,int,const android_dlextinfo*);
'''
ENV='''
#pragma once
#include <jni.h>
#include <android/native_window.h>
#include "ctxbridges/common.h"
struct test_environment { int config_renderer; bool force_vsync; ANativeWindow *pojavWindow; basic_render_window_t *mainWindowBundle; JNIEnv *glfwThreadVmEnv; JavaVM *runtimeJavaVMPtr; int savedWidth,savedHeight; };
extern struct test_environment *pojav_environ;
'''
def write(out,path,text):
 p=out/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
def run():
 java=pathlib.Path(os.environ.get('JAVA_HOME',''))
 if not (java/'include/jni.h').exists():
  java=pathlib.Path(subprocess.check_output(['sh','-c','readlink -f "$(command -v javac)"'],text=True).strip()).parent.parent
 with tempfile.TemporaryDirectory(prefix='consolidation-native-') as directory:
  out=pathlib.Path(directory)
  # Stage just these exact production files locally. Repeated C include-path
  # probes on the cloud-backed source directory otherwise dominate host runs.
  staged=out/'jni'
  paths=['egl_bridge.c','frame_metrics.c','frame_metrics.h','frame_pacing.h','utils.h','log.h',
         'GL/gl.h','GL/glext.h','GL/osmesa.h','ctxbridges/common.h','ctxbridges/bridge_tbl.h',
         'ctxbridges/gl_bridge.c','ctxbridges/gl_bridge.h','ctxbridges/osm_bridge.c','ctxbridges/osm_bridge.h',
         'ctxbridges/egl_loader.h','ctxbridges/osmesa_loader.h','driver_helper/nsbypass.c','driver_helper/nsbypass.h','driver_helper/hook.c']
  for path in paths:write(staged,path,(JNI/path).read_text())
  write(out,'EGL/egl.h',EGL);write(out,'KHR/khrplatform.h',prior.KHR_HEADER)
  write(out,'android/native_window.h',WINDOW);write(out,'android/native_window_jni.h','#include "native_window.h"\n')
  write(out,'android/api-level.h','int android_get_device_api_level(void);\n')
  write(out,'android/rect.h','')
  write(out,'android/dlext.h',DLEXT)
  write(out,'android/log.h','''
#pragma once
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_DEBUG 3
int __android_log_print(int,const char*,const char*,...);
''')
  write(out,'environ/environ.h',ENV)
  # Real production pointer declarations; only their external API targets are doubles.
  pointers='\n'.join((JNI/'ctxbridges'/name).read_text() for name in ['egl_loader.h','osmesa_loader.h'])
  pointers='#include "ctxbridges/egl_loader.h"\n#include "ctxbridges/osmesa_loader.h"\n'+'\n'.join(x.replace('extern ','',1) for x in re.findall(r'extern\s+[^;]+;',pointers))
  write(out,'mock_pointer_definitions.h',pointers)
  flags=[os.environ.get('CC','cc'),'-D_GNU_SOURCE','-std=gnu11','-O1','-g','-Wall','-Wextra','-Werror=implicit-function-declaration','-Wno-unused-parameter','-I'+str(out),'-I'+str(staged),'-I'+str(java/'include'),'-I'+str(java/'include/linux'),'-pthread']
  if os.environ.get('CONSOLIDATION_NATIVE_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
  bridge_sources=[staged/'egl_bridge.c',staged/'ctxbridges/gl_bridge.c',staged/'ctxbridges/osm_bridge.c',staged/'frame_metrics.c',SUPPORT/'bridge.c']
  subprocess.run(flags+[str(x) for x in bridge_sources]+['-ldl','-o',str(out/'bridge')],check=True)
  subprocess.run([str(out/'bridge')],check=True)
  subprocess.run(flags+['-DADRENO_POSSIBLE','-DSEARCH_PATH="'+str(out)+'"',str(SUPPORT/'driver.c'),'-Wl,--wrap=mmap','-Wl,--wrap=munmap','-Wl,--wrap=msync','-ldl','-o',str(out/'driver')],check=True)
  subprocess.run([str(out/'driver'),str(out)],check=True)
  subprocess.run(flags+['-DADRENO_POSSIBLE','-c',str(staged/'egl_bridge.c'),'-o',str(out/'optional-bridge.o')],check=True)
  print('PASS host compile complete optional ADRENO bridge branch (not NDK/ARM/device execution)')
if __name__=='__main__':run()
