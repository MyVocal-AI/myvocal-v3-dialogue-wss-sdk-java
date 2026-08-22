# Runtime Dependencies

The SDK is a thin Jar.

| Dependency | Version | Purpose |
|---|---:|---|
| `com.squareup.okhttp3:okhttp` | `3.14.9` | WebSocket and TLS transport |
| `org.json:json` | `20231013` | JSON serialization and parsing |

Applications must provide these dependencies at runtime. Their transitive
dependencies are resolved by Maven or Gradle.
