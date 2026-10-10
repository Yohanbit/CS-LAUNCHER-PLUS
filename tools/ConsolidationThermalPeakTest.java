import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.performance.FrameSampleCollector;
import net.kdt.pojavlaunch.performance.SessionStats;
import net.kdt.pojavlaunch.performance.ThermalGovernor;
public class ConsolidationThermalPeakTest {
    static int checks;
    static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    static Field field(Class<?> c,String n) throws Exception { Field f=c.getDeclaredField(n); f.setAccessible(true); return f; }
    static Object session() throws Exception { return field(FrameSampleCollector.class,"session").get(null); }
    static void seed(Object s) throws Exception {
        Arrays.fill((int[])field(s.getClass(),"samples").get(s),60);
        field(s.getClass(),"size").setInt(s,10);
        field(s.getClass(),"observedNanos").setLong(s,31_000_000_000L);
    }
    public static void main(String[] args) throws Exception {
        Context ctx=new Context(); Tools.LOCAL_RENDERER="opengles2"; Tools.DIR_DATA=args[0];
        LauncherPreferences.DEFAULT_PREF.diagnostics=true;
        ThermalGovernor.peak=3;
        FrameSampleCollector.begin(ctx,"opengles2","test"); Object first=session(); seed(first);
        // A hot governor observation immediately before pause must not depend on the next 1s sampler tick.
        FrameSampleCollector.pause();
        check(HandlerThread.liveCount()==0 && Handler.pendingCount()==0,"pause has no background heartbeat");
        ThermalGovernor.peak=1; // actual governor start resets the foreground-segment peak on resume
        FrameSampleCollector.begin(ctx,"opengles2","test");
        check(first==session(),"same-session resume retains samples and thermal peak");
        Handler.take("CS-FrameStats").run();
        FrameSampleCollector.finishAndClear(ctx);
        check(SessionStats.ENTRIES.size()==1,"one completed session persisted");
        check(SessionStats.ENTRIES.get(0).thermal==3,"hot before pause / cool after resume keeps hottest level");
        check(HandlerThread.liveCount()==0 && Handler.pendingCount()==0,"finish fully releases sampler");
        ThermalGovernor.peak=0;
        FrameSampleCollector.begin(ctx,"opengles2","new"); seed(session());
        FrameSampleCollector.finishAndClear(ctx);
        check(SessionStats.ENTRIES.get(1).thermal==0,"new real session cannot inherit old thermal peak");
        LauncherPreferences.DEFAULT_PREF.diagnostics=false;
        FrameSampleCollector.begin(ctx,"opengles2","off");
        check(HandlerThread.liveCount()==0 && session()==null,"default-off still creates no sampler/session");
        System.out.println("PASS ConsolidationThermalPeakTest: "+checks+" assertions (production collector, synthetic thermal observations)");
    }
}
