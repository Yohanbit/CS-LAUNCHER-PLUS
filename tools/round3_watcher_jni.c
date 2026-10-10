#include <jni.h>

static jint coverage;
static jlong total;
static jint coverage_reads;
static jint total_reads;
static jint change_coverage_after_total = -1;

JNIEXPORT void JNICALL Java_Round3WatcherLifecycleTest_configure(
        JNIEnv *env, jclass cls, jint next_coverage, jlong next_total) {
    (void)env; (void)cls;
    coverage = next_coverage; total = next_total;
    coverage_reads = 0; total_reads = 0; change_coverage_after_total = -1;
}
JNIEXPORT void JNICALL Java_Round3WatcherLifecycleTest_changeCoverageOnTotal(
        JNIEnv *env, jclass cls, jint next_coverage) {
    (void)env; (void)cls;
    change_coverage_after_total = next_coverage;
}
JNIEXPORT jint JNICALL Java_Round3WatcherLifecycleTest_coverageReads(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return coverage_reads;
}
JNIEXPORT jint JNICALL Java_Round3WatcherLifecycleTest_totalReads(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return total_reads;
}
#ifndef ROUND3_LEGACY_LIBRARY
JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls; coverage_reads++; return coverage;
}
#endif
JNIEXPORT jlong JNICALL Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetTotalPresents(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls; total_reads++;
    if (change_coverage_after_total >= 0) {
        coverage = change_coverage_after_total;
        change_coverage_after_total = -1;
    }
    return total;
}
