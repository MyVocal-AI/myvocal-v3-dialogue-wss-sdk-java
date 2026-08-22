package ai.myvocal.tts.v3dialogue;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MultiContextClient {
    private final Object lock = new Object();
    private final String apiKey;
    private final ConnectionOptions options;
    private final DialogueListener listener;
    private final OkHttpClient httpClient;
    private final boolean ownsHttpClient;
    private final boolean accumulateAudio;
    private final Map<String, ContextState> contexts =
        new LinkedHashMap<String, ContextState>();
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final ThreadPoolExecutor callbackExecutor;

    private volatile WebSocket socket;
    private volatile SDKException stickyError;
    private volatile int closeCode = -1;
    private volatile boolean connected;
    private volatile boolean closingSocket;
    private volatile boolean aborting;
    private volatile CloseResult socketResult;

    public MultiContextClient(
        String apiKey,
        ConnectionOptions options,
        DialogueListener listener
    ) {
        this(apiKey, options, listener,
            new OkHttpClient.Builder().build(), true, false);
    }

    public MultiContextClient(
        String apiKey,
        ConnectionOptions options,
        DialogueListener listener,
        OkHttpClient httpClient
    ) {
        this(apiKey, options, listener, httpClient, false, false);
    }

    public MultiContextClient(
        String apiKey,
        ConnectionOptions options,
        DialogueListener listener,
        OkHttpClient httpClient,
        boolean accumulateAudio
    ) {
        this(apiKey, options, listener, httpClient, false, accumulateAudio);
    }

    private MultiContextClient(
        String apiKey,
        ConnectionOptions options,
        DialogueListener listener,
        OkHttpClient httpClient,
        boolean ownsHttpClient,
        boolean accumulateAudio
    ) {
        if (apiKey == null || apiKey.length() == 0) {
            throw new ValidationException("api_key must not be empty");
        }
        if (options == null || listener == null || httpClient == null) {
            throw new ValidationException(
                "options, listener and httpClient are required");
        }
        this.apiKey = apiKey;
        this.options = options;
        this.listener = listener;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
        this.accumulateAudio = accumulateAudio;
        this.callbackExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(
                        runnable, "myvocal-v3-dialogue-callback");
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.DiscardPolicy());
    }

    public void connect(long timeoutMillis) {
        synchronized (lock) {
            if (socket != null) {
                throw new StateException("client is already connected");
            }
            Request request = new Request.Builder()
                .url(options.buildUrl())
                .header("api-key", apiKey)
                .build();
            socket = httpClient.newWebSocket(request, new SocketListener());
        }
        try {
            await(connectedLatch, timeoutMillis, "WebSocket connection");
        } catch (SDKTimeoutException error) {
            abort();
            throw error;
        }
        try {
            raiseSticky();
        } catch (SDKException error) {
            abort();
            throw error;
        }
        if (!connected) {
            abort();
            throw new TransportException("WebSocket did not open");
        }
    }

    public void openContext(ContextOptions context) {
        requireConnected();
        if (context == null) {
            throw new ValidationException("context is required");
        }
        synchronized (lock) {
            if (closingSocket) {
                throw new StateException("socket is closing");
            }
            if (contexts.containsKey(context.getContextId())) {
                throw new StateException(
                    "context_id is already registered");
            }
            int active = 0;
            for (ContextState state : contexts.values()) {
                if (!state.done) active++;
            }
            if (active >= 5) {
                throw new StateException(
                    "at most 5 active contexts are allowed");
            }
            contexts.put(context.getContextId(), new ContextState(context));
        }
        send(context.toJson());
    }

    public void sendInputs(String contextId, List<Input> inputs) {
        ContextState state = requireOpenContext(contextId);
        if (inputs == null || inputs.isEmpty()) {
            throw new ValidationException("inputs must not be empty");
        }
        List<String> registered = state.options.getVoices();
        JSONArray values = new JSONArray();
        for (Input input : inputs) {
            if (input == null || !registered.contains(input.getVoiceId())) {
                throw new ValidationException(
                    "input voice_id must be registered");
            }
            values.put(input.toJson());
        }
        send(new JSONObject()
            .put("context_id", contextId)
            .put("inputs", values));
    }

    public void flush(String contextId) {
        requireOpenContext(contextId);
        send(new JSONObject()
            .put("context_id", contextId)
            .put("flush", true));
    }

    public void keepAlive(String contextId) {
        requireOpenContext(contextId);
        send(new JSONObject()
            .put("context_id", contextId)
            .put("keep_alive", true));
    }

    public ContextResult closeContext(
        String contextId, long timeoutMillis) {
        requireConnected();
        ContextState state;
        boolean sendClose = false;
        synchronized (lock) {
            state = contexts.get(contextId);
            if (state == null) {
                throw new StateException("unknown context_id");
            }
            if (state.done) {
                if (state.error != null) {
                    throw state.error;
                }
                raiseSticky();
                return state.result;
            }
            if (!state.closing) {
                state.closing = true;
                sendClose = true;
            }
        }
        if (sendClose) {
            send(new JSONObject()
                .put("context_id", contextId)
                .put("close_context", true));
        }
        await(state.finalLatch, timeoutMillis, "context final");
        if (state.error != null) {
            throw state.error;
        }
        raiseSticky();
        return state.result;
    }

    public CloseResult closeSocket(long timeoutMillis) {
        raiseSticky();
        if (socketResult != null) {
            raiseSticky();
            return socketResult;
        }
        if (socket == null) {
            throw new StateException("client has not connected");
        }
        List<ContextState> active = new ArrayList<ContextState>();
        boolean sendClose = false;
        synchronized (lock) {
            if (!closingSocket) {
                requireConnected();
                closingSocket = true;
                sendClose = true;
                for (ContextState state : contexts.values()) {
                    if (!state.done) active.add(state);
                }
            }
        }
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (sendClose) {
                send(new JSONObject().put("close_socket", true));
                for (ContextState state : active) {
                    awaitRemaining(
                        state.finalLatch, deadline, "context final");
                    raiseSticky();
                }
            }
            awaitRemaining(closedLatch, deadline, "socket close");
        } catch (SDKTimeoutException error) {
            abort();
            throw error;
        }
        raiseSticky();
        if (closeCode != 1000) {
            throw new TransportException(
                "WebSocket closed with code " + closeCode);
        }
        socketResult = new CloseResult(closeCode);
        shutdownOwnedClient();
        return socketResult;
    }

    public CloseResult waitClosed(long timeoutMillis) {
        if (socket == null) {
            throw new StateException("client has not connected");
        }
        await(closedLatch, timeoutMillis, "socket close");
        raiseSticky();
        if (!aborting && closeCode != 1000) {
            throw new TransportException(
                "WebSocket closed with code " + closeCode);
        }
        return new CloseResult(closeCode);
    }

    public void abort() {
        aborting = true;
        WebSocket current = socket;
        if (current != null) {
            current.cancel();
        }
        shutdownOwnedClient();
        callbackExecutor.shutdownNow();
    }

    public boolean contextDone(String contextId) {
        synchronized (lock) {
            ContextState state = contexts.get(contextId);
            return state != null && state.done;
        }
    }

    private void send(JSONObject payload) {
        raiseSticky();
        WebSocket current = socket;
        if (current == null || !current.send(payload.toString())) {
            TransportException error =
                new TransportException("WebSocket send failed");
            setSticky(error);
            throw error;
        }
    }

    private void handleMessage(String text) {
        final JSONObject event;
        try {
            event = new JSONObject(text);
        } catch (JSONException error) {
            reportProtocol(new ProtocolException(
                "INVALID_JSON", "server frame was not JSON", null));
            return;
        }

        ProtocolException protocolError = protocolError(event);
        if (protocolError != null) {
            reportProtocol(protocolError);
            return;
        }

        String contextId = event.optString("context_id", null);
        ContextState state;
        synchronized (lock) {
            state = contextId == null ? null : contexts.get(contextId);
        }

        String encodedAudio = event.optString("audio", "");
        if (encodedAudio.length() > 0) {
            final byte[] audio;
            try {
                audio = Base64.getDecoder().decode(encodedAudio);
            } catch (IllegalArgumentException error) {
                reportProtocol(new ProtocolException(
                    "INVALID_AUDIO", "audio was not valid base64", null));
                return;
            }
            if (state == null) {
                reportProtocol(new ProtocolException(
                    "UNKNOWN_CONTEXT",
                    "audio event referenced an unknown context",
                    "context_id"));
                return;
            }
            if (accumulateAudio) {
                synchronized (lock) {
                    state.audio.write(audio, 0, audio.length);
                }
            }
            invokeAudio(contextId, audio, event.optJSONObject("alignment"));
        }

        boolean finalEvent = event.optBoolean("is_final", false);
        if (finalEvent) {
            if (state == null) {
                reportProtocol(new ProtocolException(
                    "UNKNOWN_CONTEXT",
                    "final event referenced an unknown context",
                    "context_id"));
                return;
            }
            synchronized (lock) {
                if (!state.done) {
                    state.done = true;
                    state.result = new ContextResult(
                        contextId, state.audio.toByteArray());
                }
            }
        }

        invokeEvent(new ServerEvent(event));
        if (finalEvent) {
            invokeContextFinal(state.result);
            state.finalLatch.countDown();
        }
    }

    private ProtocolException protocolError(JSONObject event) {
        String code = event.optString("code", "");
        if (code.length() == 0) {
            code = event.optString("error_code", "");
        }
        JSONObject nested = event.optJSONObject("error");
        if (code.length() == 0 && nested != null) {
            code = nested.optString("code", "");
        }
        if (code.length() == 0
            && event.opt("error") instanceof String) {
            code = event.optString("error", "");
        }
        if (code.length() == 0) {
            return null;
        }
        String message = event.optString("message",
            event.optString("status_msg", code));
        String field = event.optString("field", null);
        if (nested != null) {
            if (message.length() == 0 || message.equals(code)) {
                message = nested.optString("message", message);
            }
            if (field == null) {
                field = nested.optString("field", null);
            }
        }
        return new ProtocolException(
            code,
            message,
            field,
            event.optString("context_id", null),
            null);
    }

    private void reportProtocol(ProtocolException error) {
        ContextState state = null;
        if (error.getContextId() != null) {
            synchronized (lock) {
                state = contexts.get(error.getContextId());
                if (state != null && !state.done) {
                    state.error = error;
                    state.done = true;
                    state.result = new ContextResult(
                        error.getContextId(), state.audio.toByteArray());
                    state.finalLatch.countDown();
                }
            }
        }
        if (state == null) {
            setSticky(error);
        }
        invokeError(error);
    }

    private void setSticky(SDKException error) {
        synchronized (lock) {
            if (stickyError != null) {
                return;
            }
            stickyError = error;
            for (ContextState state : contexts.values()) {
                state.finalLatch.countDown();
            }
        }
    }

    private void invokeAudio(
        String contextId, byte[] audio, JSONObject alignment) {
        try {
            listener.onAudio(contextId, audio, alignment);
        } catch (Throwable error) {
            invokeError(new CallbackException("onAudio", error));
        }
    }

    private void invokeEvent(ServerEvent event) {
        final ServerEvent value = event;
        dispatchCallback(new Runnable() {
            @Override public void run() {
                try {
                    listener.onEvent(value);
                } catch (Throwable error) {
                    invokeErrorDirect(
                        new CallbackException("onEvent", error));
                }
            }
        }, false);
    }

    private void invokeError(SDKException error) {
        final SDKException value = error;
        dispatchCallback(new Runnable() {
            @Override public void run() {
                invokeErrorDirect(value);
            }
        }, true);
    }

    private void invokeErrorDirect(SDKException error) {
        try {
            listener.onError(error);
        } catch (Throwable ignored) {
            // A user error callback must not change the protocol result.
        }
    }

    private void invokeContextFinal(ContextResult result) {
        final ContextResult value = result;
        dispatchCallback(new Runnable() {
            @Override public void run() {
                try {
                    listener.onContextFinal(value);
                } catch (Throwable error) {
                    invokeErrorDirect(
                        new CallbackException("onContextFinal", error));
                }
            }
        }, true);
    }

    private void invokeClose(int code, String reason) {
        final int valueCode = code;
        final String valueReason = reason;
        dispatchCallback(new Runnable() {
            @Override public void run() {
                try {
                    listener.onClose(valueCode, valueReason);
                } catch (Throwable error) {
                    invokeErrorDirect(
                        new CallbackException("onClose", error));
                }
            }
        }, true);
    }

    private void dispatchCallback(Runnable callback, boolean critical) {
        if (!callbackExecutor.isShutdown()) {
            if (critical
                && callbackExecutor.getQueue().remainingCapacity() == 0) {
                callbackExecutor.getQueue().poll();
            }
            callbackExecutor.execute(callback);
        }
    }

    private void requireConnected() {
        raiseSticky();
        if (socket == null || !connected || closedLatch.getCount() == 0) {
            throw new StateException("client is not connected");
        }
    }

    private ContextState requireOpenContext(String contextId) {
        requireConnected();
        synchronized (lock) {
            ContextState state = contexts.get(contextId);
            if (state == null) {
                throw new StateException("unknown context_id");
            }
            if (state.closing || state.done) {
                throw new StateException(
                    "context is closing or closed");
            }
            return state;
        }
    }

    private void raiseSticky() {
        if (stickyError != null) {
            throw stickyError;
        }
    }

    private static void await(
        CountDownLatch latch, long timeoutMillis, String operation) {
        if (timeoutMillis <= 0) {
            throw new ValidationException("timeout must be positive");
        }
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new SDKTimeoutException(
                    "timed out waiting for " + operation);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SDKTimeoutException(
                "interrupted while waiting for " + operation, error);
        }
    }

    private static void awaitRemaining(
        CountDownLatch latch, long deadline, String operation) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SDKTimeoutException(
                "timed out waiting for " + operation);
        }
        try {
            if (!latch.await(remaining, TimeUnit.NANOSECONDS)) {
                throw new SDKTimeoutException(
                    "timed out waiting for " + operation);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SDKTimeoutException(
                "interrupted while waiting for " + operation, error);
        }
    }

    private void shutdownOwnedClient() {
        if (ownsHttpClient) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private final class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true;
            connectedLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleMessage(new String(
                bytes.toByteArray(), StandardCharsets.UTF_8));
        }

        @Override
        public void onClosing(
            WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(
            WebSocket webSocket, int code, String reason) {
            closeCode = code;
            connected = false;
            if (!aborting && code == 1008 && stickyError == null) {
                setSticky(new ProtocolException(
                    "POLICY_CLOSE",
                    "WebSocket policy close",
                    null,
                    null,
                    1008));
            }
            if (!aborting && code != 1000 && code != 1008
                && stickyError == null) {
                setSticky(new TransportException(
                    "WebSocket closed with code " + code));
            }
            if (!aborting && stickyError == null) {
                synchronized (lock) {
                    for (ContextState state : contexts.values()) {
                        if (!state.done) {
                            setSticky(new ProtocolException(
                                "MISSING_FINAL",
                                "socket closed before every context was final",
                                null));
                            break;
                        }
                    }
                }
            }
            invokeClose(code, reason);
            connectedLatch.countDown();
            closedLatch.countDown();
            shutdownOwnedClient();
            callbackExecutor.shutdown();
        }

        @Override
        public void onFailure(
            WebSocket webSocket, Throwable error, Response response) {
            connected = false;
            if (!aborting) {
                if (response != null
                    && (response.code() == 401 || response.code() == 403)) {
                    setSticky(new AuthenticationException(
                        "WebSocket authentication failed"));
                } else {
                    setSticky(new TransportException(
                        "WebSocket transport failed", error));
                }
            }
            connectedLatch.countDown();
            closedLatch.countDown();
            invokeClose(-1, error == null ? "" : error.getMessage());
            shutdownOwnedClient();
            callbackExecutor.shutdown();
        }
    }

    private static final class ContextState {
        final ContextOptions options;
        final CountDownLatch finalLatch = new CountDownLatch(1);
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        boolean closing;
        boolean done;
        ProtocolException error;
        ContextResult result;

        ContextState(ContextOptions options) {
            this.options = options;
        }
    }
}
