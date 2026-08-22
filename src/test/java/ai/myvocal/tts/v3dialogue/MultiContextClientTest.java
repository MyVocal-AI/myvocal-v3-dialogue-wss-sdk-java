package ai.myvocal.tts.v3dialogue;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MultiContextClientTest {
    private MockWebServer server;
    private OkHttpClient http;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient.Builder().build();
    }

    @After
    public void tearDown() throws Exception {
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
        server.shutdown();
    }

    @Test
    public void sendsOperationsAndWaitsForFinalAndClose1000() throws Exception {
        final List<JSONObject> received = new ArrayList<JSONObject>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    received.add(message);
                    if (message.optBoolean("close_socket")) {
                        ws.send(new JSONObject()
                            .put("context_id", "a")
                            .put("is_final", true).toString());
                        ws.send(new JSONObject()
                            .put("context_id", "b")
                            .put("is_final", true).toString());
                        ws.close(1000, "done");
                    }
                }
            }));

        RecordingListener listener = new RecordingListener();
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "a", Collections.singletonList("voice-a")));
        client.openContext(new ContextOptions(
            "b", Collections.singletonList("voice-b")));
        client.sendInputs("a", Collections.singletonList(
            new Input("Hello", "voice-a", true)));
        client.flush("a");
        client.keepAlive("a");
        CloseResult result = client.closeSocket(3000);

        assertEquals(1000, result.getCloseCode());
        assertTrue(client.contextDone("a"));
        assertTrue(client.contextDone("b"));
        assertEquals(0, listener.errors.size());
        assertEquals("api-key", server.takeRequest(1, TimeUnit.SECONDS)
            .getHeaders().names().stream()
            .filter(name -> name.equalsIgnoreCase("api-key"))
            .findFirst().orElse(""));
        assertTrue(received.get(0).has("voices"));
        assertTrue(received.get(2).has("inputs"));
        assertTrue(received.get(3).optBoolean("flush"));
        assertTrue(received.get(4).optBoolean("keep_alive"));
    }

    @Test
    public void decodesAudioInOrderAndTurnFinalIsNotContextFinal()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    if (message.has("voices")) {
                        ws.send(ByteString.encodeUtf8(new JSONObject()
                            .put("context_id", "ctx")
                            .put("audio", ByteString.encodeUtf8("one").base64())
                            .toString()));
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("audio", ByteString.encodeUtf8("two").base64())
                            .put("is_final_audio_for_turn", true).toString());
                    } else if (message.optBoolean("close_context")) {
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("is_final", true).toString());
                    } else if (message.optBoolean("close_socket")) {
                        ws.close(1000, "done");
                    }
                }
            }));
        RecordingListener listener = new RecordingListener();
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        Thread.sleep(100);
        assertArrayEquals("onetwo".getBytes("UTF-8"), listener.joinedAudio());
        assertFalse(client.contextDone("ctx"));
        client.closeContext("ctx", 2000);
        assertTrue(client.contextDone("ctx"));
        client.closeSocket(2000);
    }

    @Test
    public void protocolErrorStaysStickyAcrossClose1000() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    if (message.has("voices")) {
                        ws.send(new JSONObject()
                            .put("code", "INVALID_PARAMETER")
                            .put("field", "inputs.voice_id")
                            .put("message", "invalid voice").toString());
                        ws.close(1000, "done");
                    }
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        try {
            client.waitClosed(2000);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("INVALID_PARAMETER", expected.getCode());
            assertEquals("inputs.voice_id", expected.getField());
        }
    }

    @Test
    public void callbackFailureIsIsolatedAndLateSendRejected()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    if (message.optBoolean("close_context")) {
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("is_final", true).toString());
                    } else if (message.optBoolean("close_socket")) {
                        ws.close(1000, "done");
                    }
                }
            }));
        RecordingListener listener = new RecordingListener();
        listener.throwOnEvent = true;
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        client.closeContext("ctx", 2000);
        waitUntil(new Condition() {
            @Override public boolean value() {
                return !listener.errors.isEmpty();
            }
        });
        assertFalse(listener.errors.isEmpty());
        try {
            client.sendInputs("ctx", Arrays.asList(
                new Input("late", "voice-a")));
            fail("expected StateException");
        } catch (StateException expected) {
            assertNotNull(expected.getMessage());
        }
        client.closeSocket(2000);
    }

    @Test
    public void duplicateCloseOperationsAreIdempotent() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    if (message.optBoolean("close_context")) {
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("is_final", true).toString());
                    } else if (message.optBoolean("close_socket")) {
                        ws.close(1000, "done");
                    }
                }
            }));
        RecordingListener listener = new RecordingListener();
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        ContextResult first = client.closeContext("ctx", 2000);
        ContextResult second = client.closeContext("ctx", 2000);
        assertEquals(first.getContextId(), second.getContextId());
        CloseResult firstClose = client.closeSocket(2000);
        CloseResult secondClose = client.closeSocket(2000);
        assertEquals(1000, firstClose.getCloseCode());
        assertEquals(1000, secondClose.getCloseCode());
        assertEquals(1, listener.contextFinals);
        assertEquals(1, listener.closes);
    }

    @Test
    public void policyCloseWithoutJsonIsProtocolError() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    if (new JSONObject(text).has("voices")) {
                        ws.close(1008, "policy");
                    }
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        try {
            client.waitClosed(2000);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("POLICY_CLOSE", expected.getCode());
            assertEquals(Integer.valueOf(1008), expected.getCloseCode());
        }
    }

    @Test
    public void socketCloseTimeoutUsesTimeoutCategoryAndCancels()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    // Intentionally ignore close_socket to exercise timeout.
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        try {
            client.closeSocket(50);
            fail("expected SDKTimeoutException");
        } catch (SDKTimeoutException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void activeContextLimitAndContextCloseTimeout()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    // Keep contexts active and do not send a final event.
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        for (int index = 0; index < 5; index++) {
            client.openContext(new ContextOptions(
                "ctx-" + index, Collections.singletonList("voice-a")));
        }
        try {
            client.openContext(new ContextOptions(
                "ctx-5", Collections.singletonList("voice-a")));
            fail("expected StateException");
        } catch (StateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            client.closeContext("ctx-0", 20);
            fail("expected SDKTimeoutException");
        } catch (SDKTimeoutException expected) {
            assertNotNull(expected.getMessage());
        }
        client.abort();
    }

    @Test
    public void close1000BeforeContextFinalIsProtocolError()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    if (new JSONObject(text).has("voices")) {
                        ws.close(1000, "early");
                    }
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        try {
            client.waitClosed(2000);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("MISSING_FINAL", expected.getCode());
        }
    }

    @Test
    public void contextStringErrorIsolatedFromSibling() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject message = new JSONObject(text);
                    if ("bad".equals(message.optString("context_id"))
                        && message.has("voices")) {
                        ws.send(new JSONObject()
                            .put("error", "TASK_FAILED")
                            .put("message", "bad context")
                            .put("context_id", "bad").toString());
                    }
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        client.openContext(new ContextOptions(
            "bad", Collections.singletonList("voice-a")));
        client.openContext(new ContextOptions(
            "good", Collections.singletonList("voice-b")));
        Thread.sleep(50);
        client.sendInputs("good", Collections.singletonList(
            new Input("continue", "voice-b", false)));
        try {
            client.closeContext("bad", 2000);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("TASK_FAILED", expected.getCode());
            assertEquals("bad", expected.getContextId());
        }
        client.abort();
    }

    @Test
    public void slowControlCallbackDoesNotBlockReader() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    if (new JSONObject(text).has("voices")) {
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("status", "progress").toString());
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("is_final", true).toString());
                    }
                }
            }));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener() {
            @Override public void onEvent(ServerEvent event) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(client.contextDone("ctx"));
        release.countDown();
        client.abort();
    }

    @Test
    public void controlEventStormIsBounded() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String text) {
                    if (new JSONObject(text).has("voices")) {
                        for (int index = 0; index < 200; index++) {
                            ws.send(new JSONObject()
                                .put("context_id", "ctx")
                                .put("sequence", index).toString());
                        }
                        ws.send(new JSONObject()
                            .put("context_id", "ctx")
                            .put("is_final", true).toString());
                    }
                }
            }));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener() {
            @Override public void onEvent(ServerEvent event) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        MultiContextClient client = client(listener);
        client.connect(2000);
        client.openContext(new ContextOptions(
            "ctx", Collections.singletonList("voice-a")));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        waitUntil(new Condition() {
            @Override public boolean value() {
                return client.contextDone("ctx");
            }
        });
        assertTrue(client.contextDone("ctx"));
        release.countDown();
        client.abort();
    }

    @Test
    public void closeSocketAfterTerminalClosePreservesStickyError()
        throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(
            new WebSocketListener() {
                @Override public void onOpen(
                    WebSocket ws, Response response) {
                    ws.send(new JSONObject()
                        .put("error", "SESSION_FAILED")
                        .put("message", "failed").toString());
                    ws.close(1000, "done");
                }
            }));
        MultiContextClient client = client(new RecordingListener());
        client.connect(2000);
        try {
            client.waitClosed(2000);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("SESSION_FAILED", expected.getCode());
        }
        try {
            client.closeSocket(2000);
            fail("expected the original ProtocolException");
        } catch (ProtocolException expected) {
            assertEquals("SESSION_FAILED", expected.getCode());
        }
    }

    @Test
    public void ownedClientIsReleasedAfterAuthenticationFailure()
        throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        String endpoint = server.url("/enterprise/v1/text-to-dialogue/"
            + "websocket/multi").toString().replace("http://", "ws://");
        MultiContextClient client = new MultiContextClient(
            "test-key",
            ConnectionOptions.builder().endpoint(endpoint).build(),
            new RecordingListener());
        try {
            client.connect(2000);
            fail("expected AuthenticationException");
        } catch (AuthenticationException expected) {
            assertNotNull(expected.getMessage());
        }
        Field field = MultiContextClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        OkHttpClient owned = (OkHttpClient) field.get(client);
        assertTrue(owned.dispatcher().executorService().isShutdown());
    }

    private MultiContextClient client(RecordingListener listener) {
        String endpoint = server.url("/enterprise/v1/text-to-dialogue/"
            + "websocket/multi").toString().replace("http://", "ws://");
        ConnectionOptions options = ConnectionOptions.builder()
            .endpoint(endpoint).build();
        return new MultiContextClient("test-key", options, listener, http);
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.value() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private interface Condition {
        boolean value();
    }

    private static class RecordingListener implements DialogueListener {
        final List<byte[]> audio = new ArrayList<byte[]>();
        final List<SDKException> errors =
            Collections.synchronizedList(new ArrayList<SDKException>());
        boolean throwOnEvent;
        int contextFinals;
        int closes;

        @Override public void onAudio(
            String contextId, byte[] value, JSONObject alignment) {
            audio.add(value);
        }

        @Override public void onEvent(ServerEvent event) {
            if (throwOnEvent) {
                throw new RuntimeException("boom");
            }
        }

        @Override public void onError(SDKException error) {
            errors.add(error);
        }

        @Override public void onContextFinal(ContextResult result) {
            contextFinals++;
        }

        @Override public void onClose(int code, String reason) {
            closes++;
        }

        byte[] joinedAudio() {
            int size = 0;
            for (byte[] item : audio) size += item.length;
            byte[] result = new byte[size];
            int offset = 0;
            for (byte[] item : audio) {
                System.arraycopy(item, 0, result, offset, item.length);
                offset += item.length;
            }
            return result;
        }
    }
}
