/* JNI call spy, not the production counter implementation or a device measurement. */
#include <jni.h>
static jint enables, disables, reads;
JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_FrameMetrics_nativeSetTimingEnabled(
        JNIEnv *env, jclass cls, jboolean enabled) {
    (void)env; (void)cls;
    if (enabled) enables++; else disables++;
}
JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_utils_FrameMetrics_nativeReadSnapshot(
        JNIEnv *env, jclass cls, jlongArray values) {
    (void)env; (void)cls; (void)values;
    reads++;
    return JNI_FALSE;
}
JNIEXPORT jintArray JNICALL Java_OverheadLifecycleTest_calls(JNIEnv *env, jclass cls) {
    (void)cls;
    const jint values[] = {enables, disables, reads};
    jintArray array = (*env)->NewIntArray(env, 3);
    if (array != NULL) (*env)->SetIntArrayRegion(env, array, 0, 3, values);
    return array;
}
