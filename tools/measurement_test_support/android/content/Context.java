package android.content;
public class Context {
    public static final String POWER_SERVICE = "power";
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String key) { return new android.os.PowerManager(); }
}
