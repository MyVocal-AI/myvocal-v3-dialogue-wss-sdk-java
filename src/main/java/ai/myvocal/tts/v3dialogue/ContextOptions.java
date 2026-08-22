package ai.myvocal.tts.v3dialogue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ContextOptions {
    private final String contextId;
    private final List<String> voices;
    private final VoiceSettings voiceSettings;
    private final List<Input> inputs;

    public ContextOptions(String contextId, List<String> voices) {
        this(contextId, voices, null, null);
    }

    public ContextOptions(
        String contextId,
        List<String> voices,
        VoiceSettings voiceSettings,
        List<Input> inputs
    ) {
        if (contextId == null || contextId.length() == 0) {
            throw new ValidationException("context_id must not be empty");
        }
        if (voices == null || voices.size() < 1 || voices.size() > 10) {
            throw new ValidationException(
                "voices must contain between 1 and 10 items");
        }
        Set<String> registered = new HashSet<String>();
        for (String voice : voices) {
            if (voice == null || voice.length() == 0) {
                throw new ValidationException(
                    "voice ids must not be empty");
            }
            if (!registered.add(voice)) {
                throw new ValidationException("voice ids must be unique");
            }
        }
        List<Input> safeInputs = inputs == null
            ? new ArrayList<Input>() : new ArrayList<Input>(inputs);
        for (Input input : safeInputs) {
            if (!registered.contains(input.getVoiceId())) {
                throw new ValidationException(
                    "input voice_id must be registered");
            }
        }
        this.contextId = contextId;
        this.voices = new ArrayList<String>(voices);
        this.voiceSettings = voiceSettings;
        this.inputs = safeInputs;
    }

    public String getContextId() {
        return contextId;
    }

    public List<String> getVoices() {
        return new ArrayList<String>(voices);
    }

    public JSONObject toJson() {
        JSONObject value = new JSONObject()
            .put("context_id", contextId)
            .put("voices", new JSONArray(voices));
        if (voiceSettings != null) {
            value.put("voice_settings", voiceSettings.toJson());
        }
        if (!inputs.isEmpty()) {
            JSONArray values = new JSONArray();
            for (Input input : inputs) {
                values.put(input.toJson());
            }
            value.put("inputs", values);
        }
        return value;
    }
}
