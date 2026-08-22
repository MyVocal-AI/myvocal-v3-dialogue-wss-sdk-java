package ai.myvocal.tts.v3dialogue;

public final class CallbackException extends SDKException {
    private final String callbackName;

    public CallbackException(String callbackName, Throwable cause) {
        super(callbackName + " callback failed: " + cause.getMessage(), cause);
        this.callbackName = callbackName;
    }

    public String getCallbackName() {
        return callbackName;
    }
}
