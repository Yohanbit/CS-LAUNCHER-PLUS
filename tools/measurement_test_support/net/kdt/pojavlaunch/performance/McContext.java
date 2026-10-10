package net.kdt.pojavlaunch.performance;
public class McContext {
    public final String rendererId, versionId;
    public McContext(String rendererId, String versionId) {
        this.rendererId = rendererId == null ? "" : rendererId;
        this.versionId = versionId == null ? "" : versionId;
    }
}
