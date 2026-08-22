package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;

public final class Input {
    private final String text;
    private final String voiceId;
    private final boolean newTurn;

    public Input(String text, String voiceId) {
        this(text, voiceId, false);
    }

    public Input(String text, String voiceId, boolean newTurn) {
        if (text == null || text.length() == 0) {
            throw new ValidationException("input text must not be empty");
        }
        if (voiceId == null || voiceId.length() == 0) {
            throw new ValidationException("voice_id must not be empty");
        }
        this.text = text;
        this.voiceId = voiceId;
        this.newTurn = newTurn;
    }

    public String getVoiceId() {
        return voiceId;
    }

    JSONObject toJson() {
        JSONObject value = new JSONObject()
            .put("text", text)
            .put("voice_id", voiceId);
        if (newTurn) {
            value.put("new_turn", true);
        }
        return value;
    }
}
