package org.json;
/** Compile-only JSON seam; round3 tests do not assert Android JSON persistence behaviour. */
public class JSONObject {
    public JSONObject() {}
    public JSONObject(String text) {}
    public JSONObject put(String key, Object value) { return this; }
    public String optString(String key, String fallback) { return fallback; }
    public int optInt(String key, int fallback) { return fallback; }
    public boolean optBoolean(String key, boolean fallback) { return fallback; }
    public JSONArray optJSONArray(String key) { return null; }
}
