package org.phoenix.core.network.netbird;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Small HTTP seam so the NetBird API client can run on Android and in deterministic tests. */
public interface NetBirdHttpTransport {
    Response execute(Request request) throws IOException;

    final class Request {
        private final String method;
        private final URI uri;
        private final Map<String, String> headers;
        private final String body;

        public Request(String method, URI uri, Map<String, String> headers, String body) {
            this.method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
            this.uri = Objects.requireNonNull(uri, "uri");
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
            this.body = body == null ? "" : body;
        }

        public String method() { return method; }
        public URI uri() { return uri; }
        public Map<String, String> headers() { return headers; }
        public String body() { return body; }

        @Override
        public String toString() {
            return "NetBirdHttpRequest{" + method + " " + uri + ", headers=<redacted>, bodyLength=" + body.length() + "}";
        }
    }

    final class Response {
        private final int statusCode;
        private final String body;
        private final Map<String, String> headers;

        public Response(int statusCode, String body) {
            this(statusCode, body, Map.of());
        }

        public Response(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
        }

        public int statusCode() { return statusCode; }
        public String body() { return body; }

        public Optional<String> header(String name) {
            if (name == null) return Optional.empty();
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }
    }
}
