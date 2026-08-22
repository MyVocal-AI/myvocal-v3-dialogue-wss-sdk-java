package ai.myvocal.tts.v3dialogue;

public class SDKException extends RuntimeException {
    public SDKException(String message) {
        super(message);
    }

    public SDKException(String message, Throwable cause) {
        super(message, cause);
    }
}
