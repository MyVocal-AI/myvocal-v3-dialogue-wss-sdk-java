import ai.myvocal.tts.v3dialogue.CloseResult;
import ai.myvocal.tts.v3dialogue.ConnectionOptions;
import ai.myvocal.tts.v3dialogue.ContextOptions;
import ai.myvocal.tts.v3dialogue.ContextResult;
import ai.myvocal.tts.v3dialogue.DialogueListener;
import ai.myvocal.tts.v3dialogue.Input;
import ai.myvocal.tts.v3dialogue.MultiContextClient;
import ai.myvocal.tts.v3dialogue.SDKException;
import ai.myvocal.tts.v3dialogue.ServerEvent;
import ai.myvocal.tts.v3dialogue.VoiceSettings;
import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public final class BasicExample {
    public static void main(String[] args) {
        String apiKey = required("MYVOCAL_TTS_API_KEY");
        String voiceId = required("MYVOCAL_VOICE_ID");
        final AtomicLong audioBytes = new AtomicLong();

        DialogueListener listener = new DialogueListener() {
            @Override public void onAudio(
                String contextId, byte[] audio, JSONObject alignment) {
                audioBytes.addAndGet(audio.length);
                System.out.println(
                    "audio " + contextId + " " + audio.length);
            }

            @Override public void onEvent(ServerEvent event) {
            }

            @Override public void onError(SDKException error) {
                System.err.println(error.getClass().getSimpleName()
                    + ": " + error.getMessage());
            }

            @Override public void onContextFinal(ContextResult result) {
                System.out.println("final " + result.getContextId());
            }
        };

        ConnectionOptions options = ConnectionOptions.builder()
            .format("mp3_128")
            .languageCode("en")
            .timestamps(true)
            .build();
        MultiContextClient client =
            new MultiContextClient(apiKey, options, listener);
        client.connect(30000);
        client.openContext(new ContextOptions(
            "example-a",
            Collections.singletonList(voiceId),
            new VoiceSettings(0.5),
            Collections.singletonList(new Input(
                "Hello from the first context.", voiceId, true))));
        client.openContext(new ContextOptions(
            "example-b",
            Collections.singletonList(voiceId),
            new VoiceSettings(0.5),
            Collections.singletonList(new Input(
                "Hello from the second context.", voiceId, true))));
        CloseResult result = client.closeSocket(60000);
        System.out.println("close " + result.getCloseCode()
            + " audio_bytes " + audioBytes.get());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.length() == 0) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
