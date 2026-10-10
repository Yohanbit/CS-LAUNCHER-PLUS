import net.kdt.pojavlaunch.utils.FrameCounterWindow;
import net.kdt.pojavlaunch.utils.FpsCounter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class MeasurementWindowTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rate(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 0.00001, message + ": " + actual);
    }
    public static void main(String[] args) throws Exception {
        FrameCounterWindow a = new FrameCounterWindow(200_000_000L);
        FrameCounterWindow b = new FrameCounterWindow(200_000_000L);
        check(Double.isNaN(a.sample(100, 0)), "baseline at time zero is valid");
        check(Double.isNaN(b.sample(100, 0)), "independent baseline");
        rate(a.sample(112, 200_000_000L), 60, "first reader rate");
        check(Double.isNaN(a.sample(113, 201_000_000L)), "short window retains baseline");
        rate(b.sample(124, 400_000_000L), 60, "second reader does not consume first's count");
        rate(a.sample(124, 400_000_000L), 60, "too-soon poll did not discard frames");
        rate(a.sample(124, 600_000_000L), 0, "zero stall is a real sample");
        check(a.getLastElapsedNanos() == 200_000_000L, "zero stall duration retained");
        rate(a.sample(2124, 800_000_000L), 10000, "no 1000 FPS cap");
        check(Double.isNaN(a.sample(1, 1_000_000_000L)), "counter reset rebases");
        rate(a.sample(13, 1_200_000_000L), 60, "rate after reset");
        check(Double.isNaN(a.sample(14, 1_100_000_000L)), "backward monotonic input rebases");
        check(Double.isNaN(a.sample(14, 1_100_000_000L)), "same time cannot divide by zero");
        check(Double.isNaN(a.sample(-1, 1_300_000_000L)), "unknown is not measured zero");
        check(a.getLastElapsedNanos() == 0, "unknown invalidates last duration");
        check(Double.isNaN(a.sample(1000, 1_500_000_000L)), "unknown gap rebases");
        a.reset();
        check(Double.isNaN(a.sample(9000, 90_000_000_000L)), "pause excludes background gap");
        rate(a.sample(9012, 90_200_000_000L), 60, "resume foreground rate");
        FrameCounterWindow huge = new FrameCounterWindow(1);
        huge.sample(0, 0);
        check(Double.isFinite(huge.sample(Long.MAX_VALUE, 1_000_000_000L)), "overflow-safe multiplication");
        try { new FrameCounterWindow(0); throw new AssertionError("invalid window accepted"); }
        catch (IllegalArgumentException expected) { }

        // Every concurrent reader owns its baseline; reset on one never touches others.
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] readers = new Thread[8];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                try {
                    FrameCounterWindow window = new FrameCounterWindow(200_000_000L);
                    ready.countDown(); start.await();
                    for (int n = 0; n < 10000; n++) {
                        long time = n * 400_000_000L;
                        window.reset(); window.sample(n * 24L, time);
                        rate(window.sample(n * 24L + 12, time + 200_000_000L), 60, "concurrent rate");
                    }
                } catch (Throwable t) { failure.compareAndSet(null, t); }
            });
            readers[i].start();
        }
        ready.await(); start.countDown();
        for (Thread reader : readers) reader.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(FpsCounter.getTotalPresents() == -1, "late native linkage returns unknown");
        check(new FpsCounter.Window().getFps() == -1, "late native linkage is not zero FPS");
        System.out.println("PASS measurement-window: independent readers, short windows, zero stalls, >1000 FPS, reset, pause, unknown, overflow, 8 concurrent readers");
    }
}
