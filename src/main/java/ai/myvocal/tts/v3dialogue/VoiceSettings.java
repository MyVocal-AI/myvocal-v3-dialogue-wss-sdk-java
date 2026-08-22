package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;

public final class VoiceSettings {
    private final double stability;

    public VoiceSettings(double stability) {
        if (Double.isNaN(stability) || stability < 0.0 || stability > 1.0) {
            throw new ValidationException(
                "stability must be between 0 and 1");
        }
        this.stability = stability;
    }

    JSONObject toJson() {
        return new JSONObject().put("stability", stability);
    }
}
