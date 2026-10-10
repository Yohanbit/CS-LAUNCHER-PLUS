#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <jni.h>
#include <android/native_window.h>
#include <environ/environ.h>
#include "ctxbridges/gl_bridge.h"
#include "ctxbridges/osm_bridge.h"
#include "frame_metrics.h"
#include "mock_pointer_definitions.h"

extern int pojavInit(void);
extern void *pojavCreateContext(void*);
extern void pojavMakeCurrent(void*);
extern void pojavSwapBuffers(void);
extern void pojavTerminate(void);
extern void pojavSetWindowHint(int,int);
extern void Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv*,jclass,jobject);
extern void Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(JNIEnv*,jclass);
extern jint Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage(JNIEnv*,jclass);
extern jlong Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetTotalPresents(JNIEnv*,jclass);

static struct test_environment environment;
struct test_environment *pojav_environ=&environment;
struct context { int id; int bound; };
struct surface { ANativeWindow *window; };
static __thread struct context *egl_current, *osm_current;
static int create_failure, display_failure, unbind_failure, surface_destroy_failure, context_destroy_failure;
static int osmesa_unbind_failure, lock_failure, post_failure;
static int created, destroyed, surfaces_created, surfaces_destroyed, terminations, finishes, posts;
static ANativeWindow windows[4];

static jboolean no_exception(JNIEnv *env) { return JNI_FALSE; }
static const struct JNINativeInterface_ jni_table={.ExceptionCheck=no_exception};
static JNIEnv jni=&jni_table;
void ANativeWindow_acquire(ANativeWindow *w) { assert(w); assert(__atomic_fetch_add(&w->refs,1,__ATOMIC_SEQ_CST)>0); }
void ANativeWindow_release(ANativeWindow *w) { assert(w); assert(__atomic_fetch_sub(&w->refs,1,__ATOMIC_SEQ_CST)>1); }
int32_t ANativeWindow_getWidth(ANativeWindow *w) { assert(w->refs>1); return 2; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { assert(w->refs>1); return 2; }
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *w,int32_t a,int32_t b,int32_t c) { assert(w->refs>1); return 0; }
int32_t ANativeWindow_lock(ANativeWindow *w,ANativeWindow_Buffer *b,void *rect) {
    if(lock_failure) return -1;
    assert(!w->locked);w->locked=1;b->width=b->height=b->stride=2;b->bits=w->pixels;return 0;
}
int32_t ANativeWindow_unlockAndPost(ANativeWindow *w) { assert(w->locked);w->locked=0;posts++;return post_failure?-1:0; }
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *env,jobject surface) { ANativeWindow *w=(ANativeWindow*)surface;if(w)ANativeWindow_acquire(w);return w; }
int __android_log_print(int priority,const char *tag,const char *format,...) { return 0; }
JNIEnv *get_attached_env(JavaVM *vm) { return &jni; }
void updateMonitorSize(int w,int h) { assert(w==2&&h==2); }
void setNativeWindowSwapInterval(ANativeWindow *w,int interval) { assert(w->refs>1); }
int android_get_device_api_level(void) { return 21; }

