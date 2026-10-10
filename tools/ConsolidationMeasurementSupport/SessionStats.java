package net.kdt.pojavlaunch.performance;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public class SessionStats {
    public static final List<Entry> ENTRIES=new ArrayList<>();
    public static void append(Context c,Entry e) { ENTRIES.add(e); }
    public static class Entry {
        public final int thermal;
        Entry(long timestamp,String renderer,String mode,String version,int median,int worst,float inverse,long duration,int thermal) { this.thermal=thermal; }
    }
}
