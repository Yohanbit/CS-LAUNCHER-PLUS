#include "frame_metrics.h"
#include <assert.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <time.h>

static _Atomic unsigned long lock_calls, clock_calls;
int __real_pthread_mutex_lock(pthread_mutex_t *mutex);
int __wrap_pthread_mutex_lock(pthread_mutex_t *mutex) {
    atomic_fetch_add(&lock_calls, 1);
    return __real_pthread_mutex_lock(mutex);
}
int __real_clock_gettime(clockid_t id, struct timespec *value);
int __wrap_clock_gettime(clockid_t id, struct timespec *value) {
    atomic_fetch_add(&clock_calls, 1);
    return __real_clock_gettime(id, value);
}

static _Atomic unsigned writers_done;
static void *writer(void *unused) {
    (void)unused;
    for (unsigned i = 0; i < 100000; i++) {
        uint32_t token = pojav_frame_timing_token();
        pojav_frame_record_swap(true, token, 0, 0);
        pojav_frame_record_swap(false, token, 0, 0);
    }
    atomic_fetch_add(&writers_done, 1);
    return NULL;
}
static void *reader(void *unused) {
    (void)unused;
    uint64_t previous = 0;
    while (atomic_load(&writers_done) != 4) {
        uint64_t count = pojav_frame_submissions();
        assert(count >= previous);
        previous = count;
    }
    return NULL;
}

int main(void) {
    assert(pojav_frame_coverage() == 0);
    pojav_frame_set_coverage(1); assert(pojav_frame_coverage() == 1);
    pojav_frame_set_coverage(2); assert(pojav_frame_coverage() == 2);
    pojav_frame_set_coverage(4); assert(pojav_frame_coverage() == 0);
    assert((pojav_frame_timing_token() & 1u) == 0);
    pthread_t writers[4], readers[4];
    unsigned long locks = atomic_load(&lock_calls), clocks = atomic_load(&clock_calls);
    for (int i = 0; i < 4; i++) {
        assert(pthread_create(&writers[i], NULL, writer, NULL) == 0);
        assert(pthread_create(&readers[i], NULL, reader, NULL) == 0);
    }
    for (int i = 0; i < 4; i++) {
        assert(pthread_join(writers[i], NULL) == 0);
        assert(pthread_join(readers[i], NULL) == 0);
    }
    assert(pojav_frame_submissions() == 400000);
    assert(atomic_load(&lock_calls) == locks); /* no disabled hot-path mutex */
    assert(atomic_load(&clock_calls) == clocks); /* no disabled timing clock */
    pojav_frame_snapshot s;
    pojav_frame_snapshot_read(&s);
    assert(s.attempts == 0 && s.submissions == 400000);
    pojav_frame_set_timing_enabled(true);
    uint32_t first = pojav_frame_timing_token();
    assert(first & 1u);
    pojav_frame_record_swap(true, first, 100, 110);
    pojav_frame_record_swap(false, first, 130, 150);
    pojav_frame_snapshot_read(&s);
    assert(s.attempts == 2 && s.submissions == 400001 && s.unsuccessful_swaps == 1);
    assert(s.swap_ns_total == 30 && s.swap_ns_last == 20 && s.swap_ns_max == 20);
    assert(s.inter_swap_samples == 1 && s.inter_swap_ns_total == 20);
    pojav_frame_set_timing_enabled(false);
    pojav_frame_record_swap(true, first, 160, 170); /* old in-flight timing discarded */
    pojav_frame_snapshot_read(&s);
    assert(s.attempts == 0 && s.submissions == 400002);
    pojav_frame_set_timing_enabled(true);
    uint32_t second = pojav_frame_timing_token();
    assert(second != first);
    pojav_frame_record_swap(true, first, 180, 190);
    pojav_frame_record_swap(true, second, 1000, 1010);
    pojav_frame_snapshot_read(&s);
    assert(s.attempts == 1 && s.inter_swap_samples == 0); /* no paused gap */
    assert(s.submissions == 400004 && s.timing_epoch == second);
    pojav_frame_set_timing_enabled(true); /* duplicate enable does not reset */
    assert(pojav_frame_timing_token() == second);
    uint64_t c1 = 0, t1 = 0, c2 = 0, t2 = 0;
    assert(pojav_frame_rate(100, 1000000000, &c1, &t1) == 0);
    assert(pojav_frame_rate(100, 1000000000, &c2, &t2) == 0);
    assert(pojav_frame_rate(112, 1200000000, &c1, &t1) == 60);
    assert(pojav_frame_rate(124, 1400000000, &c2, &t2) == 60);
    assert(pojav_frame_rate(112, 1300000000, &c1, &t1) == -1);
    assert(pojav_frame_rate(112, 1400000000, &c1, &t1) == 0);
    assert(pojav_frame_rate(1, 1600000000, &c1, &t1) == 0); /* reset */
    assert(pojav_frame_rate(2, 1500000000, &c1, &t1) == 0); /* backwards */
    assert(pojav_frame_rate(1002, 1700000000, &c1, &t1) == 5000);
    assert(pojav_frame_rate(UINT64_MAX, 1900000000, &c1, &t1) == INT_MAX);
    pojav_frame_set_timing_enabled(false);
    puts("PASS measurement-native: 4 writers + 4 readers, 400000 non-destructive submissions, disabled no mutex/clock, timing epochs, stale swaps, failed-attempt denominator, gap reset, independent native rates");
    return 0;
}
