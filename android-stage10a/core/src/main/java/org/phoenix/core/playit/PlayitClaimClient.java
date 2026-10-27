package org.phoenix.core.playit;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependency-free client for Playit's public agent claim flow.
 *
 * It implements only the documented claim/setup and claim/exchange API. It does
 * not log in to an account, invent an agent identity, or manufacture a tunnel
 * address. The returned agent key is intentionally exposed only through a
 * redacted, closeable credential wrapper.
 */
public final class PlayitClaimClient {
    public static final String API_BASE_URL = "https://api.playit.gg";
    public static final String CLAIM_PAGE_URL = "https://playit.gg/claim/";
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    /** Playit's /claim/setup answers VersionTextTooLong from 31 characters (measured 2026-10-11). */
    public static final int MAX_VERSION_TEXT_CHARS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final PlayitHttpTransport transport;
    private final String versionText;

    public enum AgentType {
        ASSIGNABLE("assignable"),
        SELF_MANAGED("self-managed");

        private final String wireValue;

        AgentType(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }

    public enum ClaimState {
        PENDING_USER_VISIT,
        PENDING_APPROVAL,
        APPROVED,
        DENIED,
        EXPIRED
    }

    /** A local claim code and its real Playit browser URL. */
    public static final class Claim {
        private final String code;
        private final AgentType agentType;

        private Claim(String code, AgentType agentType) {
            this.code = code;
            this.agentType = agentType;
        }

        public String claimUrl() {
            return CLAIM_PAGE_URL + code;
        }

        /** Returns the code for secure persistence so a pending claim can resume after process death. */
        public String claimCode() {
            return code;
        }

        public AgentType agentType() {
            return agentType;
        }

        @Override
        public String toString() {
            return "PlayitClaim{code=<redacted>, claimUrl=<redacted>, agentType=" + agentType + "}";
        }
    }

    public static final class ClaimResult {
        private final ClaimState state;
        private final PlayitCredential credential;

        private ClaimResult(ClaimState state, PlayitCredential credential) {
            this.state = state;
            this.credential = credential;
        }

        public ClaimState state() {
            return state;
        }

        /** Present only after Playit confirms approval and exchanges the claim. */
        public PlayitCredential credential() {
            return credential;
        }

        @Override
        public String toString() {
            return "PlayitClaimResult{state=" + state + ", credential="
                    + (credential == null ? "<none>" : "<redacted>") + "}";
        }
    }

    public PlayitClaimClient(PlayitHttpTransport transport, String versionText) {
        if (transport == null) {
            throw new IllegalArgumentException("HTTP transport is required");
        }
        if (versionText == null || versionText.trim().isEmpty()
                || versionText.length() > MAX_VERSION_TEXT_CHARS) {
            throw new IllegalArgumentException("A short Playit agent version string is required");
        }
        this.transport = transport;
        this.versionText = versionText;
    }

    /** Creates a claim code using the same five-byte, lowercase-hex format as upstream. */
    public Claim createClaim(AgentType agentType) {
        if (agentType == null) {
            throw new IllegalArgumentException("Agent type is required");
        }
        byte[] bytes = new byte[5];
        RANDOM.nextBytes(bytes);
        char[] code = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            code[i * 2] = HEX[value >>> 4];
            code[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new Claim(new String(code), agentType);
    }

    /** Recreates a locally persisted claim session after validating its code. */
    public Claim restoreClaim(String code, AgentType agentType) throws PlayitApiException {
        if (agentType == null || code == null || !code.matches("(?i)[0-9a-f]{10}")) {
            throw new PlayitApiException(PlayitApiException.Code.INVALID_CLAIM_CODE, 0);
        }
        return new Claim(code.toLowerCase(java.util.Locale.ROOT), agentType);
    }

    /**
     * Polls the documented claim setup endpoint. Approval is followed by the
     * exchange endpoint in the same call; callers can schedule this method on
     * their own background executor and use the returned state to update UI.
     */
    public ClaimResult poll(Claim claim) throws PlayitApiException {
        if (claim == null) {
            throw new IllegalArgumentException("Claim is required");
        }

        Map<String, Object> setup;
        try {
            setup = post("/claim/setup", requestJson(
                    claim.code, claim.agentType.wireValue(), versionText));
        } catch (ClaimStateException state) {
            return state.result;
        }
        Object setupData = setup.get("data");
        String setupState = stringValue(setupData);
        if (setupState == null) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, 200);
        }
        switch (setupState) {
            case "WaitingForUserVisit":
                return result(ClaimState.PENDING_USER_VISIT);
            case "WaitingForUser":
                return result(ClaimState.PENDING_APPROVAL);
            case "UserRejected":
                return result(ClaimState.DENIED);
            case "UserAccepted":
                return exchange(claim);
            default:
                throw new PlayitApiException(PlayitApiException.Code.UNEXPECTED_RESPONSE, 200);
        }
    }

