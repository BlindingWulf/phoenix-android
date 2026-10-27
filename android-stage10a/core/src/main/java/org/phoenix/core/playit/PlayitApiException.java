package org.phoenix.core.playit;

import java.util.Optional;

/** A safe, redacted API error. It deliberately never retains remote response text. */
public final class PlayitApiException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_CLAIM_CODE,
        VERSION_TEXT_TOO_LONG,
        EXPIRED,
        DENIED,
        RATE_LIMITED,
        CREDENTIAL_UNAVAILABLE,
        NOT_AUTHORIZED,
        AGENT_UNAVAILABLE,
        PREMIUM_REQUIRED,
        ACCOUNT_VERIFICATION_REQUIRED,
        TUNNEL_REJECTED,
        TUNNEL_NOT_FOUND,
        PUBLIC_HOSTNAME_PREFIX_UNSUPPORTED,
        INVALID_AGENT_CREDENTIAL,
        AGENT_TYPE_NOT_SUPPORTED,
        AGENT_SCOPE_RESTRICTED,
        REQUEST_VALIDATION_FAILED,
        API_ENDPOINT_UNAVAILABLE,
        PROVIDER_INTERNAL_ERROR,
        TUNNEL_CONFIGURATION_INVALID,
        TUNNEL_ALLOCATION_UNAVAILABLE,
        TUNNEL_NAME_INVALID,
        FIREWALL_UNAVAILABLE,
        AGENT_VERSION_UNSUPPORTED,
        TUNNEL_ENDPOINT_UNSUPPORTED,
        REGION_UNAVAILABLE,
        HTTP_ERROR,
        TRANSPORT_FAILURE,
        MALFORMED_RESPONSE,
        UNEXPECTED_RESPONSE
    }

    public enum ValidationHintKind {
        MISSING_FIELD,
        UNKNOWN_FIELD,
        EXPECTED_TYPE
    }

    /** A type category, never the provider's arbitrary expected-value text. */
    public enum ExpectedType {
        STRING,
        OBJECT,
        ARRAY,
        BOOLEAN,
        INTEGER,
        NUMBER,
        UUID,
        ENUM
    }

    /**
     * A sanitized validation clue. Field names are selected from a fixed
     * allowlist, and expected types are closed enum values. No provider text or
     * submitted field value is retained.
     */
    public static final class ValidationHint {
        private static final java.util.Set<String> ALLOWED_FIELDS = java.util.Set.of(
                "ports", "protocol", "origin", "endpoint", "enabled", "alloc", "name",
                "firewall_id", "agent_id", "config", "fields", "local_ip", "local_port");

        private final ValidationHintKind kind;
        private final String fieldName;
        private final ExpectedType expectedType;

        private ValidationHint(ValidationHintKind kind, String fieldName, ExpectedType expectedType) {
            this.kind = kind;
            this.fieldName = fieldName;
            this.expectedType = expectedType;
        }

        static ValidationHint missingField(String fieldName) {
            String safeField = safeField(fieldName);
            return safeField == null ? null : new ValidationHint(ValidationHintKind.MISSING_FIELD, safeField, null);
        }

        static ValidationHint unknownField(String fieldName) {
            String safeField = safeField(fieldName);
            return safeField == null ? null : new ValidationHint(ValidationHintKind.UNKNOWN_FIELD, safeField, null);
        }

        static ValidationHint expectedType(String fieldName, ExpectedType expectedType) {
            if (expectedType == null) {
                return null;
            }
            return new ValidationHint(ValidationHintKind.EXPECTED_TYPE, safeField(fieldName), expectedType);
        }

        private static String safeField(String fieldName) {
            if (fieldName == null) {
                return null;
            }
            String candidate = fieldName.trim();
            int separator = candidate.lastIndexOf('.');
            if (separator >= 0 && separator + 1 < candidate.length()) {
                candidate = candidate.substring(separator + 1);
            }
            for (String allowed : ALLOWED_FIELDS) {
                if (allowed.equalsIgnoreCase(candidate)) {
                    return allowed;
                }
            }
            return null;
        }

        public ValidationHintKind kind() {
            return kind;
        }

        /** Empty when the provider only described a type and no safe field was identified. */
        public Optional<String> fieldName() {
            return Optional.ofNullable(fieldName);
        }

        public Optional<ExpectedType> expectedType() {
            return Optional.ofNullable(expectedType);
        }

        /** Safe fixed-text rendering suitable for app diagnostics. */
        public String message() {
            switch (kind) {
                case MISSING_FIELD:
                    return "Missing required field: " + fieldName + ".";
                case UNKNOWN_FIELD:
                    return "Unrecognized field: " + fieldName + ".";
                case EXPECTED_TYPE:
                default:
                    String type = expectedType.name().toLowerCase(java.util.Locale.ROOT);
                    return fieldName == null
                            ? "Expected " + type + " value for a request field."
                            : "Expected " + type + " value for field: " + fieldName + ".";
            }
        }

        @Override
        public String toString() {
            return message();
        }
    }

    private final Code code;
    private final int httpStatus;
    private final boolean deterministicFailure;
    private final transient ValidationHint validationHint;

    public PlayitApiException(Code code, int httpStatus) {
        this(code, httpStatus, false);
    }

    PlayitApiException(Code code, int httpStatus, boolean deterministicFailure) {
        this(code, httpStatus, deterministicFailure, null);
    }

    PlayitApiException(
            Code code,
            int httpStatus,
            boolean deterministicFailure,
            ValidationHint validationHint) {
        super(safeMessage(code));
        this.code = code;
        this.httpStatus = httpStatus;
        this.deterministicFailure = deterministicFailure;
        this.validationHint = code == Code.REQUEST_VALIDATION_FAILED ? validationHint : null;
    }

    public Code code() {
        return code;
    }

    /** Returns zero when no HTTP response was received. */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * True only when Playit returned an explicit rejection that establishes the
     * request did not complete. Before retrying any create operation, callers
     * must still reconcile a fresh run-data snapshot to check for a created or
     * pending tunnel. Transport failures and internal errors remain ambiguous.
     */
    public boolean isDeterministicFailure() {
        return deterministicFailure;
    }

    /** Returns only a whitelisted field/type clue parsed from a validation envelope. */
    public Optional<ValidationHint> validationHint() {
        return Optional.ofNullable(validationHint);
    }

    private static String safeMessage(Code code) {
        switch (code) {
            case INVALID_CLAIM_CODE:
                return "The Playit claim code is invalid.";
            case VERSION_TEXT_TOO_LONG:
                return "The Playit agent version text exceeds the accepted length.";
            case EXPIRED:
                return "The Playit claim has expired.";
            case DENIED:
                return "The Playit claim was denied.";
            case RATE_LIMITED:
                return "The Playit API rate limit was reached.";
            case CREDENTIAL_UNAVAILABLE:
                return "A usable Playit agent credential is required.";
            case NOT_AUTHORIZED:
                return "Playit did not authorize this agent request.";
            case AGENT_UNAVAILABLE:
                return "The Playit agent is not available for this request.";
            case PREMIUM_REQUIRED:
                return "This Playit tunnel option requires a supported account plan.";
            case ACCOUNT_VERIFICATION_REQUIRED:
                return "Playit requires account verification for this request.";
            case TUNNEL_REJECTED:
                return "Playit rejected the tunnel request.";
            case TUNNEL_NOT_FOUND:
                return "The Playit tunnel could not be found.";
            case PUBLIC_HOSTNAME_PREFIX_UNSUPPORTED:
                return "Playit cannot change the assigned public hostname through this agent key. Custom domains must be configured in the Playit account; the current tunnel address is unchanged.";
            case INVALID_AGENT_CREDENTIAL:
                return "Playit did not accept the stored agent credential.";
            case AGENT_TYPE_NOT_SUPPORTED:
                return "Playit requires a different agent ownership type for this operation.";
            case AGENT_SCOPE_RESTRICTED:
                return "Playit restricted this request to the current agent's resources.";
            case REQUEST_VALIDATION_FAILED:
                return "Playit rejected the request fields.";
            case API_ENDPOINT_UNAVAILABLE:
                return "The requested Playit API endpoint is unavailable.";
            case PROVIDER_INTERNAL_ERROR:
                return "Playit could not complete the request.";
            case TUNNEL_CONFIGURATION_INVALID:
                return "Playit rejected the Minecraft tunnel configuration.";
            case TUNNEL_ALLOCATION_UNAVAILABLE:
                return "Playit has no valid public tunnel allocation for this request.";
            case TUNNEL_NAME_INVALID:
                return "Playit rejected the tunnel name.";
            case FIREWALL_UNAVAILABLE:
                return "The selected Playit firewall is unavailable.";
            case AGENT_VERSION_UNSUPPORTED:
                return "Playit requires a newer agent.";
            case TUNNEL_ENDPOINT_UNSUPPORTED:
                return "Playit does not support this tunnel endpoint and protocol.";
            case REGION_UNAVAILABLE:
                return "The requested Playit region is unavailable for this tunnel.";
            case HTTP_ERROR:
                return "The Playit API request failed.";
            case TRANSPORT_FAILURE:
                return "Could not reach the Playit API.";
            case MALFORMED_RESPONSE:
                return "The Playit API returned an invalid response.";
            case UNEXPECTED_RESPONSE:
            default:
                return "The Playit API returned an unexpected result.";
        }
    }

    @Override
    public String toString() {
        return "PlayitApiException{code=" + code + ", httpStatus=" + httpStatus
                + ", deterministicFailure=" + deterministicFailure + "}";
    }
}
