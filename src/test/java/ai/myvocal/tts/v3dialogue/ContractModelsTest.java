package ai.myvocal.tts.v3dialogue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ContractModelsTest {
    @Test
    public void languageCodesReachTheConnectionQuery() {
        for (String model : new String[]{"myvocal_v3", "myvocal_v4", "myvocal_v3_turbo"}) {
            for (String code : new String[]{"en", "zh", "ast", "yue", "ceb", "fil", "ckb", "kam"}) {
                String url = ConnectionOptions.builder().modelId(model)
                    .languageCode(code.toUpperCase(java.util.Locale.ROOT)).build().buildUrl();
                assertTrue(url.contains("language_code=" + code));
                assertTrue(url.contains("model_id=" + model));
            }
        }
    }

    @Test
    public void languageCodeFormatRemainsValidated() {
        for (final String code : new String[]{"e", "engl", "en-US", "a1", " yue", "\u7ca4\u8bed", ""}) {
            expectValidation(new Runnable() {
                @Override public void run() {
                    ConnectionOptions.builder().languageCode(code).build();
                }
            });
        }
    }

    @Test
    public void modelSelectionIsEncodedOnceWithoutWhitelist() throws Exception {
        for (String model : new String[] {
            "myvocal_v3", "myvocal_v4", "myvocal_v3_turbo", "future/model +1"
        }) {
            String query = new java.net.URI(ConnectionOptions.builder()
                .modelId(model).build().buildUrl()).getRawQuery();
            int count = 0;
            for (String pair : query.split("&")) {
                if (pair.startsWith("model_id=")) {
                    count++;
                    assertEquals(model, java.net.URLDecoder.decode(
                        pair.substring("model_id=".length()), "UTF-8"));
                }
            }
            assertEquals(1, count);
        }
    }

    @Test
    public void urlContainsFixedQueryAndDefaults() {
        String url = ConnectionOptions.builder().build().buildUrl();
        assertTrue(url.contains("priority=dedicated_concurrency"));
        assertTrue(url.contains("model_id=myvocal_v3"));
        assertTrue(url.contains("format=mp3_128"));
        assertTrue(url.contains("timestamps=false"));
        assertTrue(url.contains("text_normalization=on"));
        assertTrue(url.contains("enable_logging=true"));
        assertFalse(url.contains("seed="));
    }

    @Test
    public void acceptsAllPublicFormats() {
        String[] formats = {
            "mp3_64", "mp3_96", "mp3_128", "mp3_192",
            "pcm_8000", "pcm_16000", "pcm_22050", "pcm_24000",
            "pcm_44100", "ulaw_8000"
        };
        for (String format : formats) {
            assertTrue(ConnectionOptions.builder().format(format).build()
                .buildUrl().contains("format=" + format));
        }
    }

    @Test
    public void validatesSeedLanguageAndStability() {
        assertTrue(ConnectionOptions.builder().seed(1L).languageCode("EN")
            .build().buildUrl().contains("language_code=en"));
        assertTrue(ConnectionOptions.builder().seed(4294967295L)
            .build().buildUrl().contains("seed=4294967295"));
        expectValidation(new Runnable() {
            @Override public void run() {
                ConnectionOptions.builder().seed(0L).build();
            }
        });
        expectValidation(new Runnable() {
            @Override public void run() {
                ConnectionOptions.builder().seed(4294967296L).build();
            }
        });
        expectValidation(new Runnable() {
            @Override public void run() {
                ConnectionOptions.builder().languageCode("english").build();
            }
        });
        new VoiceSettings(0.0);
        new VoiceSettings(1.0);
        expectValidation(new Runnable() {
            @Override public void run() {
                new VoiceSettings(1.01);
            }
        });
    }

    @Test
    public void wireShapeOmitsOptionalValues() {
        ContextOptions options = new ContextOptions(
            "ctx", Collections.singletonList("voice-a"),
            new VoiceSettings(0.5),
            Collections.singletonList(new Input("Hello", "voice-a"))
        );
        JSONObject value = options.toJson();
        assertEquals("ctx", value.getString("context_id"));
        assertEquals(1, value.getJSONArray("voices").length());
        assertEquals(0.5,
            value.getJSONObject("voice_settings").getDouble("stability"), 0.0);
        JSONObject input = value.getJSONArray("inputs").getJSONObject(0);
        assertFalse(input.has("new_turn"));
    }

    @Test
    public void contextRejectsVoiceLimitAndUnregisteredInput() {
        expectValidation(new Runnable() {
            @Override public void run() {
                new ContextOptions("ctx", Arrays.asList(
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"));
            }
        });
        expectValidation(new Runnable() {
            @Override public void run() {
                new ContextOptions(
                    "ctx", Collections.singletonList("voice-a"), null,
                    Collections.singletonList(new Input("Hello", "voice-b")));
            }
        });
    }

    private static void expectValidation(Runnable action) {
        try {
            action.run();
            fail("expected ValidationException");
        } catch (ValidationException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
