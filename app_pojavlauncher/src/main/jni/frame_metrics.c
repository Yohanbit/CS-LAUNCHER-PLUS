#include "frame_metrics.h"
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <time.h>

/* Never silently introduce libatomic locks on ARM32. Android's supported
 * ARMv7/ARM64 targets provide native 64-bit atomic operations. A new target
 * must prove that here (and in the NDK test), rather than require a hidden
 * library or make the render loop contend with readers. */
_Static_assert(sizeof(unsigned long long) == sizeof(uint64_t), "64-bit counter required");
_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "frame counter must always be lock-free");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "timing gate must always be lock-free");
static _Atomic unsigned long long submission_count;
static _Atomic unsigned int timing_token;
static _Atomic unsigned int counter_coverage;
/* Used only when detailed diagnostics are explicitly enabled or read. */
static pthread_mutex_t metrics_lock = PTHREAD_MUTEX_INITIALIZER;
static pojav_frame_snapshot metrics;
static __thread uint32_t previous_token;
static __thread uint64_t previous_end_ns;

uint64_t pojav_monotonic_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

uint64_t pojav_frame_submissions(void) {
    return atomic_load_explicit(&submission_count, memory_order_relaxed);
}

void pojav_frame_set_coverage(unsigned int coverage) {
    atomic_store_explicit(&counter_coverage, coverage <= 2 ? coverage : 0, memory_order_release);
}

unsigned int pojav_frame_coverage(void) {
    return atomic_load_explicit(&counter_coverage, memory_order_acquire);
}

uint32_t pojav_frame_timing_token(void) {
    return atomic_load_explicit(&timing_token, memory_order_acquire);
}

void pojav_frame_set_timing_enabled(bool enabled) {
    pthread_mutex_lock(&metrics_lock);
    uint32_t old = atomic_load_explicit(&timing_token, memory_order_relaxed);
    if (((old & 1u) != 0) != enabled) {
        uint32_t next = ((old & ~1u) + 2u) | (enabled ? 1u : 0u);
        memset(&metrics, 0, sizeof(metrics));
        metrics.timing_epoch = next;
        atomic_store_explicit(&timing_token, next, memory_order_release);
    }
    pthread_mutex_unlock(&metrics_lock);
}

static void record_timing(uint32_t token, uint64_t start_ns, uint64_t end_ns,
                          uint64_t inter_swap_ns, bool has_inter_swap, bool submitted) {
    if ((token & 1u) == 0 || start_ns == 0 || end_ns < start_ns) return;
    uint64_t duration = end_ns >= start_ns ? end_ns - start_ns : 0;
    pthread_mutex_lock(&metrics_lock);
    if (token != atomic_load_explicit(&timing_token, memory_order_relaxed)) {
        pthread_mutex_unlock(&metrics_lock);
        return;
    }
    metrics.attempts++;
    if (!submitted) metrics.unsuccessful_swaps++;
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

void pojav_frame_record_swap(bool submitted, uint32_t token,
                             uint64_t start_ns, uint64_t end_ns) {
    if (submitted) atomic_fetch_add_explicit(&submission_count, 1, memory_order_relaxed);
    if ((token & 1u) == 0) return; /* No clock calls or locks in the default path. */
    bool has_gap = previous_token == token && previous_end_ns != 0 && start_ns >= previous_end_ns;
    record_timing(token, start_ns, end_ns,
                  has_gap ? start_ns - previous_end_ns : 0, has_gap, submitted);
    previous_token = token;
    previous_end_ns = end_ns;
}

void pojav_frame_record(uint64_t start_ns, uint64_t end_ns,
                        uint64_t inter_swap_ns, bool has_inter_swap, bool submitted) {
    if (submitted) atomic_fetch_add_explicit(&submission_count, 1, memory_order_relaxed);
    record_timing(pojav_frame_timing_token(), start_ns, end_ns,
                  inter_swap_ns, has_inter_swap, submitted);
}

void pojav_frame_snapshot_read(pojav_frame_snapshot *out) {
    pthread_mutex_lock(&metrics_lock);
    *out = metrics;
    out->submissions = pojav_frame_submissions();
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
