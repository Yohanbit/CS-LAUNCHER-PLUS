#ifndef POJAV_FRAME_METRICS_H
#define POJAV_FRAME_METRICS_H
#include <stdbool.h>
#include <stdint.h>

/* CPU wall-clock measurements, not GPU timers or display presentation times.
 * Counters cover the launcher main window only. Native Vulkan bypasses this
 * bridge and is deliberately unknown (not a measured zero). Only successful
 * EGL window swaps / OSMesa unlockAndPost calls increment the cumulative count.
 * Timing totals describe ALL measured main-window swap attempts, including
 * failed / dummy-window attempts, and exist only in an opt-in timing epoch. */
typedef struct {
    uint64_t attempts;
    uint64_t submissions;
    uint64_t unsuccessful_swaps;
    uint64_t swap_ns_total;
    uint64_t swap_ns_last;
    uint64_t swap_ns_max;
    uint64_t inter_swap_ns_total;
    uint64_t inter_swap_samples;
    uint64_t inter_swap_ns_last;
    uint64_t sample_time_ns;
    uint32_t timing_epoch;
} pojav_frame_snapshot;

uint64_t pojav_monotonic_ns(void);
/* Always-on, non-destructive, lock-free successful-submission count. */
uint64_t pojav_frame_submissions(void);
/* 0 = unknown/uninstrumented, 1 = EGL window swaps, 2 = OSMesa posts.
 * Published atomically by bridge initialization/API selection, not by readers
 * inspecting mutable renderer configuration across threads. */
void pojav_frame_set_coverage(unsigned int coverage);
unsigned int pojav_frame_coverage(void);
/* Low bit enables timing; remaining bits identify a fresh diagnostic epoch.
 * Capture the token before a swap; an in-flight old epoch is never recorded. */
uint32_t pojav_frame_timing_token(void);
void pojav_frame_set_timing_enabled(bool enabled);
void pojav_frame_record_swap(bool submitted, uint32_t token,
                             uint64_t start_ns, uint64_t end_ns);
/* Compatibility helper. Gated by the current epoch; cumulative count always
 * increments on success. New bridges should use record_swap's captured token. */
void pojav_frame_record(uint64_t start_ns, uint64_t end_ns,
                        uint64_t inter_swap_ns, bool has_inter_swap,
                        bool submitted);
void pojav_frame_snapshot_read(pojav_frame_snapshot *out);
/* Non-destructive, independent-reader rate calculation. -1 means too soon. */
int pojav_frame_rate(uint64_t count, uint64_t now_ns,
                     uint64_t *previous_count, uint64_t *previous_time_ns);
#endif
