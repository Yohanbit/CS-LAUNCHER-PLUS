#ifndef POJAV_FRAME_PACING_H
#define POJAV_FRAME_PACING_H
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

/* The launcher exports CS_UNLIMITED_FPS=1 for its default unlimited profile.
 * Manual/original omit it and preserve the game's requested swap interval.
 * This removes requested vsync, not Android BufferQueue backpressure or the
 * game's own manual frame limit. Explicit FORCE_VSYNC=true always wins. */
static inline int pojav_effective_swap_interval(int requested, bool force_vsync) {
    if (force_vsync) return 1;
    const char *unlimited = getenv("CS_UNLIMITED_FPS");
    if (unlimited != NULL && strcmp(unlimited, "1") == 0) return 0;
    return requested < 0 ? 0 : requested; /* adaptive vsync not advertised */
}

/* Private ANativeWindow ABI is not a validated portable Mali optimization. */
static inline bool pojav_private_swap_interval_enabled(void) {
    const char *value = getenv("POJAV_EXPERIMENTAL_NATIVE_SWAP_INTERVAL");
    return value != NULL && strcmp(value, "true") == 0;
}
#endif