static struct context *new_context(void) { if(create_failure)return NULL;struct context *c=calloc(1,sizeof(*c));assert(c);c->id=++created;return c; }
static void destroy_context(struct context *c) { assert(c&&!c->bound);destroyed++;free(c); }
static EGLDisplay display(NativeDisplayType d) { return display_failure?EGL_NO_DISPLAY:(void*)1; }
static EGLBoolean initialize(EGLDisplay d,EGLint *a,EGLint *b) { return EGL_TRUE; }
static EGLBoolean choose(EGLDisplay d,const EGLint *a,EGLConfig *c,EGLint n,EGLint *count) { assert(n==1);*c=(void*)2;*count=1;return EGL_TRUE; }
static EGLBoolean attrib(EGLDisplay d,EGLConfig c,EGLint name,EGLint *value) { *value=name==EGL_MIN_SWAP_INTERVAL?0:1;return EGL_TRUE; }
static EGLBoolean bind_api(EGLenum api) { return EGL_TRUE; }
static EGLContext create_egl(EGLDisplay d,EGLConfig c,EGLContext share,const EGLint *a) { return new_context(); }
static EGLSurface create_window(EGLDisplay d,EGLConfig c,NativeWindowType window,const EGLint *a) {
    struct surface *s=calloc(1,sizeof(*s));assert(s);s->window=window;surfaces_created++;return s;
}
static EGLSurface create_pbuffer(EGLDisplay d,EGLConfig c,const EGLint *a) { return create_window(d,c,NULL,a); }
static EGLBoolean make_egl(EGLDisplay d,EGLSurface draw,EGLSurface read,EGLContext ctx) {
    if(ctx==EGL_NO_CONTEXT&&unbind_failure)return EGL_FALSE;
    if(egl_current)egl_current->bound--;
    egl_current=ctx;if(egl_current)egl_current->bound++;return EGL_TRUE;
}
static EGLBoolean destroy_surface(EGLDisplay d,EGLSurface s) { if(surface_destroy_failure)return EGL_FALSE;assert(s);surfaces_destroyed++;free(s);return EGL_TRUE; }
static EGLBoolean destroy_egl(EGLDisplay d,EGLContext c) { if(context_destroy_failure)return EGL_FALSE;destroy_context(c);return EGL_TRUE; }
static EGLBoolean terminate_egl(EGLDisplay d) { terminations++;return EGL_TRUE; }
static EGLBoolean release_thread(void) { assert(egl_current==NULL);return EGL_TRUE; }
static EGLBoolean swap_egl(EGLDisplay d,EGLSurface s) { assert(egl_current&&s);return EGL_TRUE; }
static EGLBoolean interval_egl(EGLDisplay d,EGLint i) { return EGL_TRUE; }
static EGLint error_egl(void) { return 0; }
static EGLBoolean query(EGLDisplay d,EGLSurface s,EGLint name,EGLint *v) { *v=2;return EGL_TRUE; }
static OSMesaContext create_osm(GLenum format,OSMesaContext share) { return (OSMesaContext)new_context(); }
static void destroy_osm(OSMesaContext c) { destroy_context((struct context*)c); }
static OSMesaContext current_osm(void) { return (OSMesaContext)osm_current; }
static GLboolean make_osm(OSMesaContext c,void *b,GLenum t,GLsizei w,GLsizei h) {
    if(c==NULL&&osmesa_unbind_failure)return GL_FALSE;
    if(osm_current)osm_current->bound--;
    osm_current=(struct context*)c;
    if(osm_current)osm_current->bound++;
    return GL_TRUE;
}
static void pixel_store(GLint p,GLint value) { }
static void finish_osm(void) { assert(osm_current);finishes++; }
bool dlsym_EGL(void) {
    eglGetDisplay_p=display;eglInitialize_p=initialize;eglChooseConfig_p=choose;eglGetConfigAttrib_p=attrib;
    eglBindAPI_p=bind_api;eglCreateContext_p=create_egl;eglCreateWindowSurface_p=create_window;eglCreatePbufferSurface_p=create_pbuffer;
    eglMakeCurrent_p=make_egl;eglDestroySurface_p=destroy_surface;eglDestroyContext_p=destroy_egl;eglTerminate_p=terminate_egl;
    eglReleaseThread_p=release_thread;eglSwapBuffers_p=swap_egl;eglSwapInterval_p=interval_egl;eglGetError_p=error_egl;eglQuerySurface_p=query;
    return true;
}
bool dlsym_OSMesa(void) {
    OSMesaCreateContext_p=create_osm;OSMesaDestroyContext_p=destroy_osm;OSMesaGetCurrentContext_p=current_osm;
    OSMesaMakeCurrent_p=make_osm;OSMesaPixelStore_p=pixel_store;glFinish_p=finish_osm;return true;
}
static int mode(void) { return Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage(NULL,NULL); }
static void publish(int i) { Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(&jni,NULL,(jobject)&windows[i]); }
static void release_window(void) { Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(&jni,NULL); }
static void all_java_refs(void) { for(int i=0;i<4;i++)assert(windows[i].refs==1); }

static _Atomic int worker_bound,worker_release;
static void *shared_worker(void *context) {
    pojavMakeCurrent(context);atomic_store(&worker_bound,1);
    while(!atomic_load(&worker_release))sched_yield();
    pojavMakeCurrent(NULL);return NULL;
}
static _Atomic int publication_done;
static void *publisher(void *unused) { for(int i=0;i<1000;i++)publish(i%4);atomic_store(&publication_done,1);return NULL; }

