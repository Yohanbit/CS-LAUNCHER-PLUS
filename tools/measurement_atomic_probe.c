/* Freestanding ARM code-generation probe when a complete NDK is unavailable.
 * Not a substitute for the full production-source NDK compile in the runner. */
#include <stdatomic.h>
_Static_assert(sizeof(unsigned long long) == 8, "64-bit counter required");
_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "frame counter must always be lock-free");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "timing gate must always be lock-free");
static _Atomic unsigned long long count;
static _Atomic unsigned int gate;
unsigned long long measurement_probe_read(void) { return atomic_load_explicit(&count, memory_order_relaxed); }
void measurement_probe_increment(void) { atomic_fetch_add_explicit(&count, 1, memory_order_relaxed); }
unsigned int measurement_probe_gate(void) { return atomic_load_explicit(&gate, memory_order_acquire); }
void measurement_probe_enable(unsigned int value) { atomic_store_explicit(&gate, value, memory_order_release); }
