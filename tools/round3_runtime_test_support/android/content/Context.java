package android.content;
import android.content.pm.PackageManager;
public class Context {
    public static final String ACTIVITY_SERVICE = "activity";
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public PackageManager getPackageManager() { return new PackageManager(); }
}
