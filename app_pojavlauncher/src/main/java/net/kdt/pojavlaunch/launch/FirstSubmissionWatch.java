package net.kdt.pojavlaunch.launch;

import net.kdt.pojavlaunch.utils.FpsCounter;

/**
 * One-shot, foreground-only submission observer. All calls run on the host UI
 * thread. It never controls rendering, launch readiness or dynamic resolution.
 * Native Vulkan is explicitly unobservable: stop this foreground run without
 * inventing a frame, and retry coverage on the next resume (window hints can change).
 */
public final class FirstSubmissionWatch {
    public interface Queue {
        boolean postDelayed(Runnable callback, long delayMillis);
        void remove(Runnable callback);
    }

    public interface Source {
        int coverage();
        long totalSubmissions();
    }

    public interface Host {
        boolean isUnavailable();
        void onFirstSubmission();
    }

    private static final long COUNTED_POLL_MS = 250L;
    private static final long UNREADY_MAX_POLL_MS = 2000L;
    private final Queue queue;
    private final Source source;
    private final Host host;
    private Runnable pending;
    private boolean foreground;
    private boolean observed;
    private boolean released;
    private long generation;
    private long unreadyDelayMillis = COUNTED_POLL_MS;

    public FirstSubmissionWatch(Queue queue, Source source, Host host) {
        this.queue = queue;
        this.source = source;
        this.host = host;
    }

    /** Idempotent within one foreground run, including unsupported coverage. */
    public void resume() {
        if (released || foreground || observed || host.isUnavailable()) return;
        foreground = true;
        unreadyDelayMillis = COUNTED_POLL_MS;
        final long run = ++generation;
        pending = () -> tick(run);
        if (!queue.postDelayed(pending, 0L)) pause();
    }

    /** Remove callbacks before the host advertises its paused state. */
    public void pause() {
        foreground = false;
        ++generation;
        if (pending != null) queue.remove(pending);
        pending = null;
    }

    public void release() {
        released = true;
        pause();
    }

    public boolean hasObservedSubmission() { return observed; }

    private void tick(long run) {
        if (run != generation || !foreground || released || observed || pending == null) return;
        if (host.isUnavailable()) { pause(); return; }
        int coverage = source.coverage();
        if (run != generation || !foreground) return;
        if (coverage == FpsCounter.COVERAGE_NATIVE_VULKAN_UNSUPPORTED) {
            // Keep foreground=true so duplicate resumes cannot restart polling.
            pending = null;
            return;
        }
        if (coverage == FpsCounter.COVERAGE_COUNTED) {
            long total = source.totalSubmissions();
            // Coverage may change while reading the cumulative counter. Do not
            // label a NO_API window using the preceding GL window's count.
            coverage = source.coverage();
            if (run != generation || !foreground) return;
            if (coverage == FpsCounter.COVERAGE_NATIVE_VULKAN_UNSUPPORTED) {
                pending = null;
                return;
            }
            if (coverage == FpsCounter.COVERAGE_COUNTED && total > 0L) {
                // This counter is cumulative since process start, not a rate
                // window: a late/resumed observer must not wait for an extra
                // frame after the first submission has already occurred.
                observed = true;
                pending = null;
                host.onFirstSubmission();
                return;
            }
        }
        long delay = COUNTED_POLL_MS;
        if (coverage != FpsCounter.COVERAGE_COUNTED) {
            delay = unreadyDelayMillis;
            unreadyDelayMillis = Math.min(UNREADY_MAX_POLL_MS, unreadyDelayMillis * 2L);
        } else {
            unreadyDelayMillis = COUNTED_POLL_MS;
        }
        if (!queue.postDelayed(pending, delay)) pause();
    }
}