    private ClaimResult exchange(Claim claim) throws PlayitApiException {
        Map<String, Object> envelope;
        try {
            envelope = post("/claim/exchange", requestJson(claim.code));
        } catch (ClaimStateException state) {
            return state.result;
        }
        Object data = envelope.get("data");
        if (!(data instanceof Map)) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, 200);
        }
        Object secretValue = ((Map<?, ?>) data).get("secret_key");
        if (!(secretValue instanceof String) || ((String) secretValue).trim().isEmpty()) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, 200);
        }
        return new ClaimResult(ClaimState.APPROVED, new PlayitCredential((String) secretValue));
    }

    private Map<String, Object> post(String path, String body) throws PlayitApiException {
        URI uri = URI.create(API_BASE_URL + path);
        PlayitHttpTransport.Response response;
        try {
            try (PlayitHttpTransport.Request request = new PlayitHttpTransport.Request(uri, body)) {
                response = transport.execute(request);
            }
        } catch (IOException | RuntimeException error) {
            throw new PlayitApiException(PlayitApiException.Code.TRANSPORT_FAILURE, 0);
        }
        if (response == null) {
            throw new PlayitApiException(PlayitApiException.Code.TRANSPORT_FAILURE, 0);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new PlayitApiException(PlayitApiException.Code.RATE_LIMITED, status);
        }
        String responseBody = response.body();
        if (responseBody.length() > MAX_RESPONSE_CHARS) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
        }

        Map<String, Object> envelope;
        try {
            envelope = stringMap(PlayitJson.parse(responseBody));
            if (envelope == null) {
                throw new IllegalArgumentException("Invalid response");
            }
        } catch (RuntimeException error) {
            PlayitApiException.Code code = status >= 400
                    ? PlayitApiException.Code.HTTP_ERROR
                    : PlayitApiException.Code.MALFORMED_RESPONSE;
            throw new PlayitApiException(code, status);
        }

        Object statusValue = envelope.get("status");
        Object data = envelope.get("data");
        if (!(statusValue instanceof String) || !envelope.containsKey("data")) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
        }
        String apiStatus = (String) statusValue;
        if ("success".equals(apiStatus)) {
            if (status < 200 || status >= 300) {
                throw new PlayitApiException(PlayitApiException.Code.HTTP_ERROR, status);
            }
            return envelope;
        }
        if ("error".equals(apiStatus)) {
            throw new PlayitApiException(PlayitApiException.Code.HTTP_ERROR, status);
        }
        if ("fail".equals(apiStatus)) {
            ClaimResult mapped = mapClaimFailure(data, status);
            if (mapped != null) {
                throw new ClaimStateException(mapped);
            }
            throw new PlayitApiException(PlayitApiException.Code.UNEXPECTED_RESPONSE, status);
        }
        throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
    }

    private static ClaimResult mapClaimFailure(Object data, int status) throws PlayitApiException {
        String failure = stringValue(data);
        if (failure == null) {
            return null;
        }
        switch (failure) {
            case "CodeExpired":
                return result(ClaimState.EXPIRED);
            case "UserRejected":
                return result(ClaimState.DENIED);
            case "NotAccepted":
                return result(ClaimState.PENDING_APPROVAL);
            case "NotSetup":
                return result(ClaimState.PENDING_USER_VISIT);
            case "InvalidCode":
            case "CodeNotFound":
                throw new PlayitApiException(PlayitApiException.Code.INVALID_CLAIM_CODE, status);
            case "VersionTextTooLong":
                throw new PlayitApiException(PlayitApiException.Code.VERSION_TEXT_TOO_LONG, status);
            default:
                return null;
        }
    }

    private static String requestJson(String code, String agentType, String versionText) {
        return "{\"code\":" + quote(code)
                + ",\"agent_type\":" + quote(agentType)
                + ",\"version\":" + quote(versionText) + "}";
    }

    private static String requestJson(String code) {
        return "{\"code\":" + quote(code) + "}";
    }

    private static String quote(String value) {
        return PlayitJson.quote(value);
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (!(key instanceof String)) {
                return null;
            }
        }
        return (Map<String, Object>) value;
    }

    private static ClaimResult result(ClaimState state) {
        return new ClaimResult(state, null);
    }

    /** Internal signal used to return an API fail envelope as a normal claim state. */
    private static final class ClaimStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient ClaimResult result;

        private ClaimStateException(ClaimResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }
}
