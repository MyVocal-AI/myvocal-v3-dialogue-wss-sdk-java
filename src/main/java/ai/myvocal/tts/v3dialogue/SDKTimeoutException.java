package ai.myvocal.tts.v3dialogue;

public final class SDKTimeoutException extends SDKException {
    public SDKTimeoutException(String message) {
        super(message);
    }

    public SDKTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
