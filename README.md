# MyVocal Dialogue WebSocket SDK for Java

Java 8-compatible multi-context dialogue client. The delivered artifact is
a thin Jar compiled with `--release 8` and therefore uses class major version
52.

## Model Selection (v1.1.0)

Supports `myvocal_v3` (default), `myvocal_v4`, and `myvocal_v3_turbo`.
Package names and import paths retain `v3` for compatibility.

```java
ConnectionOptions options = ConnectionOptions.builder()
    .modelId("myvocal_v4") // Or "myvocal_v3_turbo"
    .build();
```

These models use the **dialogue Multi-Context** endpoint. The two new models
do not support ordinary TTS Single or ordinary TTS Multi-Context WebSocket.
Model IDs are sent unchanged; the SDK does not maintain a model whitelist.
Available SDK options are not a promise that every model supports every
advanced option or format. Start with `mp3_128` and default options.

## Local Installation

Install the extracted Jar into a local Maven repository:

```bash
mvn install:install-file -Dfile=lib/myvocal-v3-dialogue-wss-sdk-1.1.0.jar -DgroupId=ai.myvocal -DartifactId=myvocal-v3-dialogue-wss-sdk -Dversion=1.1.0 -Dpackaging=jar
```

Runtime dependencies:

```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>okhttp</artifactId>
  <version>3.14.9</version>
</dependency>
<dependency>
  <groupId>org.json</groupId>
  <artifactId>json</artifactId>
  <version>20231013</version>
</dependency>
```

The artifact is not yet published to Maven Central.

## Lifecycle

```text
connect -> openContext -> sendInputs/flush/keepAlive
        -> closeContext or closeSocket -> Close 1000
```

`closeContext` waits for that context's final event. `closeSocket` waits for
every active context and Close Code `1000`. Duplicate close calls are
idempotent. No active session is automatically reconnected or replayed.

Connection options expose the 10 public formats, optional language code,
timestamps, text normalization, seed `1..4294967295`, and logging option.
Priority remains fixed at `dedicated_concurrency`; model selection is configurable.

Audio callbacks run directly on the reader and must return quickly. Control
and lifecycle callbacks use a 16-slot non-blocking executor; lifecycle
callbacks take priority during an event storm. Callback exceptions are
reported through `onError` as `CallbackException`.

A server error with a registered `context_id` terminates only that context.
Connection-level errors remain sticky and are returned by later close calls.

Use `keepAlive` before a context reaches its 20-second application idle
timeout. WebSocket ping/pong does not replace this message.

Usage is reported by `GET https://api.voicelibrary.co/enterprise/v1/user/usage`.
`myvocal_v3`, `myvocal_v4`, and `myvocal_v3_turbo` record characters under
`tts.v3_chars`, `tts.v4_chars`, and `tts.v3_turbo_chars`, respectively,
and contribute to `tts.total_chars`. Use the returned counters to reconcile
usage; no SDK-side multiplier is applied. New-model counters describe new
requests, not a historical backfill.

## Verification

```bash
mvn test
```

The live test is skipped unless `MYVOCAL_TTS_API_KEY` and
`MYVOCAL_VOICE_ID` are present in the current process.

The packaged basic example also accepts optional `MYVOCAL_MODEL_ID`.
