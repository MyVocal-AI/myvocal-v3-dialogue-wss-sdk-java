package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LiveProbeTest {
    @Test
    public void liveTwoContextsCloseSocket() throws Exception {
        String apiKey = System.getenv("MYVOCAL_TTS_API_KEY");
        String voiceId = System.getenv("MYVOCAL_VOICE_ID");
        Assume.assumeTrue(apiKey != null && voiceId != null);

        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final AtomicInteger chunks = new AtomicInteger();
        final AtomicInteger alignments = new AtomicInteger();
        final AtomicInteger turnFinals = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();

        DialogueListener listener = new DialogueListener() {
            @Override public synchronized void onAudio(
                String contextId, byte[] value, JSONObject alignment) {
                assertTrue(contextId.equals("java-live-a")
                    || contextId.equals("java-live-b"));
                audio.write(value, 0, value.length);
                chunks.incrementAndGet();
                if (alignment != null) alignments.incrementAndGet();
            }

            @Override public void onEvent(ServerEvent event) {
                if (event.getRaw().optBoolean(
                    "is_final_audio_for_turn", false)) {
                    turnFinals.incrementAndGet();
                }
            }

            @Override public void onError(SDKException error) {
                errors.incrementAndGet();
            }
        };

        ConnectionOptions options = ConnectionOptions.builder()
            .format("pcm_16000")
            .languageCode("en")
            .timestamps(true)
            .textNormalization("auto")
            .seed(821002L)
            .enableLogging(false)
            .build();
        MultiContextClient client =
            new MultiContextClient(apiKey, options, listener);
        client.connect(10000);
        client.openContext(new ContextOptions(
            "java-live-a",
            Collections.singletonList(voiceId),
            new VoiceSettings(0.5),
            Collections.singletonList(new Input(
                "Hello from the first SDK context.", voiceId, true))));
        client.openContext(new ContextOptions(
            "java-live-b",
            Collections.singletonList(voiceId),
            new VoiceSettings(0.5),
            Collections.singletonList(new Input(
                "Hello from the second SDK context.", voiceId, true))));
        CloseResult result = client.closeSocket(60000);

        byte[] value = audio.toByteArray();
        assertEquals(1000, result.getCloseCode());
        assertTrue(client.contextDone("java-live-a"));
        assertTrue(client.contextDone("java-live-b"));
        assertTrue(chunks.get() > 0);
        assertTrue(value.length > 0);
        assertTrue(alignments.get() > 0);
        assertEquals(0, errors.get());
        System.out.println(new JSONObject()
            .put("sdk", "java")
            .put("contextsFinal", 2)
            .put("turnFinals", turnFinals.get())
            .put("audioChunks", chunks.get())
            .put("audioBytes", value.length)
            .put("audioSha256", hex(
                MessageDigest.getInstance("SHA-256").digest(value)))
            .put("alignmentEvents", alignments.get())
            .put("closeCode", result.getCloseCode())
            .put("pass", true)
            .toString());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
