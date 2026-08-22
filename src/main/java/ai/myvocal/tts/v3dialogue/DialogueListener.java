package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;

public interface DialogueListener {
    void onAudio(String contextId, byte[] audio, JSONObject alignment);
    void onEvent(ServerEvent event);
    void onError(SDKException error);

    default void onContextFinal(ContextResult result) {
    }

    default void onClose(int code, String reason) {
    }
}
