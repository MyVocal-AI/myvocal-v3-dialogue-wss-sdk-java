# MyVocal V3 Dialogue WebSocket SDK for Java

Java 8-compatible multi-context V3 dialogue client. The delivered artifact is
a thin Jar compiled with `--release 8` and therefore uses class major version
52.

## Local Installation

Install the extracted Jar into a local Maven repository:

```bash
mvn install:install-file -Dfile=lib/myvocal-v3-dialogue-wss-sdk-1.0.1.jar -DgroupId=ai.myvocal -DartifactId=myvocal-v3-dialogue-wss-sdk -Dversion=1.0.1 -Dpackaging=jar
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
Priority and model are fixed by the SDK.

Audio callbacks run directly on the reader and must return quickly. Control
and lifecycle callbacks use a 16-slot non-blocking executor; lifecycle
callbacks take priority during an event storm. Callback exceptions are
reported through `onError` as `CallbackException`.

A server error with a registered `context_id` terminates only that context.
Connection-level errors remain sticky and are returned by later close calls.

Use `keepAlive` before a context reaches its 20-second application idle
timeout. WebSocket ping/pong does not replace this message.

Successfully generated text is charged at twice the standard TTS character
usage.

## Verification

```bash
mvn test
```

The live test is skipped unless `MYVOCAL_TTS_API_KEY` and
`MYVOCAL_VOICE_ID` are present in the current process.
