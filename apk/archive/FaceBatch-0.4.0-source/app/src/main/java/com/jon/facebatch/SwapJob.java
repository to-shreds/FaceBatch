package com.jon.facebatch;

import org.json.JSONObject;

public final class SwapJob {
    public final String sourceUri;
    public final String sourceName;
    public final String targetUri;
    public final String targetName;

    public SwapJob(String sourceUri, String sourceName, String targetUri, String targetName) {
        this.sourceUri = sourceUri;
        this.sourceName = sourceName;
        this.targetUri = targetUri;
        this.targetName = targetName;
    }

    public String label() {
        return UriTools.baseName(sourceName) + " → " + UriTools.baseName(targetName);
    }

    public JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("sourceUri", sourceUri);
        object.put("sourceName", sourceName);
        object.put("targetUri", targetUri);
        object.put("targetName", targetName);
        return object;
    }

    public static SwapJob fromJson(JSONObject object) {
        return new SwapJob(
                object.optString("sourceUri", ""),
                object.optString("sourceName", "source"),
                object.optString("targetUri", ""),
                object.optString("targetName", "target")
        );
    }
}
