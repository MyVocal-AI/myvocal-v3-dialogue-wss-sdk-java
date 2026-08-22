package ai.myvocal.tts.v3dialogue;

public final class ProtocolException extends SDKException {
    private final String code;
    private final String field;
    private final String contextId;
    private final Integer closeCode;

    public ProtocolException(String code, String message, String field) {
        this(code, message, field, null, null);
    }

    public ProtocolException(
        String code,
        String message,
        String field,
        String contextId,
        Integer closeCode
    ) {
        super(message);
        this.code = code;
        this.field = field;
        this.contextId = contextId;
        this.closeCode = closeCode;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }

    public String getContextId() {
        return contextId;
    }

    public Integer getCloseCode() {
        return closeCode;
    }
}
