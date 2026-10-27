package org.phoenix.core.playit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/** Dependency-free HTTPS transport backed by HttpURLConnection. */
public final class UrlConnectionPlayitTransport implements PlayitHttpTransport {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    @Override
    public Response execute(Request request) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI uri = request.uri();
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("HTTPS is required for Playit API requests");
            }

            URLConnection opened = uri.toURL().openConnection();
            if (!(opened instanceof HttpURLConnection)) {
                throw new IOException("Unsupported Playit API transport");
            }
            connection = (HttpURLConnection) opened;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (java.util.Map.Entry<String, String> header : request.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            byte[] requestBytes = request.jsonBody().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String body = responseStream == null ? "" : readBounded(responseStream);
            return new Response(status, body);
        } catch (IOException error) {
            // Do not retain URLConnection exception text or causes in a user-facing error.
            if ("HTTPS is required for Playit API requests".equals(error.getMessage())
                    || "Unsupported Playit API transport".equals(error.getMessage())) {
                throw error;
            }
            throw new IOException("Playit HTTP transport failed");
        } catch (RuntimeException error) {
            throw new IOException("Playit HTTP transport failed");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Playit response exceeded the size limit");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
