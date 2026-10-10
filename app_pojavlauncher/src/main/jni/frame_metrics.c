#include "frame_metrics.h"
#include <limits.h>
#include <pthread.h>
#include <time.h>

/* One very short critical section per main-window swap. No allocation,
 * logging, JNI, driver queries, glFinish, or readback in the hot path. */
static pthread_mutex_t metrics_lock = PTHREAD_MUTEX_INITIALIZER;
static pojav_frame_snapshot metrics;

uint64_t pojav_monotonic_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

void pojav_frame_record(uint64_t start_ns, uint64_t end_ns,
                        uint64_t inter_swap_ns, bool has_inter_swap,
                        bool submitted) {
    uint64_t duration = end_ns >= start_ns ? end_ns - start_ns : 0;
    pthread_mutex_lock(&metrics_lock);
    metrics.attempts++;
    if (submitted) metrics.submissions++;
    else metrics.unsuccessful_swaps++;
    metrics.swap_ns_total += duration;
    metrics.swap_ns_last = duration;
    if (duration > metrics.swap_ns_max) metrics.swap_ns_max = duration;
    if (has_inter_swap) {
        metrics.inter_swap_ns_total += inter_swap_ns;
        metrics.inter_swap_samples++;
        metrics.inter_swap_ns_last = inter_swap_ns;
    }
    metrics.sample_time_ns = end_ns;
    pthread_mutex_unlock(&metrics_lock);
}

void pojav_frame_snapshot_read(pojav_frame_snapshot *out) {
    pthread_mutex_lock(&metrics_lock);
    *out = metrics;
    pthread_mutex_unlock(&metrics_lock);
}

int pojav_frame_rate(uint64_t count, uint64_t now_ns,
                     uint64_t *previous_count, uint64_t *previous_time_ns) {
    if (*previous_time_ns == 0 || now_ns < *previous_time_ns || count < *previous_count) {
        *previous_count = count;
        *previous_time_ns = now_ns;
        return 0;
    }
    uint64_t elapsed = now_ns - *previous_time_ns;
    if (elapsed < 200000000ull) return -1;
    double rate = (double)(count - *previous_count) * 1000000000.0 / (double)elapsed;
    *previous_count = count;
    *previous_time_ns = now_ns;
    return rate > INT_MAX ? INT_MAX : (int)rate;
}
