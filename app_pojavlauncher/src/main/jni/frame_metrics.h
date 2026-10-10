#ifndef POJAV_FRAME_METRICS_H
#define POJAV_FRAME_METRICS_H
#include <stdbool.h>
#include <stdint.h>

/* CPU wall-clock measurements, not GPU timers or display presentation times.
 * Counters cover the launcher main window only. Native Vulkan bypasses this
 * bridge and is deliberately not counted. */
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
} pojav_frame_snapshot;

uint64_t pojav_monotonic_ns(void);
void pojav_frame_record(uint64_t start_ns, uint64_t end_ns,
                        uint64_t inter_swap_ns, bool has_inter_swap,
                        bool submitted);
void pojav_frame_snapshot_read(pojav_frame_snapshot *out);
/* Non-destructive, independent-reader rate calculation. -1 means too soon. */
int pojav_frame_rate(uint64_t count, uint64_t now_ns,
                     uint64_t *previous_count, uint64_t *previous_time_ns);
#endif