int main(void) {
    for(int i=0;i<4;i++){windows[i].refs=1;windows[i].id=i;}
    setenv("AMETHYST_RENDERER","opengles_mobileglues",1);publish(0);assert(pojavInit());
    create_failure=1;assert(!pojavCreateContext(NULL));assert(mode()==0&&!pojav_frame_coverage());
    assert(Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetTotalPresents(NULL,NULL)==-1);
    create_failure=0;void *main=pojavCreateContext(NULL);assert(main&&mode()==1&&pojav_frame_coverage()==1);
    pojavMakeCurrent(main);uint64_t count=pojav_frame_submissions();pojavSwapBuffers();assert(pojav_frame_submissions()==count+1);
    create_failure=1;assert(!pojavCreateContext(main));assert(mode()==1&&pojav_frame_coverage()==1);
    create_failure=0;
    publish(1);publish(1);publish(2);pojavSwapBuffers();
    assert(((gl_render_window_t*)main)->nativeSurface==&windows[2]);
    release_window();release_window();pojavSwapBuffers();assert(((gl_render_window_t*)main)->nativeSurface==NULL);
    count=pojav_frame_submissions();pojavSwapBuffers();assert(pojav_frame_submissions()==count);
    publish(0);pojavSwapBuffers();
    void *shared=pojavCreateContext(main);assert(shared);
    pthread_t worker;assert(!pthread_create(&worker,NULL,shared_worker,shared));
    while(!atomic_load(&worker_bound))sched_yield();
    int before=destroyed;pojavTerminate();assert(destroyed==before&&mode()==0&&!pojav_frame_coverage());
    atomic_store(&worker_release,1);assert(!pthread_join(worker,NULL));
    context_destroy_failure=1;pojavTerminate();assert(destroyed==before);
    context_destroy_failure=0;pojavTerminate();assert(destroyed==created&&surfaces_destroyed==surfaces_created&&terminations==1);
    release_window();all_java_refs();pojavTerminate();assert(terminations==1);
    puts("PASS production EGL N01 primary/shared failure, independent window ownership, dummy exclusion, busy-thread deferred teardown, destroy retry/idempotent terminate");

    publish(0);assert(pojavInit());main=pojavCreateContext(NULL);assert(main);pojavMakeCurrent(main);
    publish(1);surface_destroy_failure=1;assert(!gl_swap_buffers());
    assert(((gl_render_window_t*)main)->nativeSurface==&windows[0]);
    surface_destroy_failure=0;pojavSwapBuffers();assert(((gl_render_window_t*)main)->nativeSurface==&windows[1]);
    /* Keep swaps off the 750 ms legacy EGL replacement workaround during the
     * concurrent publication stress. Snapshots are real production acquires. */
    pthread_t writer;assert(!pthread_create(&writer,NULL,publisher,NULL));
    while(!atomic_load(&publication_done)) {
        uint64_t generation;ANativeWindow *w=pojav_acquire_window(&generation);if(w)ANativeWindow_release(w);
    }
    assert(!pthread_join(writer,NULL));pojavSwapBuffers();pojavTerminate();release_window();all_java_refs();
    assert(destroyed==created&&surfaces_destroyed==surfaces_created);
    puts("PASS production window mutex transfer: 1000 UI publications concurrent with acquired reader snapshots; EGL surface-destroy failure retains pending refs then retries");

    setenv("AMETHYST_RENDERER","vulkan_zink",1);
    /* No physical Vulkan is required by this OSMesa fixture: use the GL
     * exported table through direct OSMesa API tests below, with real bundle
     * destructor and production window queue. */
    publish(0);assert(osm_init());osm_render_window_t *os= osm_init_context(NULL);assert(os);
    osm_make_current(os);assert(osm_get_current()==os);assert(osm_swap_buffers());assert(finishes>0&&posts>0);
    publish(0);assert(osm_swap_buffers());publish(1);assert(osm_swap_buffers());
    post_failure=1;assert(!osm_swap_buffers());post_failure=0;assert(!os->buffer_locked);
    publish(2);assert(osm_swap_buffers());
    atomic_store(&publication_done,0);
    assert(!pthread_create(&writer,NULL,publisher,NULL));
    for(int i=0;i<1000;i++)osm_swap_buffers();
    assert(!pthread_join(writer,NULL));assert(osm_swap_buffers());assert(os->nativeSurface==&windows[3]);
    osmesa_unbind_failure=1;osm_make_current(NULL);assert(osm_get_current()==os);
    osmesa_unbind_failure=0;osm_make_current(NULL);assert(osm_get_current()==NULL);
    environment.mainWindowBundle=NULL;assert(osm_destroy_context(os));release_window();all_java_refs();assert(destroyed==created);
    puts("PASS production OSMesa same-window/concurrent 1000 publication+swap transfers, glFinish retained, post error consumes lock once, unsupported unbind preserves driver binding, unbound destruction balances ownership");

    publish(0);assert(pojavInit());pojavSetWindowHint(0x22001,0);assert(mode()==2);
    assert(pojavCreateContext(NULL)==&windows[0]);publish(1);release_window();assert(windows[0].refs==2);
    assert(pojavCreateContext(NULL)==NULL);pojavTerminate();all_java_refs();
    puts("PASS production NO_API returned-window lease survives Java replacement/release until termination; no GL backend activation");
    return 0;
}
