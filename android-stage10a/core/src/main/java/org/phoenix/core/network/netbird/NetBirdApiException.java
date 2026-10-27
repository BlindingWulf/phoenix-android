package org.phoenix.core.network.netbird;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Safe API failure categories; provider response bodies and credentials are deliberately omitted. */
public final class NetBirdApiException extends Exception {
    public enum Code {
        TRANSPORT_FAILURE,
        RATE_LIMITED,
        INVALID_REQUEST,
        CREDENTIAL_REJECTED,
        PERMISSION_DENIED,
        NOT_FOUND,
        PROVIDER_UNAVAILABLE,
        MALFORMED_RESPONSE,
        UNEXPECTED_STATUS
    }

    private final Code code;
    private final int httpStatus;
    private final Long retryAfterEpochMillis;

    public NetBirdApiException(Code code, int httpStatus) {
        this(code, httpStatus, null, null);
    }

    public NetBirdApiException(Code code, int httpStatus, Long retryAfterEpochMillis, Throwable cause) {
        super(message(code, httpStatus), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAfterEpochMillis = retryAfterEpochMillis;
    }

    public Code code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Long retryAfterEpochMillis() { return retryAfterEpochMillis; }

    /** A timed-out or 5xx write may have reached NetBird; reconcile by deterministic name before any retry. */
    public boolean outcomeMayHaveOccurred() {
        return code == Code.TRANSPORT_FAILURE || code == Code.PROVIDER_UNAVAILABLE;
    }

    /**
     * Longest provider cooldown Phoenix will persist. Long provider deadlines stay authoritative (no early retry), but an
     * absurd or overflowing header must not lock provisioning for the lifetime of the install.
     */
    public static final long MAX_RETRY_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000;

    public static Long parseRetryAfter(String value, long nowEpochMillis) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        boolean delta = !candidate.isEmpty();
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') { delta = false; break; }
        }
        long horizon = nowEpochMillis + MAX_RETRY_AFTER_MILLIS;
        if (delta) {
            try { return Math.min(horizon, Math.addExact(nowEpochMillis, Math.multiplyExact(Long.parseLong(candidate), 1000L))); }
            catch (ArithmeticException | NumberFormatException overflow) { return horizon; }
        }
        try {
            return Math.min(horizon, ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
        } catch (DateTimeParseException invalid) {
            try { return Math.min(horizon, Instant.parse(candidate).toEpochMilli()); }
            catch (DateTimeParseException ignored) { return null; }
        }
    }

    private static String message(Code code, int status) {
        String description = switch (code) {
            case TRANSPORT_FAILURE -> "NetBird API request failed at the transport layer";
            case RATE_LIMITED -> "NetBird API rate limit reached";
            case INVALID_REQUEST -> "NetBird rejected the API request";
            case CREDENTIAL_REJECTED -> "NetBird API credential was rejected";
            case PERMISSION_DENIED -> "NetBird account does not have permission for this API operation";
            case NOT_FOUND -> "NetBird API resource was not found";
            case PROVIDER_UNAVAILABLE -> "NetBird API is temporarily unavailable";
            case MALFORMED_RESPONSE -> "NetBird API returned an unexpected response";
            case UNEXPECTED_STATUS -> "NetBird API returned an unexpected status";
        };
        return description + (status > 0 ? " (HTTP " + status + ")" : "");
    }
}
