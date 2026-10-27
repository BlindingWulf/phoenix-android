package org.phoenix.core.playit;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small HTTP seam for the Playit API. Android can provide a platform transport,
 * while tests can supply deterministic responses without opening a socket.
 */
@FunctionalInterface
public interface PlayitHttpTransport {
    Response execute(Request request) throws IOException;

    final class Request implements AutoCloseable {
        private final URI uri;
        private final String jsonBody;
        private final Map<String, String> headers;

        Request(URI uri, Map<String, String> headers, String jsonBody) {
            this.uri = uri;
            this.headers = new LinkedHashMap<>(headers);
            this.jsonBody = jsonBody;
        }

        Request(URI uri, String jsonBody) {
            this(uri, Map.of(), jsonBody);
        }

        public URI uri() {
            return uri;
        }

        public String method() {
            return "POST";
        }

        /** Headers for the platform transport; Authorization values are sensitive. */
        public Map<String, String> headers() {
            return Collections.unmodifiableMap(headers);
        }

        public String jsonBody() {
            return jsonBody;
        }

        @Override
        public void close() {
            headers.clear();
        }

        @Override
        public String toString() {
            return "PlayitHttpRequest{uri=" + uri + ", headers=<redacted>, body=<redacted>}";
        }
    }

    final class Response {
        private final int statusCode;
        private final String body;

        public Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }

        @Override
        public String toString() {
            return "PlayitHttpResponse{statusCode=" + statusCode + ", body=<redacted>}";
        }
    }
}
