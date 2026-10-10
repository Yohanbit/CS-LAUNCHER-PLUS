import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.kdt.pojavlaunch.prefs.*;
import org.json.JSONObject;

/** Production persistence/sync/file-lock implementations; Android and loader are dependency doubles. */
public class ConsolidationSettingsTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    private static SharedPreferences main(Context c) { return c.getSharedPreferences("cslauncher_settings",0); }
    private static void activate(Context c) { LauncherPreferences.DEFAULT_PREF=main(c); }
    public static void main(String[] args) throws Exception {
        File dir=new File(args[0]); dir.mkdirs();
        Context launcher=new Context(dir), game=new Context(dir);
        activate(launcher);
        main(launcher).edit().putString("perfMode","extreme").putInt("extremeManualFps",120).putBoolean("showFpsCounter",false).commit();
        SharedSettings.syncIntoPreferences(launcher);
        SettingsSaveManager.initDraft(launcher);
        SharedPreferences draft=SettingsSaveManager.getDraftPrefs(launcher);
        draft.edit().putString("perfMode","normal").commit();
        SettingsSaveManager.markDirty("perfMode");
        check(SettingsSaveManager.commitChanges(launcher),"autosave succeeds");
        check("normal".equals(main(launcher).getString("perfMode",null)),"reload does not roll Normal back to Extreme");
        check("normal".equals(CrossProcessState.getString(launcher,"perfMode",null)),"selected profile published");
        activate(game); SharedSettings.putBoolean(game,"showFpsCounter",true);
        activate(launcher);
        draft.edit().putString("extremeFpsMode","manual").putString("developer_gc_variant","ergonomic_defaults").commit();
        SettingsSaveManager.markDirty("extremeFpsMode"); SettingsSaveManager.markDirty("developer_gc_variant");
        check(SettingsSaveManager.commitChanges(launcher),"dropdown and input dirty edits save");
        check(main(launcher).getBoolean("showFpsCounter",false),"unrelated game edit imported, not overwritten by stale draft");
        check(CrossProcessState.getBoolean(game,"showFpsCounter",false),"unrelated shared edit survives");
        check("manual".equals(main(launcher).getString("extremeFpsMode",null)),"FPS dropdown survives reload");
        check("ergonomic_defaults".equals(main(launcher).getString("developer_gc_variant",null)),"GC dropdown survives reload");
        draft.edit().putInt("extremeManualFps",150).putBoolean("extremeDynamicResolution",true).putString("experimentalModWorkarounds","sentinel").commit();
        SettingsSaveManager.markDirty("extremeManualFps"); SettingsSaveManager.markDirty("extremeDynamicResolution");
        check(SettingsSaveManager.commitChanges(launcher),"typed shared slider/switch save");
        check(main(launcher).getInt("extremeManualFps",0)==150,"slider survives reload");
        check(main(launcher).getBoolean("extremeDynamicResolution",false),"switch survives reload");
        check(!main(launcher).contains("experimentalModWorkarounds"),"untouched draft key not saved");
        draft.edit().remove("developer_gc_variant").commit(); SettingsSaveManager.markDirty("developer_gc_variant");
        check(SettingsSaveManager.commitChanges(launcher),"removal saves");
        check(!main(launcher).contains("developer_gc_variant") && !CrossProcessState.contains(launcher,"developer_gc_variant"),"shared removal not reintroduced");
        CrossProcessState.put(game,"serverAddress","example.invalid:25565");
        activate(game); SharedSettings.putString(game,"perfMode","maximum");
        activate(launcher);
        check(SettingsSaveManager.commitChanges(launcher),"empty lifecycle flush succeeds");
        check("maximum".equals(CrossProcessState.getString(launcher,"perfMode",null)),"empty onPause does not republish old draft");
        draft.edit().putString("perfMode","normal").commit(); SettingsSaveManager.markDirty("perfMode");
        Context.MemoryPrefs memory=(Context.MemoryPrefs)main(launcher); memory.failCommit=true;
        check(!SettingsSaveManager.commitChanges(launcher),"main commit failure reported");
        check(SettingsSaveManager.hasUnsavedChanges(launcher),"failed edit retained for retry");
        memory.failCommit=false;
        check(SettingsSaveManager.commitChanges(launcher),"retry succeeds");
        check(!SettingsSaveManager.hasUnsavedChanges(launcher),"dirty state cleared only on success");
        draft.edit().putString("perfMode","extreme").commit(); SettingsSaveManager.markDirty("perfMode");
        check(SettingsSaveManager.resetLauncherSettings(launcher),"reset succeeds");
        for(String key:SharedSettings.SHARED_KEYS) {
            check(!CrossProcessState.contains(launcher,key),"reset removes shared key "+key);
            check(!main(launcher).contains(key),"reset does not reload shared key "+key);
        }
        check("example.invalid:25565".equals(CrossProcessState.getString(launcher,"serverAddress",null)),"reset preserves foreign state");
        check(draft.getAll().isEmpty() && !SettingsSaveManager.hasUnsavedChanges(launcher),"reset clears pending/draft");
        activate(game); SharedSettings.syncIntoPreferences(game);
        check(!main(game).contains("perfMode"),"other process stale cache cleared after reset");
        check(!CrossProcessState.contains(game,"perfMode"),"other process cannot bootstrap old setting");
        activate(launcher);
        File f=new File(dir,"cs_shared_state.json");
        Files.write(f.toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        JSONObject recovered=CrossProcessState.readAll(launcher);
        check(!recovered.has("perfMode") && recovered.has("serverAddress"),"backup sanitized; corruption cannot undo reset");
        SharedSettings.syncIntoPreferences(launcher);
        check(!main(launcher).contains("perfMode"),"recovery/reload remains reset");
        JSONObject repair=new JSONObject(); repair.put("otherKey",17);
        check(CrossProcessState.merge(launcher,repair),"merge recovers sanitized backup");
        check(!CrossProcessState.contains(launcher,"perfMode"),"merge recovery cannot resurrect reset key");
        draft.edit().putBoolean("experimentalModWorkarounds",true).commit();
        check(SettingsSaveManager.saveToMain(launcher,"experimentalModWorkarounds"),"nonshared opt-in saves by key");
        check(main(launcher).getBoolean("experimentalModWorkarounds",false),"mod workaround opt-in persisted");
        check(!CrossProcessState.contains(launcher,"experimentalModWorkarounds"),"launcher-only opt-in not cross-process state");
        File isolated=new File(dir,"no-foreign-state"); isolated.mkdirs();
        Context cleanLauncher=new Context(isolated), staleGame=new Context(isolated);
        activate(cleanLauncher); SharedSettings.putString(cleanLauncher,"perfMode","extreme");
        activate(staleGame); SharedSettings.syncIntoPreferences(staleGame);
        check("extreme".equals(main(staleGame).getString("perfMode",null)),"second cache contains old profile");
        activate(cleanLauncher);
        check(SettingsSaveManager.resetLauncherSettings(cleanLauncher),"reset without foreign state succeeds");
        activate(staleGame); SharedSettings.syncIntoPreferences(staleGame);
        check(!main(staleGame).contains("perfMode") && !CrossProcessState.contains(staleGame,"perfMode"),"reset marker prevents empty-state bootstrap from stale process");
        activate(launcher);
        // Real file-lock/rename path: separate JVMs do not share the in-process IO_GUARD.
        Process a=worker(dir,"workerA"), b=worker(dir,"workerB");
        check(a.waitFor()==0 && b.waitFor()==0,"independent JVM writers succeed");
        check(CrossProcessState.getInt(launcher,"workerA",-1)==49 && CrossProcessState.getInt(launcher,"workerB",-1)==49,"interprocess writes preserve unrelated keys");
        System.out.println("PASS ConsolidationSettingsTest: "+checks+" assertions (production persistence, synthetic Android caches)");
    }
    private static Process worker(File dir,String key) throws Exception {
        return new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"ConsolidationStateWriter",dir.toString(),key).inheritIO().start();
    }
}
