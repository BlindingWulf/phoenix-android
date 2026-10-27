package org.phoenix.core.playit;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/** Plain-Java assertion harness; run with `java ...PlayitClaimClientTest`. */
public final class PlayitClaimClientTest {
    private static final String SECRET = "agent-key-fixture-do-not-log";

    private PlayitClaimClientTest() {
    }

    public static void main(String[] args) throws Exception {
        createsUpstreamFormatClaimCode();
        parsesPendingStatesAndRequestShape();
        exchangesApprovedClaimWithoutLeakingKey();
        parsesDeniedAndExpiredStates();
        parsesExchangeFailuresAsStates();
        rejectsDeepMalformedJsonAndInvalidApiCodes();
        redactsMalformedAndTransportErrors();
        rejectsInvalidClaimCodes();
        escapesVersionTextInRequestJson();
        persistsCredentialThroughAdapter();
        loadsStoredCredentialAsValidatedDefensiveCopy();
        provisionsAndReadsRealControlPlaneData();
        mapsControlPlaneFailuresWithoutRemoteText();
        classifiesSafeProviderFailuresAndRetryEvidence();
        extractsOnlyAllowlistedValidationHints();
        renamesExistingTunnelWithoutChangingIdentity();
        readsPlayitDomainOptionsAndAvailabilityWithoutPurchasingOrAssigning();
        reportsHostnamePrefixCapabilityWithoutMutatingTunnel();
        reconcilesOnlyObservedPersistentIds();
        createsBedrockAndVoiceTunnelsWithRealLocalPorts();
        fallsBackToAgentSchemaOnlyAfterDeterministicValidation();
        retargetsTunnelAndReadsAllocationsAndAgentConfig();
        System.out.println("PlayitClaimClientTest: all checks passed");
    }

    private static void createsUpstreamFormatClaimCode() throws Exception {
        FakeTransport http = new FakeTransport();
        PlayitClaimClient client = new PlayitClaimClient(http, "Phoenix Android Stage 10A");
        PlayitClaimClient.Claim claim = client.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED);

