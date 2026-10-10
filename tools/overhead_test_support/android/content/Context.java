package android.content;
public class Context {
    public static final String POWER_SERVICE = "power";
    public android.os.PowerManager power = new android.os.PowerManager();
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String key) { return power; }
}
