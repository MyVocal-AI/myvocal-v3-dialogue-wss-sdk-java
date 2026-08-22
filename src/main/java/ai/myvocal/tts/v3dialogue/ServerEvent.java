package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;

public final class ServerEvent {
    private final JSONObject raw;

    ServerEvent(JSONObject raw) {
        this.raw = new JSONObject(raw.toString());
    }

    public String getContextId() {
        return raw.optString("context_id", null);
    }

    public JSONObject getRaw() {
        return new JSONObject(raw.toString());
    }
}
