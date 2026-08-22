package ai.myvocal.tts.v3dialogue;

public final class ContextResult {
    private final String contextId;
    private final byte[] audio;

    ContextResult(String contextId, byte[] audio) {
        this.contextId = contextId;
        this.audio = audio.clone();
    }

    public String getContextId() {
        return contextId;
    }

    public byte[] getAudio() {
        return audio.clone();
    }
}