        check(claim.claimCode().matches("[0-9a-f]{10}"), "claim code must be 5 random bytes in hex");
        equal("https://playit.gg/claim/" + claim.claimCode(), claim.claimUrl(), "official claim page URL");
        check(claim.toString().indexOf(claim.claimCode()) < 0, "claim toString must redact its code");
        equal(0, http.requests.size(), "creating a claim is local and must not make an API request");
    }

    private static void parsesPendingStatesAndRequestShape() throws Exception {
        FakeTransport http = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":\"WaitingForUserVisit\"}"),
                response(200, "{\"status\":\"success\",\"data\":\"WaitingForUser\"}"));
        PlayitClaimClient client = new PlayitClaimClient(http, "Phoenix Android Stage 10A");
        PlayitClaimClient.Claim claim = client.createClaim(PlayitClaimClient.AgentType.ASSIGNABLE);

        equal(PlayitClaimClient.ClaimState.PENDING_USER_VISIT, client.poll(claim).state(), "waiting for browser visit");
        equal(PlayitClaimClient.ClaimState.PENDING_APPROVAL, client.poll(claim).state(), "waiting for browser approval");
        equal(2, http.requests.size(), "setup polling count");
        equal("https://api.playit.gg/claim/setup", http.requests.get(0).uri().toString(), "setup endpoint");
        String setupBody = http.requests.get(0).jsonBody();
        check(setupBody.contains("\"agent_type\":\"assignable\""), "setup request must send upstream agent_type value");
        check(setupBody.contains("\"version\":\"Phoenix Android Stage 10A\""), "setup request must send version text");
        check(setupBody.contains("\"code\":\"" + claim.claimCode() + "\""), "setup request must send claim code");
        equal("POST", http.requests.get(0).method(), "claim API uses POST");
        check(http.headers.get(0).isEmpty(), "claim setup does not send an account or agent authorization header");
        check(http.requests.get(0).toString().indexOf(claim.claimCode()) < 0, "HTTP request toString must redact body");
    }

    private static void exchangesApprovedClaimWithoutLeakingKey() throws Exception {
        FakeTransport http = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":\"UserAccepted\"}"),
                response(200, "{\"status\":\"success\",\"data\":{\"secret_key\":\"" + SECRET + "\"}}"));
        PlayitClaimClient client = new PlayitClaimClient(http, "Phoenix Android Stage 10A");
        PlayitClaimClient.Claim claim = client.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED);
        PlayitClaimClient.ClaimResult result = client.poll(claim);

        equal(PlayitClaimClient.ClaimState.APPROVED, result.state(), "approved claim");
        equal("https://api.playit.gg/claim/exchange", http.requests.get(1).uri().toString(), "exchange endpoint");
        equal("{\"code\":\"" + claim.claimCode() + "\"}", http.requests.get(1).jsonBody(), "exchange request shape");
        String[] value = new String[1];
        result.credential().useSecret(secret -> value[0] = new String(secret));
        equal(SECRET, value[0], "credential is available to the secure-storage adapter");
        check(result.toString().indexOf(SECRET) < 0, "claim result must redact the agent key");
        check(result.credential().toString().indexOf(SECRET) < 0, "credential toString must redact the key");
        result.credential().close();
        check(result.credential().isClosed(), "credential close must clear its in-memory copy");
        boolean closedRejected = false;
        try {
            result.credential().useSecret(secret -> { });
        } catch (IllegalStateException expected) {
            closedRejected = true;
        }
        check(closedRejected, "closed credential cannot be read");
    }

    private static void parsesDeniedAndExpiredStates() throws Exception {
        FakeTransport deniedHttp = new FakeTransport(response(
                200, "{\"status\":\"success\",\"data\":\"UserRejected\"}"));
        PlayitClaimClient deniedClient = new PlayitClaimClient(deniedHttp, "Phoenix Android");
        equal(PlayitClaimClient.ClaimState.DENIED,
                deniedClient.poll(deniedClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)).state(),
                "setup denial");

        FakeTransport expiredHttp = new FakeTransport(response(
                400, "{\"status\":\"fail\",\"data\":\"CodeExpired\"}"));
        PlayitClaimClient expiredClient = new PlayitClaimClient(expiredHttp, "Phoenix Android");
        equal(PlayitClaimClient.ClaimState.EXPIRED,
                expiredClient.poll(expiredClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)).state(),
                "expired setup response");
    }

    private static void parsesExchangeFailuresAsStates() throws Exception {
        FakeTransport denied = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":\"UserAccepted\"}"),
                response(400, "{\"status\":\"fail\",\"data\":\"UserRejected\"}"));
        PlayitClaimClient deniedClient = new PlayitClaimClient(denied, "Phoenix Android");
        equal(PlayitClaimClient.ClaimState.DENIED,
                deniedClient.poll(deniedClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)).state(),
                "exchange denial");

        FakeTransport expired = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":\"UserAccepted\"}"),
                response(400, "{\"status\":\"fail\",\"data\":\"CodeExpired\"}"));
        PlayitClaimClient expiredClient = new PlayitClaimClient(expired, "Phoenix Android");
        equal(PlayitClaimClient.ClaimState.EXPIRED,
                expiredClient.poll(expiredClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)).state(),
                "exchange expiration");

        FakeTransport race = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":\"UserAccepted\"}"),
                response(400, "{\"status\":\"fail\",\"data\":\"NotAccepted\"}"));
        PlayitClaimClient raceClient = new PlayitClaimClient(race, "Phoenix Android");
        equal(PlayitClaimClient.ClaimState.PENDING_APPROVAL,
                raceClient.poll(raceClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)).state(),
                "exchange race remains pending");
    }

    private static void rejectsDeepMalformedJsonAndInvalidApiCodes() throws Exception {
        String nested = "[".repeat(100) + "0" + "]".repeat(100);
        FakeTransport malformed = new FakeTransport(response(
                200, "{\"status\":\"success\",\"data\":" + nested + "}"));
        PlayitClaimClient malformedClient = new PlayitClaimClient(malformed, "Phoenix Android");
        PlayitApiException malformedError = expectApiError(() -> malformedClient.poll(
                malformedClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.MALFORMED_RESPONSE, malformedError.code(), "deep JSON is rejected safely");

        FakeTransport invalid = new FakeTransport(response(
                400, "{\"status\":\"fail\",\"data\":\"InvalidCode\"}"));
        PlayitClaimClient invalidClient = new PlayitClaimClient(invalid, "Phoenix Android");
        PlayitApiException invalidError = expectApiError(() -> invalidClient.poll(
                invalidClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.INVALID_CLAIM_CODE, invalidError.code(), "invalid code stays distinct from expired");

        FakeTransport longVersion = new FakeTransport(response(
                400, "{\"status\":\"fail\",\"data\":\"VersionTextTooLong\"}"));
        PlayitClaimClient longVersionClient = new PlayitClaimClient(longVersion, "Phoenix Android");
        PlayitApiException versionError = expectApiError(() -> longVersionClient.poll(
                longVersionClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.VERSION_TEXT_TOO_LONG, versionError.code(), "version rejection is specific");
    }

    private static void redactsMalformedAndTransportErrors() throws Exception {
        FakeTransport malformed = new FakeTransport(response(
                200, "{\"status\":\"error\",\"data\":{\"message\":\"" + SECRET + "\"}}"));
        PlayitClaimClient malformedClient = new PlayitClaimClient(malformed, "Phoenix Android");
        PlayitApiException apiError = expectApiError(() -> malformedClient.poll(
                malformedClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.HTTP_ERROR, apiError.code(), "API error envelope category");
        check(apiError.getMessage().indexOf(SECRET) < 0, "API message must not contain server text");
        check(apiError.toString().indexOf(SECRET) < 0, "API exception toString must not contain server text");

        FakeTransport badJson = new FakeTransport(response(200, "not-json " + SECRET));
        PlayitClaimClient badJsonClient = new PlayitClaimClient(badJson, "Phoenix Android");
        PlayitApiException malformedError = expectApiError(() -> badJsonClient.poll(
                badJsonClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.MALFORMED_RESPONSE, malformedError.code(), "malformed response category");
        check(malformedError.toString().indexOf(SECRET) < 0, "malformed response text must not escape");

        FakeTransport failedTransport = new FakeTransport();
        failedTransport.failure = new IOException("sensitive transport detail " + SECRET);
        PlayitClaimClient offlineClient = new PlayitClaimClient(failedTransport, "Phoenix Android");
        PlayitApiException networkError = expectApiError(() -> offlineClient.poll(
                offlineClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.TRANSPORT_FAILURE, networkError.code(), "transport error category");
        check(networkError.toString().indexOf(SECRET) < 0, "transport details must not escape");

        FakeTransport rateLimited = new FakeTransport(response(429, "{}"));
        PlayitClaimClient rateClient = new PlayitClaimClient(rateLimited, "Phoenix Android");
        PlayitApiException rateError = expectApiError(() -> rateClient.poll(
                rateClient.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)));
        equal(PlayitApiException.Code.RATE_LIMITED, rateError.code(), "429 category");
    }

    private static void rejectsInvalidClaimCodes() throws Exception {
        PlayitClaimClient client = new PlayitClaimClient(new FakeTransport(), "Phoenix Android");
        PlayitApiException error = expectApiError(() -> client.restoreClaim(
                "https://playit.gg/claim/fake", PlayitClaimClient.AgentType.SELF_MANAGED));
        equal(PlayitApiException.Code.INVALID_CLAIM_CODE, error.code(), "invalid code is not treated as expired");
        equal(0, error.httpStatus(), "local validation has no HTTP status");
    }

    private static void escapesVersionTextInRequestJson() throws Exception {
        FakeTransport http = new FakeTransport(response(
                200, "{\"status\":\"success\",\"data\":\"WaitingForUserVisit\"}"));
        String version = "Phoenix \"Android\"\\Stage 10A";
        PlayitClaimClient client = new PlayitClaimClient(http, version);
        client.poll(client.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED));
        check(http.requests.get(0).jsonBody().contains("Phoenix \\\"Android\\\"\\\\Stage 10A"),
                "version string is JSON-escaped");
        // Playit answers VersionTextTooLong from 31 characters; refuse locally instead of polling a doomed claim.
        new PlayitClaimClient(http, "x".repeat(30));
        expectIllegalArgument(() -> new PlayitClaimClient(http, "x".repeat(31)));
    }

    private static void persistsCredentialThroughAdapter() throws Exception {
        PlayitCredential credential = new PlayitCredential(SECRET);
        char[][] passed = new char[1][];
        String[] storedValue = new String[1];
        credential.persistTo(agentKey -> {
            passed[0] = agentKey;
            storedValue[0] = new String(agentKey);
        });
        equal(SECRET, storedValue[0], "credential store adapter receives agent key");
        check(passed[0] != null, "credential callback receives a temporary char array");
        for (char value : passed[0]) {
            equal('\0', value, "temporary credential copy is cleared after callback");
        }
        check(!credential.isClosed(), "persisting does not silently close a credential needed for API calls");
        credential.close();
    }

    private static void loadsStoredCredentialAsValidatedDefensiveCopy() throws Exception {
        char[] storedSecret = "a1b2c3d4".toCharArray();
        PlayitCredential credential = PlayitCredential.fromStoredSecret(storedSecret);
        storedSecret[0] = '0';

        String[] usedSecret = new String[1];
        credential.useSecret(secret -> usedSecret[0] = new String(secret));
        equal("a1b2c3d4", usedSecret[0], "stored key is copied without converting input to String");
        check(credential.toString().indexOf("a1b2c3d4") < 0, "stored key remains redacted");
        credential.close();

        expectIllegalArgument(() -> PlayitCredential.fromStoredSecret(new char[0]));
        expectIllegalArgument(() -> PlayitCredential.fromStoredSecret("abc".toCharArray()));
        expectIllegalArgument(() -> PlayitCredential.fromStoredSecret("abcz".toCharArray()));
        expectIllegalArgument(() -> PlayitCredential.fromStoredSecret(null));
    }

    private static void provisionsAndReadsRealControlPlaneData() throws Exception {
        UUID agentId = UUID.fromString("50203040-5060-7080-90a0-b0c0d0e0f005");
        UUID activeId = UUID.fromString("60203040-5060-7080-90a0-b0c0d0e0f006");
        UUID pendingId = UUID.fromString("70203040-5060-7080-90a0-b0c0d0e0f007");
        UUID createdId = UUID.fromString("80203040-5060-7080-90a0-b0c0d0e0f008");
        String runData = "{\"status\":\"success\",\"data\":{"
                + "\"agent_id\":\"" + agentId + "\","
                + "\"tunnels\":[{\"id\":\"" + activeId
                + "\",\"name\":\"java\",\"display_address\":\"actual.example.playit.gg:25565\"}],"
                + "\"pending\":[{\"id\":\"" + pendingId + "\",\"name\":\"queued\",\"status_msg\":\"pending allocation\"}],"
                + "\"notices\":[],\"permissions\":{}}}";
        FakeTransport http = new FakeTransport(
                response(200, runData),
                response(200, "{\"status\":\"success\",\"data\":{\"id\":\"" + createdId + "\"}}"));
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(http, credential);

        PlayitControlPlaneClient.AgentRunData state = client.getAgentRunData();
        equal(agentId, state.agentId(), "runtime returns agent ID assigned by Playit");
        equal(activeId, state.tunnels().get(0).id(), "runtime returns assigned tunnel ID");
        equal("actual.example.playit.gg:25565", state.tunnels().get(0).displayAddress().orElseThrow(),
                "address is read from Playit response");
        equal(pendingId, state.pending().get(0).id(), "runtime returns pending tunnel ID");
        equal(Set.of(activeId, pendingId), state.observedIds().tunnelIds(), "active and pending IDs reconcile");

        UUID result = client.createMinecraftJavaTunnel(agentId, "127.0.0.1", 25565, "Phoenix Java");
        equal(createdId, result, "create returns only the Playit-assigned tunnel ID");
        equal("https://api.playit.gg/v1/agents/rundata", http.requests.get(0).uri().toString(), "run-data endpoint");
        equal("https://api.playit.gg/v1/tunnels/create", http.requests.get(1).uri().toString(), "tunnel-create endpoint");
        for (Map<String, String> headers : http.headers) {
            check(headers.get("Authorization").startsWith("Agent-Key "), "control request uses upstream Agent-Key scheme");
            check(headers.get("Authorization").contains(SECRET), "transport receives the claimed agent key");
        }
        String body = http.requests.get(1).jsonBody();
        check(PlayitJson.parse(body) instanceof Map, "tunnel create body is valid JSON");
        check(body.contains("\"protocol\":{\"type\":\"tunnel-type\",\"details\":\"minecraft-java\"}"),
                "create request uses the current agent-key tunnel protocol schema");
        check(body.contains("\"type\":\"agent\",\"data\":{\"agent_id\":\"" + agentId + "\""),
                "create request uses the real runtime agent ID");
        check(body.contains("\"name\":\"local_port\",\"value\":\"25565\""),
                "create request includes the selected local Java server port");
        check(body.contains("\"endpoint\":{\"type\":\"region\",\"details\":{\"region\":\"global\",\"port\":null}}"),
                "create request uses the current region endpoint schema");
        check(body.contains("\"name\":\"Phoenix Java\""), "current schema sends a required tunnel name");
        check(!body.contains("\"ports\":"), "current request omits the legacy ports field");
        check(!body.contains("\"alloc\":"), "current request omits the legacy allocation field");
        expectIllegalArgument(() -> client.createMinecraftJavaTunnel(agentId, "127.0.0.1", 25565, null));
        equal(2, http.requests.size(), "invalid required tunnel name is rejected before another request");
        check(http.requests.get(1).toString().indexOf(SECRET) < 0, "request debug text redacts agent key");
        check(http.requests.get(1).headers().isEmpty(), "sensitive request headers are cleared after transport returns");
        credential.close();
    }

    private static void mapsControlPlaneFailuresWithoutRemoteText() throws Exception {
        FakeTransport premium = new FakeTransport(response(
                400, "{\"status\":\"fail\",\"data\":\"RequiresPlayitPremium\"}"));
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(premium, credential);
        PlayitApiException error = expectApiError(() -> client.createMinecraftJavaTunnel(
                UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f009"), "127.0.0.1", 25565, "Phoenix"));
        equal(PlayitApiException.Code.PREMIUM_REQUIRED, error.code(), "premium requirement is explicit");
        check(error.isDeterministicFailure(), "explicit provider failure is marked deterministic");
        check(error.toString().indexOf(SECRET) < 0, "control error does not include key or remote text");
        credential.close();
    }

    private static void classifiesSafeProviderFailuresAndRetryEvidence() throws Exception {
        UUID agentId = UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f009");

        PlayitControlPlaneClient invalidNameClient = new PlayitControlPlaneClient(
                new FakeTransport(response(400, "{\"status\":\"fail\",\"data\":\"TunnelNameIsNotAscii\"}")),
                new PlayitCredential(SECRET));
        PlayitApiException invalidName = expectApiError(() -> invalidNameClient.createMinecraftJavaTunnel(
                agentId, "127.0.0.1", 25565, "Phoenix"));
        equal(PlayitApiException.Code.TUNNEL_NAME_INVALID, invalidName.code(),
                "known provider failures map to a stable safe category");
        check(invalidName.isDeterministicFailure(), "provider fail envelope is retry-eligible after reconciliation");
        check(!invalidName.getMessage().contains("TunnelNameIsNotAscii"),
                "provider enum is not copied into the user-facing message");

        PlayitControlPlaneClient selfManagedClient = new PlayitControlPlaneClient(
                new FakeTransport(response(403, "{\"status\":\"error\",\"data\":{"
                        + "\"type\":\"auth\",\"message\":\"SelfManagedAgentCanOnlyAffectSelf\"}}")),
                new PlayitCredential(SECRET));
        PlayitApiException selfManaged = expectApiError(() -> selfManagedClient.createMinecraftJavaTunnel(
                agentId, "127.0.0.1", 25565, "Phoenix"));
        equal(PlayitApiException.Code.AGENT_SCOPE_RESTRICTED, selfManaged.code(),
                "self-managed scope failure remains distinguishable without retaining provider text");
        check(selfManaged.isDeterministicFailure(), "explicit auth denial is deterministic");
        check(!selfManaged.toString().contains("SelfManagedAgentCanOnlyAffectSelf"),
                "diagnostic string omits raw provider auth detail");

        PlayitControlPlaneClient internalClient = new PlayitControlPlaneClient(
                new FakeTransport(response(500, "{\"status\":\"error\",\"data\":{"
                        + "\"type\":\"internal\",\"message\":{\"trace_id\":\"private-trace\"}}}")),
                new PlayitCredential(SECRET));
        PlayitApiException internal = expectApiError(() -> internalClient.createMinecraftJavaTunnel(
                agentId, "127.0.0.1", 25565, "Phoenix"));
        equal(PlayitApiException.Code.PROVIDER_INTERNAL_ERROR, internal.code(), "internal provider failure category");
        check(!internal.isDeterministicFailure(), "provider internal error may have an ambiguous outcome");
        check(!internal.toString().contains("private-trace"), "provider trace ID is never retained");

        PlayitControlPlaneClient disconnectedClient = new PlayitControlPlaneClient(
                new FakeTransport(), new PlayitCredential(SECRET));
        PlayitApiException disconnected = expectApiError(() -> disconnectedClient.createMinecraftJavaTunnel(
                agentId, "127.0.0.1", 25565, "Phoenix"));
        equal(PlayitApiException.Code.TRANSPORT_FAILURE, disconnected.code(), "transport failure category");
        check(!disconnected.isDeterministicFailure(), "transport failure is ambiguous and cannot trigger a blind retry");
    }

    private static void extractsOnlyAllowlistedValidationHints() throws Exception {
        String privateValue = "submitted-secret-value-should-not-survive";
        PlayitApiException missing = controlValidationError(
                "missing field `endpoint`; rejected value " + privateValue);
        PlayitApiException.ValidationHint missingHint = missing.validationHint().orElseThrow();
        equal(PlayitApiException.ValidationHintKind.MISSING_FIELD, missingHint.kind(),
                "missing-field validation hint kind");
        equal(Optional.of("endpoint"), missingHint.fieldName(), "allowlisted missing field name");
        check(!missingHint.message().contains(privateValue), "validation hint discards provider values");
        check(!missing.getMessage().contains(privateValue) && !missing.toString().contains(privateValue),
                "exception text remains redacted");

        PlayitApiException unknown = controlValidationError(
                "unknown field `ports`, expected one of tunnel shapes; rejected value " + privateValue);
        PlayitApiException.ValidationHint unknownHint = unknown.validationHint().orElseThrow();
        equal(PlayitApiException.ValidationHintKind.UNKNOWN_FIELD, unknownHint.kind(),
                "unknown-field validation hint kind");
        equal(Optional.of("ports"), unknownHint.fieldName(), "allowlisted unknown field name");
        check(!unknownHint.message().contains("tunnel shapes") && !unknownHint.message().contains(privateValue),
                "unknown-field hint does not retain surrounding provider text");

        PlayitApiException expectedEnum = controlValidationError(
                "invalid type: string, expected one of 'tunnel-type' or 'raw-ports' for field `protocol`"
                        + "; actual value " + privateValue);
        PlayitApiException.ValidationHint typeHint = expectedEnum.validationHint().orElseThrow();
        equal(PlayitApiException.ValidationHintKind.EXPECTED_TYPE, typeHint.kind(),
                "expected-type validation hint kind");
        equal(Optional.of("protocol"), typeHint.fieldName(), "allowlisted expected-type field");
        equal(Optional.of(PlayitApiException.ExpectedType.ENUM), typeHint.expectedType(),
                "expected enum is reduced to a closed type category");
        check(!typeHint.message().contains("raw-ports") && !typeHint.message().contains(privateValue),
                "expected enum candidates and submitted values are discarded");

        PlayitApiException unsupportedField = controlValidationError("unknown field `secret_key`");
        check(unsupportedField.validationHint().isEmpty(), "unallowlisted field names are not exposed");
        check(!unsupportedField.toString().contains("secret_key"), "unallowlisted field is not retained in diagnostics");
    }

    private static PlayitApiException controlValidationError(String providerMessage) throws Exception {
        String body = "{\"status\":\"error\",\"data\":{"+
                "\"type\":\"validation\",\"message\":" + PlayitJson.quote(providerMessage) + "}}";
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(
                new FakeTransport(response(400, body), response(400, body)), credential);
        PlayitApiException error = expectApiError(() -> client.createMinecraftJavaTunnel(
                UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f009"), "127.0.0.1", 25565, "Phoenix"));
        credential.close();
        return error;
    }

    private static void renamesExistingTunnelWithoutChangingIdentity() throws Exception {
        UUID tunnelId = UUID.fromString("a0203040-5060-7080-90a0-b0c0d0e0f00a");
        String newName = "Phoenix \"Java\" \\ public";
        FakeTransport success = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":null}"));
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(success, credential);

        client.renameTunnel(tunnelId, newName);

        PlayitHttpTransport.Request request = success.requests.get(0);
        equal("https://api.playit.gg/tunnels/rename", request.uri().toString(), "rename endpoint path");
        equal("POST", request.method(), "rename uses POST");
        equal("Agent-Key " + SECRET, success.headers.get(0).get("Authorization"),
                "rename uses the claimed agent key");
        equal("{\"tunnel_id\":\"" + tunnelId + "\",\"name\":" + PlayitJson.quote(newName) + "}",
                request.jsonBody(), "rename sends only the tunnel UUID and escaped name");
        Object parsed = PlayitJson.parse(request.jsonBody());
        check(parsed instanceof Map, "rename request is valid JSON");
        Map<?, ?> body = (Map<?, ?>) parsed;
        equal(tunnelId.toString(), body.get("tunnel_id"), "rename targets the persisted tunnel ID");
        equal(newName, body.get("name"), "rename round-trips escaped name");
        check(request.toString().indexOf(SECRET) < 0, "rename request diagnostics redact the agent key");
        check(request.headers().isEmpty(), "rename request clears sensitive headers after transport returns");
        expectIllegalArgument(() -> client.renameTunnel(tunnelId, "  "));
        equal(1, success.requests.size(), "invalid rename name is rejected before another request");
        credential.close();

        PlayitCredential missingCredential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient missingTunnelClient = new PlayitControlPlaneClient(
                new FakeTransport(response(404, "{\"status\":\"fail\",\"data\":\"TunnelNotFound\"}")),
                missingCredential);
        PlayitApiException missing = expectApiError(() -> missingTunnelClient.renameTunnel(tunnelId, "Renamed"));
        equal(PlayitApiException.Code.TUNNEL_NOT_FOUND, missing.code(), "missing provider tunnel maps safely");
        check(missing.isDeterministicFailure(), "explicit rename rejection is deterministic");
        check(!missing.toString().contains(SECRET) && !missing.toString().contains("TunnelNotFound"),
                "rename failure excludes credentials and raw provider text");
        missingCredential.close();

        PlayitCredential longNameCredential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient longNameClient = new PlayitControlPlaneClient(
                new FakeTransport(response(400, "{\"status\":\"fail\",\"data\":\"NameTooLong\"}")),
                longNameCredential);
        PlayitApiException tooLong = expectApiError(() -> longNameClient.renameTunnel(tunnelId, "Renamed"));
        equal(PlayitApiException.Code.TUNNEL_NAME_INVALID, tooLong.code(), "provider name limit maps safely");
        check(!tooLong.getMessage().contains("NameTooLong"), "provider enum is not shown verbatim");
        longNameCredential.close();
    }

    private static void reportsHostnamePrefixCapabilityWithoutMutatingTunnel() throws Exception {
        UUID tunnelId = UUID.fromString("b1203040-5060-7080-90a0-b0c0d0e0f00b");
        String requestedPrefix = "private-prefix-fixture";
        FakeTransport transport = new FakeTransport();
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(transport, credential);

        equal(PlayitControlPlaneClient.PublicHostnameEditCapability.NOT_SUPPORTED_BY_AGENT_KEY,
                PlayitControlPlaneClient.publicHostnameEditCapability(),
                "public hostname capability clearly reports the current agent-key limitation");
        PlayitApiException unsupported = expectApiError(
                () -> client.changePublicHostnamePrefix(tunnelId, requestedPrefix));
        equal(PlayitApiException.Code.PUBLIC_HOSTNAME_PREFIX_UNSUPPORTED, unsupported.code(),
                "hostname changes have a distinct typed unsupported result");
        equal(0, unsupported.httpStatus(), "unsupported capability is rejected locally without an HTTP response");
        check(!unsupported.isDeterministicFailure(), "local capability status is not represented as a provider rejection");
        check(unsupported.getMessage().contains("agent key")
                        && unsupported.getMessage().contains("Custom domains"),
                "safe user message distinguishes the agent-key limit from account domain configuration");
        check(!unsupported.getMessage().contains(requestedPrefix)
                        && !unsupported.toString().contains(requestedPrefix)
                        && !unsupported.toString().contains(SECRET),
                "unsupported result does not retain prefix values or credentials");
        equal(0, transport.requests.size(), "no request is sent when provider hostname editing is unsupported");
        credential.close();
    }

    private static void readsPlayitDomainOptionsAndAvailabilityWithoutPurchasingOrAssigning() throws Exception {
        UUID domainId = UUID.fromString("c2203040-5060-7080-90a0-b0c0d0e0f00c");
        UUID parentId = UUID.fromString("d3203040-5060-7080-90a0-b0c0d0e0f00d");
        UUID linkedTunnelId = UUID.fromString("e4203040-5060-7080-90a0-b0c0d0e0f00e");
        String domainResponse = "{\"status\":\"success\",\"data\":{\"domains\":["
                + "{\"id\":\"" + domainId + "\",\"name\":\"phoenix-example.playit.plus\","
                + "\"is_external\":false,\"parent\":null,\"sub_id\":\"" + parentId + "\","
                + "\"target\":{\"type\":\"tunnel\",\"details\":{\"tunnel_id\":\""
                + linkedTunnelId + "\"}}},"
                + "{\"id\":\"f5203040-5060-7080-90a0-b0c0d0e0f00f\",\"name\":\"example.test\","
                + "\"is_external\":true,\"parent\":\"" + parentId + "\",\"sub_id\":\""
                + domainId + "\",\"target\":null}]}}";
        FakeTransport transport = new FakeTransport(
                response(200, domainResponse),
                response(200, "{\"status\":\"success\",\"data\":{\"is_available\":true}}"),
                response(200, "{\"status\":\"success\",\"data\":{\"is_available\":false}}"));
        PlayitCredential credential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(transport, credential);

        List<PlayitControlPlaneClient.DomainOption> domains = client.getDomainOptions();
        equal(2, domains.size(), "domain list returns provider-owned domain options");
        PlayitControlPlaneClient.DomainOption linkedDomain = domains.get(0);
        equal(domainId, linkedDomain.id(), "domain ID is parsed from the provider response");
        equal("phoenix-example.playit.plus", linkedDomain.name(), "domain name is parsed from the provider response");
        check(!linkedDomain.isExternal(), "provider-managed custom domain is distinguished from external domain");
        equal(Optional.of(PlayitControlPlaneClient.DomainTargetType.TUNNEL), linkedDomain.targetType(),
                "existing tunnel target kind is exposed as a closed enum");
        equal(Optional.of(linkedTunnelId), linkedDomain.targetId(), "existing domain tunnel assignment is read-only metadata");
        equal(Optional.of(parentId), domains.get(1).parentId(), "optional parent domain ID is parsed");
        check(domains.get(1).isExternal(), "external domain metadata is parsed");
        expectUnsupportedOperation(() -> domains.add(linkedDomain));

        PlayitHttpTransport.Request listRequest = transport.requests.get(0);
        equal("https://api.playit.gg/domains/list", listRequest.uri().toString(), "domain listing endpoint path");
        equal("{}", listRequest.jsonBody(), "domain listing sends an empty object");
        equal("Agent-Key " + SECRET, transport.headers.get(0).get("Authorization"),
                "domain listing uses the linked agent key");

        String requestedDomain = "available-example.playit.plus";
        check(client.isCustomDomainNameAvailable(requestedDomain), "availability query returns provider true value");
        check(!client.isCustomDomainNameAvailable("already-taken-example.playit.plus"),
                "availability query returns provider false value");
        PlayitHttpTransport.Request availabilityRequest = transport.requests.get(1);
        equal("https://api.playit.gg/shop/availability/custom_domain", availabilityRequest.uri().toString(),
                "custom domain availability endpoint path");
        equal("{\"name\":" + PlayitJson.quote(requestedDomain) + "}", availabilityRequest.jsonBody(),
                "availability query sends only its documented name field");
        equal(3, transport.requests.size(), "read methods make only list and availability queries");
        check(transport.requests.stream().noneMatch(request -> request.uri().getPath().contains("purchase")
                        || request.uri().getPath().contains("create")),
                "read-only domain queries do not purchase, register, or attach domains");
        check(listRequest.toString().indexOf(SECRET) < 0 && availabilityRequest.toString().indexOf(SECRET) < 0,
                "domain request diagnostics redact the agent key");
        credential.close();

        FakeTransport malformed = new FakeTransport(response(200,
                "{\"status\":\"success\",\"data\":{\"is_available\":\"yes\"}}"));
        PlayitCredential malformedCredential = new PlayitCredential(SECRET);
        PlayitControlPlaneClient malformedClient = new PlayitControlPlaneClient(malformed, malformedCredential);
        PlayitApiException malformedAvailability = expectApiError(
                () -> malformedClient.isCustomDomainNameAvailable("synthetic.example"));
        equal(PlayitApiException.Code.MALFORMED_RESPONSE, malformedAvailability.code(),
                "availability response requires the schema Boolean");
        check(!malformedAvailability.toString().contains("synthetic.example"),
                "malformed availability error excludes the queried name");
        malformedCredential.close();
    }

    private static void reconcilesOnlyObservedPersistentIds() throws Exception {
        UUID agent1 = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f001");
        UUID agent2 = UUID.fromString("20203040-5060-7080-90a0-b0c0d0e0f002");
        UUID tunnel1 = UUID.fromString("30203040-5060-7080-90a0-b0c0d0e0f003");
        UUID tunnel2 = UUID.fromString("40203040-5060-7080-90a0-b0c0d0e0f004");
        MemoryIdentityStore store = new MemoryIdentityStore();

        PlayitIdReconciler.PersistedIds initial = PlayitIdReconciler.reconcile(
                PlayitIdentityStore.complete(agent1, Set.of(tunnel1)), store);
        equal(Optional.of(agent1), initial.agentId(), "first runtime agent ID persists");
        equal(Set.of(tunnel1), initial.tunnelIds(), "first authoritative tunnel IDs persist");

        PlayitIdReconciler.PersistedIds partial = PlayitIdReconciler.reconcile(
                PlayitIdentityStore.partial(agent1, Set.of()), store);
        equal(Set.of(tunnel1), partial.tunnelIds(), "partial observation does not erase known tunnels");

        PlayitIdReconciler.PersistedIds replaced = PlayitIdReconciler.reconcile(
                PlayitIdentityStore.partial(agent2, Set.of()), store);
        equal(Optional.of(agent2), replaced.agentId(), "newly observed agent replaces stale ID");
        equal(Set.of(), replaced.tunnelIds(), "new agent clears old tunnel IDs before authoritative snapshot");

        PlayitIdReconciler.PersistedIds complete = PlayitIdReconciler.reconcile(
                PlayitIdentityStore.complete(agent2, Set.of(tunnel2)), store);
        equal(Set.of(tunnel2), complete.tunnelIds(), "new agent tunnel snapshot is reconciled");
        equal(3, store.saves, "store writes changed snapshots only");
    }

    private static void createsBedrockAndVoiceTunnelsWithRealLocalPorts() throws Exception {
        UUID agentId = UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f010");
        String created = "{\"status\":\"success\",\"data\":{\"id\":\"a0203040-5060-7080-90a0-b0c0d0e0f011\"}}";
        FakeTransport http = new FakeTransport(response(200, created), response(200, created));
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(http, new PlayitCredential(SECRET));
        client.createTunnel(agentId, "127.0.0.1", 19133, "Phoenix Bedrock",
                PlayitControlPlaneClient.TunnelSpec.minecraftBedrock(), true);
        client.createTunnel(agentId, "127.0.0.1", 24454, "Phoenix Voice",
                PlayitControlPlaneClient.TunnelSpec.udp("Simple Voice Chat"), true);
        String bedrock = http.requests.get(0).jsonBody();
        check(bedrock.contains("\"protocol\":{\"type\":\"tunnel-type\",\"details\":\"minecraft-bedrock\"}"), "bedrock uses its tunnel type");
        check(bedrock.contains("{\"name\":\"local_port\",\"value\":\"19133\"}"), "bedrock targets Geyser's real port");
        String voice = http.requests.get(1).jsonBody();
        check(voice.contains("\"raw-ports\"") && voice.contains("\"port_type\":\"udp\"") && voice.contains("\"port_count\":1"),
                "voice is one raw UDP port");
        check(voice.contains("\"software_description\":\"Simple Voice Chat\""), "voice names its software");
        check(voice.contains("{\"name\":\"local_port\",\"value\":\"24454\"}"), "voice targets Simple Voice Chat's port");
        check(!voice.contains(SECRET) && http.headers.get(1).get("Authorization").equals("Agent-Key " + SECRET),
                "key only in the Authorization header");
        PlayitControlPlaneClient.TunnelSpec tcp = PlayitControlPlaneClient.TunnelSpec.tcp("Minecraft Java for a custom name");
        check(tcp.currentSchemaProtocol().contains("\"port_type\":\"tcp\"") && !tcp.tunnelType().isPresent(),
                "the custom-name Java tunnel is one raw TCP port, not a Minecraft Java tunnel");
        equal("{\"type\":\"custom-tcp\",\"details\":1}", tcp.agentSchemaPorts(), "raw TCP in the agent schema");
    }

    private static void fallsBackToAgentSchemaOnlyAfterDeterministicValidation() throws Exception {
        UUID agentId = UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f012");
        String invalid = "{\"status\":\"error\",\"data\":{\"type\":\"validation\",\"message\":\"unknown field `protocol`\"}}";
        String created = "{\"status\":\"success\",\"data\":{\"id\":\"a0203040-5060-7080-90a0-b0c0d0e0f013\"}}";
        FakeTransport http = new FakeTransport(response(400, invalid), response(200, created));
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(http, new PlayitCredential(SECRET));
        UUID id = client.createTunnel(agentId, "127.0.0.1", 24454, "Phoenix Voice",
                PlayitControlPlaneClient.TunnelSpec.udp("Simple Voice Chat"), true);
        equal(UUID.fromString("a0203040-5060-7080-90a0-b0c0d0e0f013"), id, "agent-schema create returns its ID");
        equal(2, http.requests.size(), "exactly one fallback request");
        String fallback = http.requests.get(1).jsonBody();
        check(fallback.contains("\"ports\":{\"type\":\"custom-udp\",\"details\":1}"), "fallback uses the agent schema's custom-udp");
        check(fallback.contains("\"alloc\":{\"type\":\"region\",\"details\":{\"region\":\"global\",\"port\":null}}"),
                "fallback uses the agent schema's region allocation");

        FakeTransport ambiguous = new FakeTransport(response(500,
                "{\"status\":\"error\",\"data\":{\"type\":\"internal\",\"message\":\"x\"}}"));
        PlayitControlPlaneClient flaky = new PlayitControlPlaneClient(ambiguous, new PlayitCredential(SECRET));
        PlayitApiException error = expectApiError(() -> flaky.createTunnel(agentId, "127.0.0.1", 19132, "Phoenix Bedrock",
                PlayitControlPlaneClient.TunnelSpec.minecraftBedrock(), true));
        equal(PlayitApiException.Code.PROVIDER_INTERNAL_ERROR, error.code(), "ambiguous create failure is returned");
        equal(1, ambiguous.requests.size(), "an ambiguous create is never retried with another body");
    }

    private static void retargetsTunnelAndReadsAllocationsAndAgentConfig() throws Exception {
        UUID tunnelId = UUID.fromString("a0203040-5060-7080-90a0-b0c0d0e0f014");
        UUID agentId = UUID.fromString("90203040-5060-7080-90a0-b0c0d0e0f015");
        FakeTransport http = new FakeTransport(
                response(200, "{\"status\":\"success\",\"data\":null}"),
                response(200, "{\"status\":\"success\",\"data\":{\"agent_id\":\"" + agentId + "\",\"tunnels\":[{"
                        + "\"id\":\"" + tunnelId + "\",\"name\":\"Phoenix Bedrock\",\"display_address\":\"abc.gl.at.ply.gg:6695\","
                        + "\"tunnel_type\":\"minecraft-bedrock\",\"port_type\":\"udp\",\"port_count\":1,"
                        + "\"agent_config\":{\"fields\":[{\"name\":\"local_ip\",\"value\":\"127.0.0.1\"},"
                        + "{\"name\":\"local_port\",\"value\":\"19133\"}]},\"disabled_reason\":null}],\"pending\":[]}}"),
                response(200, "{\"status\":\"success\",\"data\":{\"tunnels\":[{\"id\":\"" + tunnelId + "\",\"name\":\"Phoenix Java\","
                        + "\"user_enabled\":true,\"tunnel_type\":\"minecraft-java\",\"port_type\":\"tcp\",\"port_count\":1,"
                        + "\"origin\":{\"type\":\"agent\",\"details\":{\"agent_id\":\"" + agentId + "\"}},"
                        + "\"public_allocations\":[{\"type\":\"PortAllocation\",\"details\":{\"auto_domain\":\"abc.gl.joinmc.link\","
                        + "\"ip_hostname\":\"abc.gl.at.ply.gg\",\"ip\":\"203.0.113.9\",\"port\":25565}}],"
                        + "\"connect_addresses\":[{\"type\":\"auto\",\"value\":{\"address\":\"abc.gl.joinmc.link\"}}]}]}}"));
        PlayitControlPlaneClient client = new PlayitControlPlaneClient(http, new PlayitCredential(SECRET));
        client.configureTunnelTarget(tunnelId, "127.0.0.1", 19133);
        String config = http.requests.get(0).jsonBody();
        check(http.requests.get(0).uri().getPath().equals("/v1/tunnels/config"), "retarget uses tunnels/config");
        check(config.contains("\"new_agent_id\":null") && config.contains("{\"name\":\"local_port\",\"value\":\"19133\"}"),
                "retarget keeps the agent and sets the port");
        PlayitControlPlaneClient.AssignedTunnel assigned = client.getAgentRunData().tunnels().get(0);
        equal(Optional.of("minecraft-bedrock"), assigned.tunnelType(), "run-data tunnel type");
        equal(Optional.of(19133), assigned.localPort(), "run-data local port from agent config");
        equal(Optional.empty(), assigned.disabledReason(), "enabled tunnel has no disabled reason");
        PlayitControlPlaneClient.PublicAllocation allocation = client.getTunnelInventory().get(0).allocations().get(0);
        equal(Optional.of("abc.gl.at.ply.gg"), allocation.ipHostname(), "concrete host behind the auto name");
        equal(Optional.of(25565), allocation.port(), "allocated public port");
        equal(Optional.of("abc.gl.joinmc.link"), allocation.autoDomain(), "auto name");
    }

    private static PlayitHttpTransport.Response response(int status, String body) {
        return new PlayitHttpTransport.Response(status, body);
    }

    private static PlayitApiException expectApiError(ThrowingOperation action) throws Exception {
        try {
            action.run();
        } catch (PlayitApiException error) {
            return error;
        }
        throw new AssertionError("Expected PlayitApiException");
    }

    private static void expectIllegalArgument(ThrowingOperation action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void expectUnsupportedOperation(ThrowingOperation action) throws Exception {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected UnsupportedOperationException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class FakeTransport implements PlayitHttpTransport {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();
        private IOException failure;

        private FakeTransport(Response... responses) {
            for (Response response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public Response execute(Request request) throws IOException {
            requests.add(request);
            headers.add(Map.copyOf(request.headers()));
            if (failure != null) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new IOException("No fake response queued");
            }
            return responses.remove();
        }
    }

    private static final class MemoryIdentityStore implements PlayitIdentityStore {
        private PlayitIdReconciler.PersistedIds ids;
        private int saves;

        @Override
        public Optional<PlayitIdReconciler.PersistedIds> load() {
            return Optional.ofNullable(ids);
        }

        @Override
        public void save(PlayitIdReconciler.PersistedIds ids) {
            this.ids = ids;
            saves++;
        }
    }
}
