package android.os;
public class PowerManager {
    public int status, statusReads, headroomReads;
    public float headroom = Float.NaN;
    public int getCurrentThermalStatus() { statusReads++; return status; }
    public float getThermalHeadroom(int forecast) { headroomReads++; return headroom; }
}
