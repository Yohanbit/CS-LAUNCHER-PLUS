import android.content.Context;
import java.io.File;
import net.kdt.pojavlaunch.prefs.CrossProcessState;
import org.json.JSONObject;
public class ConsolidationStateWriter {
    public static void main(String[] args) {
        Context context=new Context(new File(args[0]));
        for(int i=0;i<50;i++) {
            JSONObject patch=new JSONObject(); patch.put(args[1],i);
            if(!CrossProcessState.merge(context,patch)) throw new AssertionError("write failed");
        }
    }
}
