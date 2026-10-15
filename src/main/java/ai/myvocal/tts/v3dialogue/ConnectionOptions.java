package ai.myvocal.tts.v3dialogue;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class ConnectionOptions {
    public static final String DEFAULT_ENDPOINT =
        "wss://api.voicelibrary.co/enterprise/v1/"
            + "text-to-dialogue/websocket/multi";
    private static final Set<String> FORMATS = new HashSet<String>(
        Arrays.asList(
            "mp3_64", "mp3_96", "mp3_128", "mp3_192",
            "pcm_8000", "pcm_16000", "pcm_22050", "pcm_24000",
            "pcm_44100", "ulaw_8000"));
    private static final Set<String> NORMALIZATION = new HashSet<String>(
        Arrays.asList("auto", "on", "off"));

    private final String endpoint;
    private final String modelId;
    private final String format;
    private final String languageCode;
    private final boolean timestamps;
    private final String textNormalization;
    private final Long seed;
    private final boolean enableLogging;

    private ConnectionOptions(Builder builder) {
        if (!FORMATS.contains(builder.format)) {
            throw new ValidationException("unsupported format");
        }
        String language = builder.languageCode;
        if (language != null) {
            if (!language.matches("(?i)[a-z]{2}")) {
                throw new ValidationException(
                    "language_code must be ISO 639-1");
            }
            language = language.toLowerCase(java.util.Locale.ROOT);
        }
        if (!NORMALIZATION.contains(builder.textNormalization)) {
            throw new ValidationException(
                "unsupported text_normalization");
        }
        if (builder.seed != null
            && (builder.seed < 1L || builder.seed > 4294967295L)) {
            throw new ValidationException(
                "seed must be between 1 and 4294967295");
        }
        if (builder.endpoint == null || builder.endpoint.length() == 0) {
            throw new ValidationException("endpoint must not be empty");
        }
        this.endpoint = builder.endpoint;
        this.modelId = builder.modelId;
        this.format = builder.format;
        this.languageCode = language;
        this.timestamps = builder.timestamps;
        this.textNormalization = builder.textNormalization;
        this.seed = builder.seed;
        this.enableLogging = builder.enableLogging;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String buildUrl() {
        StringBuilder value = new StringBuilder(endpoint).append('?');
        append(value, "priority", "dedicated_concurrency");
        append(value, "model_id", modelId);
        append(value, "format", format);
        append(value, "timestamps", Boolean.toString(timestamps));
        append(value, "text_normalization", textNormalization);
        append(value, "enable_logging", Boolean.toString(enableLogging));
        if (languageCode != null) {
            append(value, "language_code", languageCode);
        }
        if (seed != null) {
            append(value, "seed", Long.toString(seed));
        }
        return value.toString();
    }

    private static void append(
        StringBuilder target, String name, String value) {
        if (target.charAt(target.length() - 1) != '?') {
            target.append('&');
        }
        try {
            target.append(URLEncoder.encode(name, "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(value, "UTF-8"));
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static final class Builder {
        private String endpoint = DEFAULT_ENDPOINT;
        private String modelId = "myvocal_v3";
        private String format = "mp3_128";
        private String languageCode;
        private boolean timestamps;
        private String textNormalization = "on";
        private Long seed;
        private boolean enableLogging = true;

        public Builder modelId(String value) {
            modelId = value;
            return this;
        }

        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        public Builder format(String value) {
            format = value;
            return this;
        }

        public Builder languageCode(String value) {
            languageCode = value;
            return this;
        }

        public Builder timestamps(boolean value) {
            timestamps = value;
            return this;
        }

        public Builder textNormalization(String value) {
            textNormalization = value;
            return this;
        }

        public Builder seed(long value) {
            seed = value;
            return this;
        }

        public Builder enableLogging(boolean value) {
            enableLogging = value;
            return this;
        }

        public ConnectionOptions build() {
            return new ConnectionOptions(this);
        }
    }
}
