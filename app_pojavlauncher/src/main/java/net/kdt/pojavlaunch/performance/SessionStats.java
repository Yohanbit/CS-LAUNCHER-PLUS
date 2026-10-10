package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.util.AtomicFile;

import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small rolling record of sampled swap submission rates, keyed by
 * renderer + profile + game. This is descriptive history, not a controlled renderer benchmark:
 * resolution, world, mods and FPS cap are not recorded.
 *
 * <p>Written once per session at teardown, read once when the settings card is built. Never on the
 * launch path, never per frame.
 *
 * <p>What "fps" means here is honest: the sample is derived from the present counter
 * ({@link net.kdt.pojavlaunch.utils.FpsCounter}, the swap-boundary present counter) divided by wall time over
 * the sampled window, and "worst second" is the lowest 1-second window, not a statistical 1% low. True
 * render-loop percentiles need native frame timings. {@code dumpsys gfxinfo} can describe launcher UI
 * frames rather than Minecraft's native frames and is not sufficient on its own.
 */
public final class SessionStats {

    private static final String DIR = "performance";
    private static final String FILE = "sessions.json";
    private static final int CAP = 64;
    private static final long MIN_SESSION_MS = 30_000L; // anything shorter is noise

    private SessionStats() {}

    /** One finished session, already reduced to numbers. */
    public static final class Entry {
        public final long timestamp;
        public final String rendererId;
        public final String modeKey;
        public final String versionId;
        public final int medianFps;
        public final int worstSecondFps;
        public final float medianFrameMs;
        public final long durationMs;
        public final int thermalPeak;

        Entry(long timestamp, String rendererId, String modeKey, String versionId, int medianFps,
              int worstSecondFps, float medianFrameMs, long durationMs, int thermalPeak) {
            this.timestamp = timestamp;
            this.rendererId = rendererId == null ? "" : rendererId;
            this.modeKey = modeKey == null ? "" : modeKey;
            this.versionId = versionId == null ? "" : versionId;
            this.medianFps = medianFps;
            this.worstSecondFps = worstSecondFps;
            this.medianFrameMs = medianFrameMs;
            this.durationMs = durationMs;
            this.thermalPeak = thermalPeak;
        }

        String toJson() {
            return "{\"t\":" + timestamp + ",\"r\":" + quote(rendererId) + ",\"m\":" + quote(modeKey)
                    + ",\"v\":" + quote(versionId) + ",\"fps\":" + medianFps + ",\"worst\":" + worstSecondFps
                    + ",\"ms\":" + (Float.isNaN(medianFrameMs) || Float.isInfinite(medianFrameMs)
                    ? "null" : Float.toString(medianFrameMs))
                    + ",\"dur\":" + durationMs + ",\"th\":" + thermalPeak + "}";
        }

        private static String quote(String s) {
            return JSONObject.quote(s);
        }
    }

    public static File file(Context ctx) {
        return new File(new File(Tools.DIR_DATA, DIR), FILE);
    }

    public static synchronized void append(Context ctx, Entry e) {
        if (ctx == null || e == null || e.durationMs < MIN_SESSION_MS) return;
        try {
            File out = file(ctx);
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            AtomicFile atomic = new AtomicFile(out);
            JSONArray previous = new JSONArray();
            if (out.isFile() || new File(out.getPath() + ".bak").isFile()) {
                byte[] raw = atomic.readFully();
                try {
                    previous = new JSONArray(new String(raw, StandardCharsets.UTF_8));
                } catch (org.json.JSONException corrupt) {
                    // Preserve pre-fix malformed history before starting a valid rolling array.
                    // If preservation fails, abort rather than silently losing the old data.
                    File saved = new File(out.getPath() + ".corrupt-" + System.currentTimeMillis());
                    try (FileOutputStream backup = new FileOutputStream(saved)) {
                        backup.write(raw);
                        backup.getFD().sync();
                    }
                }
            }
            List<JSONObject> entries = new ArrayList<>();
            for (int i = 0; i < previous.length(); i++) {
                JSONObject object = previous.optJSONObject(i);
                if (object != null) entries.add(object);
            }
            entries.add(new JSONObject(e.toJson()));
            JSONArray next = new JSONArray();
            for (int i = Math.max(0, entries.size() - CAP); i < entries.size(); i++) {
                next.put(entries.get(i));
            }
            FileOutputStream stream = null;
            try {
                stream = atomic.startWrite();
                stream.write(next.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(stream);
            } catch (Throwable failure) {
                if (stream != null) atomic.failWrite(stream);
                throw failure;
            }
        } catch (Throwable ignored) {
            // stats are diagnostics; they must never break a shutdown path
        }
    }

    /** Descriptive median of mixed-session medians; not evidence that one renderer is faster. */
    public static synchronized Map<String, Float> bestMedianFpsByRenderer(Context ctx) {
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        if (ctx == null) return new HashMap<>();
        try {
            File f = file(ctx);
            JSONArray arr = readEntries(f);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String r = o.optString("r", "");
                int fps = o.optInt("fps", 0);
                if (r.isEmpty() || fps <= 0 || o.optLong("dur", 0) < MIN_SESSION_MS) continue;
                List<Integer> list = buckets.get(r);
                if (list == null) buckets.put(r, list = new ArrayList<>());
                list.add(fps);
            }
        } catch (Throwable ignored) {
            return new HashMap<>();
        }
        Map<String, Float> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            List<Integer> vals = e.getValue();
            if (vals.isEmpty()) continue;
            Integer[] copy = vals.toArray(new Integer[0]);
            java.util.Arrays.sort(copy);
            int middle = copy.length / 2;
            out.put(e.getKey(), copy.length % 2 == 0
                    ? (copy[middle - 1] / 2f + copy[middle] / 2f) : (float) copy[middle]);
        }
        return out;
    }

    private static JSONArray readEntries(File file) throws Exception {
        if (!file.isFile() && !new File(file.getPath() + ".bak").isFile()) return new JSONArray();
        return new JSONArray(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }

    /** Recent sessions for the given renderer/mode pair, newest first — not controlled comparisons. */
    public static synchronized String describeRecent(Context ctx, String rendererId, String modeKey) {
        if (ctx == null) return "";
        try {
            File f = file(ctx);
            JSONArray arr = readEntries(f);
            List<String> hits = new ArrayList<>();
            for (int i = arr.length() - 1; i >= 0 && hits.size() < 3; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (rendererId != null && !rendererId.equals(o.optString("r", ""))) continue;
                if (modeKey != null && !modeKey.equals(o.optString("m", ""))) continue;
                double inverseRateMs = o.optDouble("ms", Double.NaN);
                String timing = Double.isNaN(inverseRateMs) || Double.isInfinite(inverseRateMs)
                        ? "inverse median rate unavailable"
                        : String.format(Locale.ROOT, "%.1f ms inverse median rate (not frame timing)", inverseRateMs);
                hits.add(String.format(Locale.ROOT, "%d swap fps (worst second %d, %s)",
                        o.optInt("fps"), o.optInt("worst"), timing));
            }
            if (hits.isEmpty()) return "";
            StringBuilder b = new StringBuilder("Session history (scene/settings may differ)");
            for (String h : hits) b.append("\n").append(h);
            return b.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
