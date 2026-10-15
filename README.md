# MyVocal Dialogue WebSocket SDK for Java

Java client for multi-role dialogue synthesis. Requires Java 8 or later and an enterprise TTS API Key with dedicated concurrency.

## Install

Download and extract [SDK v1.1.2](https://github.com/MyVocal-AI/myvocal-v3-dialogue-wss-sdk-java/releases/tag/v1.1.2). Install the supplied JAR and POM into your local Maven repository:

```bash
mvn install:install-file -Dfile=lib/myvocal-v3-dialogue-wss-sdk-1.1.2.jar -DpomFile=pom.xml
```

Add this dependency to your application:

```xml
<dependency>
  <groupId>ai.myvocal</groupId>
  <artifactId>myvocal-v3-dialogue-wss-sdk</artifactId>
  <version>1.1.2</version>
</dependency>
```

The SDK is not available on Maven Central. Maven resolves the required libraries from the supplied POM. See [runtime dependencies](DEPENDENCIES.md) when configuring a classpath manually.

## Choose a model

Supported models: `myvocal_v3` (default), `myvocal_v4`, and `myvocal_v3_turbo`.
Use these SDKs with the dialogue Multi-Context WebSocket endpoint. `myvocal_v4` and `myvocal_v3_turbo` do not support ordinary TTS WebSocket endpoints.

```java
import ai.myvocal.tts.v3dialogue.ConnectionOptions;

ConnectionOptions options = ConnectionOptions.builder()
    .modelId("myvocal_v4") // Or "myvocal_v3_turbo"
    .build();
```

## Output languages

`myvocal_v4` and `myvocal_v3_turbo` support the same 94 output languages. Use `zh` for Mandarin Chinese and `yue` for Cantonese. See the [language list](https://api-docs.myvocal.ai/64249845f0#输出语言) for all supported codes.

```java
ConnectionOptions options = ConnectionOptions.builder()
    .modelId("myvocal_v4").languageCode("yue").build();
```

Omit the language option to select a language from the input text. Provide text in the desired language; the option does not translate it.

## Run the example

Set the following environment variables before running the example:

```bash
export MYVOCAL_TTS_API_KEY='YOUR_API_KEY'
export MYVOCAL_VOICE_ID='YOUR_VOICE_ID'
export MYVOCAL_MODEL_ID='myvocal_v4'
```

PowerShell:

```powershell
$env:MYVOCAL_TTS_API_KEY='YOUR_API_KEY'
$env:MYVOCAL_VOICE_ID='YOUR_VOICE_ID'
$env:MYVOCAL_MODEL_ID='myvocal_v4'
```

`MYVOCAL_MODEL_ID` is optional; the default is `myvocal_v3`. Running the example makes a real synthesis request and consumes character usage. It prints audio byte counts and the connection close code.

Copy runtime dependencies from the extracted folder:

```bash
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=lib/dependencies
```

On macOS/Linux:

```bash
javac -cp "lib/*:lib/dependencies/*" examples/BasicExample.java
java -cp "lib/*:lib/dependencies/*:examples" BasicExample
```

On Windows:

```powershell
javac -cp "lib/*;lib/dependencies/*" examples/BasicExample.java
java -cp "lib/*;lib/dependencies/*;examples" BasicExample
```

The [complete example](examples/BasicExample.java) shows how to create the client, receive audio, create two contexts, and finish the connection.

## Use the SDK in your application

- [Usage guide](USAGE.md): connection options, contexts, audio callbacks, completion, errors, and timeouts.
- [API reference](https://api-docs.myvocal.ai/3892090w0): messages, model support, and limits.
- [Character usage](https://api-docs.myvocal.ai/336818975e0).

Keep callbacks short and handle audio separately for each context. Wait for context final events and Close Code `1000` when finishing the connection. The SDK does not automatically reconnect or replay text.

Licensed under [Apache License 2.0](LICENSE). Dependency notices are in [NOTICE](NOTICE).
