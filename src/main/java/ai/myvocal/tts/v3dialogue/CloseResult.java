package ai.myvocal.tts.v3dialogue;

public final class CloseResult {
    private final int closeCode;

    CloseResult(int closeCode) {
        this.closeCode = closeCode;
    }

    public int getCloseCode() {
        return closeCode;
    }
}
