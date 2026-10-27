package org.phoenix.core.playit;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Authenticated agent-key control-plane calls using the current official agent-key schema. */
public final class PlayitControlPlaneClient {
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;
    private static final int MAX_VALIDATION_MESSAGE_CHARS = 2048;
    private static final Pattern FIELD_VALIDATION_PATTERN = Pattern.compile(
            "(?i)\\b(missing(?:\\s+required)?\\s+field|unknown\\s+field|unrecognized\\s+field)"
                    + "\\s*[`'\\\"\\[]?([A-Za-z][A-Za-z0-9_.-]*)");
    private static final Pattern EXPECTED_TYPE_PATTERN = Pattern.compile(
            "(?i)\\bexpected\\s+(?:(?:a|an)\\s+)?"
                    + "(string|str|object|struct|map|sequence|array|boolean|bool|"
                    + "u8|u16|u32|u64|i8|i16|i32|i64|integer|number|uuid|enum|one\\s+of)\\b");
    private static final Pattern FIELD_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\bfield\\s*[`'\\\"\\[]?([A-Za-z][A-Za-z0-9_.-]*)");

    private final PlayitHttpTransport transport;
    private final PlayitCredential credential;

    /** Current agent-key support for changing a provider-assigned public hostname prefix. */
    public enum PublicHostnameEditCapability {
        /** The agent-key schema exposes no operation that edits an assigned prefix or attaches a domain. */
        NOT_SUPPORTED_BY_AGENT_KEY
    }

    /** Target kinds for a domain returned by Playit's read-only domain-list endpoint. */
    public enum DomainTargetType {
        IP_ADDRESS,
        TUNNEL,
        EXTERNAL_CNAME,
        GATEWAY
    }

    /** Read-only metadata for a domain already associated with the Playit account. */
    public static final class DomainOption {
        private final UUID id;
        private final String name;
        private final boolean external;
        private final UUID parentId;
        private final DomainTargetType targetType;
        private final UUID targetId;

        private DomainOption(
                UUID id,
                String name,
                boolean external,
                UUID parentId,
                DomainTargetType targetType,
                UUID targetId) {
            this.id = id;
            this.name = name;
            this.external = external;
            this.parentId = parentId;
            this.targetType = targetType;
            this.targetId = targetId;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean isExternal() {
            return external;
        }

        public Optional<UUID> parentId() {
            return Optional.ofNullable(parentId);
        }

        public Optional<DomainTargetType> targetType() {
            return Optional.ofNullable(targetType);
        }

        /** Present only when the provider's target is a tunnel or gateway ID. */
        public Optional<UUID> targetId() {
            return Optional.ofNullable(targetId);
        }
    }

    /** Address form returned by the provider's account-tunnel inventory. */
    public enum ConnectAddressType {
        ADDR4,
        ADDR6,
        IP4,
        IP6,
        AUTO,
        DOMAIN
    }

    /** A typed connect-address value copied from the provider response without synthesizing a host. */
    public static final class TunnelConnectAddress {
        private final ConnectAddressType type;
        private final String address;
        private final String domainName;
        private final UUID domainId;
        private final Integer defaultPort;
        private final String domainMode;

        private TunnelConnectAddress(
                ConnectAddressType type,
                String address,
                String domainName,
                UUID domainId,
                Integer defaultPort,
                String domainMode) {
            this.type = type;
            this.address = address;
            this.domainName = domainName;
            this.domainId = domainId;
            this.defaultPort = defaultPort;
            this.domainMode = domainMode;
        }

        public ConnectAddressType type() {
            return type;
        }

        /** Literal address field returned by Playit. */
        public Optional<String> address() {
            return Optional.ofNullable(address);
        }

        public Optional<String> domainName() {
            return Optional.ofNullable(domainName);
        }

        public Optional<UUID> domainId() {
            return Optional.ofNullable(domainId);
        }

        /** Present for provider-returned ip4/ip6 values; the core does not concatenate it into a host. */
        public Optional<Integer> defaultPort() {
            return Optional.ofNullable(defaultPort);
        }

        public Optional<String> domainMode() {
            return Optional.ofNullable(domainMode);
        }
    }

    /** Selected provider fields from one account tunnel, including only server-returned address data. */
    public static final class TunnelInventoryItem {
        private final UUID id;
        private final String name;
        private final boolean userEnabled;
        private final String tunnelType;
        private final String portType;
        private final int portCount;
        private final UUID agentId;
        private final List<String> publicHostnames;
        private final List<TunnelConnectAddress> connectAddresses;
        private final List<PublicAllocation> allocations;
        private String hostnameVerifyLevel;

        private TunnelInventoryItem(
                UUID id,
                String name,
                boolean userEnabled,
                String tunnelType,
                String portType,
                int portCount,
                UUID agentId,
                List<String> publicHostnames,
                List<TunnelConnectAddress> connectAddresses,
                List<PublicAllocation> allocations) {
            this.id = id;
            this.name = name;
            this.userEnabled = userEnabled;
            this.tunnelType = tunnelType;
            this.portType = portType;
            this.portCount = portCount;
            this.agentId = agentId;
            this.publicHostnames = List.copyOf(publicHostnames);
            this.connectAddresses = List.copyOf(connectAddresses);
            this.allocations = List.copyOf(allocations);
        }

        /** "None", "NoRawIp" or "NoAutoName" as Playit reports it for this tunnel; empty when not reported. */
        public Optional<String> hostnameVerifyLevel() {
            return Optional.ofNullable(hostnameVerifyLevel);
        }

        /** Port allocations with the concrete host and port behind an auto name (empty for gateways). */
        public List<PublicAllocation> allocations() {
            return allocations;
        }

        public UUID id() {
            return id;
        }

        public Optional<String> name() {
            return Optional.ofNullable(name);
        }

        public boolean userEnabled() {
            return userEnabled;
        }

        public Optional<String> tunnelType() {
            return Optional.ofNullable(tunnelType);
        }

        public String portType() {
            return portType;
        }

        public int portCount() {
            return portCount;
        }

        public Optional<UUID> agentId() {
            return Optional.ofNullable(agentId);
        }

        /** Actual public hostnames from PortAllocation.auto_domain or Gateway.hostname. */
        public List<String> publicHostnames() {
            return publicHostnames;
        }

        /** Typed connect-address variants returned by the provider; no host or port is fabricated. */
        public List<TunnelConnectAddress> connectAddresses() {
            return connectAddresses;
        }
    }

    /** A PortAllocation exactly as returned: the auto name and the concrete host, IP and port players reach. */
    public static final class PublicAllocation {
        private final String autoDomain;
        private final String ipHostname;
        private final String ip;
        private final Integer port;

        private PublicAllocation(String autoDomain, String ipHostname, String ip, Integer port) {
            this.autoDomain = autoDomain;
            this.ipHostname = ipHostname;
            this.ip = ip;
            this.port = port;
        }

        public Optional<String> autoDomain() {
            return Optional.ofNullable(autoDomain);
        }

        public Optional<String> ipHostname() {
            return Optional.ofNullable(ipHostname);
        }

        public Optional<String> ip() {
            return Optional.ofNullable(ip);
        }

        public Optional<Integer> port() {
            return Optional.ofNullable(port);
        }
    }

    public PlayitControlPlaneClient(PlayitHttpTransport transport, PlayitCredential credential) {
        if (transport == null || credential == null) {
            throw new IllegalArgumentException("HTTP transport and Playit credential are required");
        }
        this.transport = transport;
        this.credential = credential;
    }

    /**
     * Reports the provider capability without making a request. Tunnel labels can be renamed, but
     * the current public hostname prefix cannot be changed through the agent-key control plane.
     */
    public static PublicHostnameEditCapability publicHostnameEditCapability() {
        return PublicHostnameEditCapability.NOT_SUPPORTED_BY_AGENT_KEY;
    }

    /**
     * Lists domain metadata returned by Playit for the linked account. This is read-only and does
     * not assign a listed domain to the active tunnel.
     */
    public List<DomainOption> getDomainOptions() throws PlayitApiException {
        PlayitHttpTransport.Response response = post("/domains/list", "{}");
        Map<String, Object> data = successfulData(response, ignored -> PlayitApiException.Code.UNEXPECTED_RESPONSE);
        try {
            return parseDomainOptions(requiredList(data, "domains"));
        } catch (RuntimeException error) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, response.statusCode());
        }
    }

    /**
     * Performs Playit's read-only custom-domain availability check. It does not purchase,
     * register, attach, or change any domain.
     */
    public boolean isCustomDomainNameAvailable(String name) throws PlayitApiException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty custom-domain name is required");
        }
        PlayitHttpTransport.Response response = post(
                "/shop/availability/custom_domain",
                "{\"name\":" + PlayitJson.quote(name) + "}");
        Map<String, Object> data = successfulData(response, ignored -> PlayitApiException.Code.UNEXPECTED_RESPONSE);
        Object available = data.get("is_available");
        if (!(available instanceof Boolean)) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, response.statusCode());
        }
        return (Boolean) available;
    }

    /**
     * Reads the real runtime identity, pending tunnel IDs, and public addresses
     * returned by Playit. Empty addresses remain empty; this method never makes
     * an address from a tunnel ID or local server port.
     */
    public AgentRunData getAgentRunData() throws PlayitApiException {
        Map<String, Object> data = successfulData(post("/v1/agents/rundata", "{}"));
        try {
            UUID agentId = UUID.fromString(requiredString(data, "agent_id"));
            List<AssignedTunnel> tunnels = parseAssigned(requiredList(data, "tunnels"));
            List<PendingTunnel> pending = parsePending(requiredList(data, "pending"));
            return new AgentRunData(agentId, tunnels, pending);
        } catch (RuntimeException error) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, 200);
        }
    }

    /**
     * Creates a Minecraft Java tunnel using the current official agent-key v1 schema. The result
     * is the API-assigned tunnel ID only. Call {@link #getAgentRunData()} to
     * inspect allocation state and any actual server-provided address.
     */
    public UUID createMinecraftJavaTunnel(
            UUID agentId,
            String localIp,
            int localPort,
            String name) throws PlayitApiException {
        return createMinecraftJavaTunnel(agentId, localIp, localPort, name, true);
    }

    /**
     * Creates a Minecraft Java tunnel with the requested user-enabled state. Preview callers can
     * request {@code enabled=false} and then inspect {@link #getTunnelInventory()} for an actual
     * provider-assigned hostname before presenting it. Whether disabled tunnels receive an
     * allocation remains provider behavior; this method never invents a preview address.
     */
    public UUID createMinecraftJavaTunnel(
            UUID agentId,
            String localIp,
            int localPort,
            String name,
            boolean enabled) throws PlayitApiException {
        if (agentId == null || localIp == null || localIp.trim().isEmpty()
                || localIp.indexOf('\r') >= 0 || localIp.indexOf('\n') >= 0
                || localPort < 1 || localPort > 65535 || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "A runtime agent ID, tunnel name, and valid local server address are required");
        }

        return createTunnel(agentId, localIp, localPort, name, TunnelSpec.minecraftJava(), enabled);
    }

    /** What a tunnel carries: a Playit tunnel type, or raw UDP/TCP ports for software Playit has no type for. */
    public static final class TunnelSpec {
        private final String tunnelType;
        private final String portType;
        private final String description;

        private TunnelSpec(String tunnelType, String portType, String description) {
            this.tunnelType = tunnelType;
            this.portType = portType;
            this.description = description;
        }

        /** Minecraft Java Edition; free tunnels are routed by hostname on Playit's shared port. */
        public static TunnelSpec minecraftJava() {
            return new TunnelSpec("minecraft-java", "tcp", null);
        }

        /** Minecraft Bedrock Edition over RakNet UDP, as Geyser speaks it. */
        public static TunnelSpec minecraftBedrock() {
            return new TunnelSpec("minecraft-bedrock", "udp", null);
        }

        /** One UDP port for software without a Playit tunnel type, such as Simple Voice Chat. */
        public static TunnelSpec udp(String softwareDescription) {
            return raw("udp", softwareDescription);
        }

        /**
         * One raw TCP port. Playit forwards it without reading the traffic, so unlike a Minecraft Java tunnel it doesn't
         * check the hostname a client names in its handshake: any DNS name pointing at it works.
         */
        public static TunnelSpec tcp(String softwareDescription) {
            return raw("tcp", softwareDescription);
        }

        private static TunnelSpec raw(String portType, String softwareDescription) {
            if (softwareDescription == null || softwareDescription.trim().isEmpty() || softwareDescription.length() > 64) {
                throw new IllegalArgumentException("A short software description is required");
            }
            return new TunnelSpec(null, portType, softwareDescription);
        }

        /** The Playit tunnel type, or empty for raw ports. */
        public Optional<String> tunnelType() {
            return Optional.ofNullable(tunnelType);
        }

        public String portType() {
            return portType;
        }

        /** The current agent-key schema (Playit's Minecraft plugin): protocol plus endpoint. */
        String currentSchemaProtocol() {
            if (tunnelType != null) {
                return "{\"type\":\"tunnel-type\",\"details\":" + PlayitJson.quote(tunnelType) + "}";
            }
            return "{\"type\":\"raw-ports\",\"details\":{\"port_type\":" + PlayitJson.quote(portType)
                    + ",\"port_count\":1,\"software_description\":" + PlayitJson.quote(description) + "}}";
        }

        /** The agent's own API definition (playit-agent api_client): ports plus alloc. */
        String agentSchemaPorts() {
            if (tunnelType != null) {
                return "{\"type\":\"tunnel-type\",\"details\":" + PlayitJson.quote(tunnelType) + "}";
            }
            return "{\"type\":\"custom-" + portType + "\",\"details\":1}";
        }

        @Override
        public String toString() {
            return "PlayitTunnelSpec{" + (tunnelType != null ? tunnelType : portType) + "}";
        }
    }

    /**
     * Creates one tunnel for this agent and returns the API-assigned ID. The request uses the current agent-key schema;
     * only when Playit rejects that body as invalid (a deterministic validation error, so nothing was created) is the
     * agent's own documented schema sent once instead. Any other failure is returned as is and never retried here:
     * callers reconcile with {@link #getAgentRunData()} before another attempt.
     */
    public UUID createTunnel(UUID agentId, String localIp, int localPort, String name, TunnelSpec spec, boolean enabled)
            throws PlayitApiException {
        if (agentId == null || spec == null || localIp == null || localIp.trim().isEmpty()
                || localIp.indexOf('\r') >= 0 || localIp.indexOf('\n') >= 0
                || localPort < 1 || localPort > 65535 || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "A runtime agent ID, tunnel name, tunnel kind and valid local server address are required");
        }
        String origin = agentOrigin(agentId, localIp, localPort);
        String current = "{\"name\":" + PlayitJson.quote(name)
                + ",\"protocol\":" + spec.currentSchemaProtocol()
                + ",\"origin\":" + origin
                + ",\"endpoint\":{\"type\":\"region\",\"details\":{\"region\":\"global\",\"port\":null}}"
                + ",\"enabled\":" + enabled + ",\"firewall_id\":null}";
        Map<String, Object> data;
        try {
            data = successfulData(post("/v1/tunnels/create", current));
        } catch (PlayitApiException rejected) {
            if (rejected.code() != PlayitApiException.Code.REQUEST_VALIDATION_FAILED || !rejected.isDeterministicFailure()) {
                throw rejected;
            }
            String agentSchema = "{\"ports\":" + spec.agentSchemaPorts()
                    + ",\"origin\":" + origin
                    + ",\"enabled\":" + enabled
                    + ",\"alloc\":{\"type\":\"region\",\"details\":{\"region\":\"global\",\"port\":null}}"
                    + ",\"name\":" + PlayitJson.quote(name) + ",\"firewall_id\":null}";
            try {
                data = successfulData(post("/v1/tunnels/create", agentSchema));
            } catch (PlayitApiException second) {
                // Both bodies invalid: report the current schema's rejection, which carries the useful hint.
                if (second.code() == PlayitApiException.Code.REQUEST_VALIDATION_FAILED && second.isDeterministicFailure()) {
                    throw rejected;
                }
                throw second;
            }
        }
        try {
            return UUID.fromString(requiredString(data, "id"));
        } catch (RuntimeException error) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, 200);
        }
    }

    /** Points an existing tunnel of this agent at another local address, keeping its public address. */
    public void configureTunnelTarget(UUID tunnelId, String localIp, int localPort) throws PlayitApiException {
        if (tunnelId == null || localIp == null || localIp.trim().isEmpty() || localPort < 1 || localPort > 65535
                || localIp.indexOf('\r') >= 0 || localIp.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("An existing tunnel ID and a valid local server address are required");
        }
        String body = "{\"tunnel_id\":" + PlayitJson.quote(tunnelId.toString())
                + ",\"new_agent_id\":null,\"new_config\":" + configFields(localIp, localPort) + "}";
        successfulVoid(post("/v1/tunnels/config", body), PlayitControlPlaneClient::mapConfigFailure);
    }

    private static String configFields(String localIp, int localPort) {
        return "{\"fields\":[{\"name\":\"local_ip\",\"value\":" + PlayitJson.quote(localIp)
                + "},{\"name\":\"local_port\",\"value\":" + PlayitJson.quote(Integer.toString(localPort)) + "}]}";
    }

    private static String agentOrigin(UUID agentId, String localIp, int localPort) {
        return "{\"type\":\"agent\",\"data\":{\"agent_id\":" + PlayitJson.quote(agentId.toString())
                + ",\"config\":" + configFields(localIp, localPort) + "}}";
    }

    private static PlayitApiException.Code mapConfigFailure(Object data) {
        Map<String, Object> tagged = stringMap(data);
        Object error = tagged != null ? tagged.get("error") : data;
        String failure = error instanceof String ? (String) error : "";
        switch (failure) {
            case "TunnelNotFound":
                return PlayitApiException.Code.TUNNEL_NOT_FOUND;
            case "AgentNotFound":
            case "CannotConfigTunnelWithoutAgent":
                return PlayitApiException.Code.AGENT_UNAVAILABLE;
            case "AgentVersionUnknown":
                return PlayitApiException.Code.AGENT_VERSION_UNSUPPORTED;
            case "InvalidConfig":
            case "ConfigNotCompatibleWithAgent":
                return PlayitApiException.Code.TUNNEL_CONFIGURATION_INVALID;
            case "SelfManagedAgentCannotReassignTunnel":
                return PlayitApiException.Code.AGENT_SCOPE_RESTRICTED;
            default:
                return PlayitApiException.Code.TUNNEL_REJECTED;
        }
    }

    /**
     * Sets how strictly Playit checks the name a Java client sends: "None" (any name, so a custom DNS name works in the
     * server list too), "NoRawIp" or "NoAutoName". Playit may refuse this on free accounts (PREMIUM_REQUIRED).
     */
    public void setHostnameVerifyLevel(UUID tunnelId, String level) throws PlayitApiException {
        if (tunnelId == null || !("None".equals(level) || "NoRawIp".equals(level) || "NoAutoName".equals(level))) {
            throw new IllegalArgumentException("An existing tunnel ID and a known verify level are required");
        }
        String body = "{\"tunnel_id\":" + PlayitJson.quote(tunnelId.toString())
                + ",\"details\":{\"type\":\"hostname_verify_level\",\"value\":" + PlayitJson.quote(level) + "}}";
        successfulVoid(post("/v1/tunnels/propset", body), PlayitControlPlaneClient::mapPropsetFailure);
    }

    private static PlayitApiException.Code mapPropsetFailure(Object data) {
        String failure = data instanceof String ? (String) data : "";
        switch (failure) {
            case "RequiresPermium":
            case "RequiresPremium":
                return PlayitApiException.Code.PREMIUM_REQUIRED;
            case "TunnelNotFound":
                return PlayitApiException.Code.TUNNEL_NOT_FOUND;
            case "PropertyValueNotSupportedForTunnelType":
                return PlayitApiException.Code.TUNNEL_CONFIGURATION_INVALID;
            default:
                return PlayitApiException.Code.TUNNEL_REJECTED;
        }
    }

    /** Lists the account's tunnel inventory exactly as returned by the current v1 schema. */
    public List<TunnelInventoryItem> getTunnelInventory() throws PlayitApiException {
        PlayitHttpTransport.Response response = post("/v1/tunnels/list", "{}");
        Map<String, Object> data = successfulData(response, ignored -> PlayitApiException.Code.UNEXPECTED_RESPONSE);
        try {
            return parseTunnelInventory(requiredList(data, "tunnels"));
        } catch (RuntimeException error) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, response.statusCode());
        }
    }

    /** Renames the existing server tunnel without changing its ID or allocation. */
    public void renameTunnel(UUID tunnelId, String name) throws PlayitApiException {
        if (tunnelId == null || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("An existing tunnel ID and non-empty name are required");
        }
        successfulVoid(post("/tunnels/rename", renameTunnelRequest(tunnelId, name)),
                PlayitControlPlaneClient::mapRenameFailure);
    }

    /** Enables or disables an existing tunnel by its provider-assigned UUID. */
    public void setTunnelEnabled(UUID tunnelId, boolean enabled) throws PlayitApiException {
        if (tunnelId == null) {
            throw new IllegalArgumentException("An existing tunnel ID is required");
        }
        successfulVoid(post("/tunnels/enable", enableTunnelRequest(tunnelId, enabled)),
                PlayitControlPlaneClient::mapTunnelNotFoundFailure);
    }

    /**
     * Deletes an existing tunnel by its provider-assigned UUID. App callers must only invoke this
     * for an unused preview tunnel they created and persisted as app-owned; this core method does
     * not decide ownership or delete unrelated/account-owned tunnels.
     */
    public void deleteTunnel(UUID tunnelId) throws PlayitApiException {
        if (tunnelId == null) {
            throw new IllegalArgumentException("An existing tunnel ID is required");
        }
        successfulVoid(post("/tunnels/delete", deleteTunnelRequest(tunnelId)),
                PlayitControlPlaneClient::mapTunnelNotFoundFailure);
    }

    /**
     * Explicitly rejects an unsupported public-hostname mutation without contacting Playit. The
     * current Agent-Key API can list domains and query custom-domain name availability, but it has
     * no documented endpoint that changes an assigned prefix or attaches a domain to this tunnel.
     *
     * @throws PlayitApiException with {@code PUBLIC_HOSTNAME_PREFIX_UNSUPPORTED}; no network call
     *         is made and the existing tunnel ID and public address remain untouched
     */
    public void changePublicHostnamePrefix(UUID tunnelId, String prefix) throws PlayitApiException {
        if (tunnelId == null || prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("An existing tunnel ID and non-empty hostname prefix are required");
        }
        throw new PlayitApiException(PlayitApiException.Code.PUBLIC_HOSTNAME_PREFIX_UNSUPPORTED, 0);
    }

    private PlayitHttpTransport.Response post(String path, String body) throws PlayitApiException {
        if (credential.isClosed()) {
            throw new PlayitApiException(PlayitApiException.Code.CREDENTIAL_UNAVAILABLE, 0);
        }
        final PlayitHttpTransport.Response[] result = new PlayitHttpTransport.Response[1];
        try {
            credential.useSecret(secret -> {
                String key = new String(secret).trim();
                if (key.isEmpty()) {
                    throw new SafeClientFailure(PlayitApiException.Code.CREDENTIAL_UNAVAILABLE);
                }
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Agent-Key " + key);
                URI uri = URI.create(PlayitClaimClient.API_BASE_URL + path);
                try (PlayitHttpTransport.Request request = new PlayitHttpTransport.Request(uri, headers, body)) {
                    result[0] = transport.execute(request);
                } catch (IOException | RuntimeException error) {
                    throw new SafeClientFailure(PlayitApiException.Code.TRANSPORT_FAILURE);
                }
            });
        } catch (SafeClientFailure failure) {
            throw new PlayitApiException(failure.code, 0);
        } catch (IllegalStateException error) {
            throw new PlayitApiException(PlayitApiException.Code.CREDENTIAL_UNAVAILABLE, 0);
        } catch (Exception error) {
            throw new PlayitApiException(PlayitApiException.Code.TRANSPORT_FAILURE, 0);
        }
        if (result[0] == null) {
            throw new PlayitApiException(PlayitApiException.Code.TRANSPORT_FAILURE, 0);
        }
        return result[0];
    }

    private static Map<String, Object> successfulData(PlayitHttpTransport.Response response)
            throws PlayitApiException {
        return successfulData(response, PlayitControlPlaneClient::mapTunnelFailure);
    }

    private static Map<String, Object> successfulData(
            PlayitHttpTransport.Response response,
            FailureMapper failureMapper) throws PlayitApiException {
        Map<String, Object> envelope = successfulEnvelope(response, failureMapper);
        Map<String, Object> data = stringMap(envelope.get("data"));
        if (data == null) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, response.statusCode());
        }
        return data;
    }

    private static void successfulVoid(
            PlayitHttpTransport.Response response,
            FailureMapper failureMapper) throws PlayitApiException {
        Map<String, Object> envelope = successfulEnvelope(response, failureMapper);
        if (envelope.get("data") != null) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, response.statusCode());
        }
    }

    private static Map<String, Object> successfulEnvelope(
            PlayitHttpTransport.Response response,
            FailureMapper failureMapper) throws PlayitApiException {
        int status = response.statusCode();
        if (status == 429) {
            throw new PlayitApiException(PlayitApiException.Code.RATE_LIMITED, status);
        }
        String body = response.body();
        if (body.length() > MAX_RESPONSE_CHARS) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
        }
        Map<String, Object> envelope;
        try {
            envelope = stringMap(PlayitJson.parse(body));
            if (envelope == null) {
                throw new IllegalArgumentException("Invalid response");
            }
        } catch (RuntimeException error) {
            PlayitApiException.Code code = status >= 400
                    ? PlayitApiException.Code.HTTP_ERROR
                    : PlayitApiException.Code.MALFORMED_RESPONSE;
            throw new PlayitApiException(code, status);
        }
        Object apiStatusValue = envelope.get("status");
        if (!(apiStatusValue instanceof String) || !envelope.containsKey("data")) {
            throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
        }
        String apiStatus = (String) apiStatusValue;
        Object data = envelope.get("data");
        if ("success".equals(apiStatus)) {
            if (status < 200 || status >= 300) {
                throw new PlayitApiException(PlayitApiException.Code.HTTP_ERROR, status);
            }
            return envelope;
        }
        if ("error".equals(apiStatus)) {
            PlayitApiException.Code code = mapApiError(data);
            throw new PlayitApiException(code, status, isDeterministicApiError(data),
                    extractValidationHint(data));
        }
        if ("fail".equals(apiStatus)) {
            throw new PlayitApiException(failureMapper.map(data), status, true);
        }
        throw new PlayitApiException(PlayitApiException.Code.MALFORMED_RESPONSE, status);
    }

    @FunctionalInterface
    private interface FailureMapper {
        PlayitApiException.Code map(Object data);
    }

    private static PlayitApiException.Code mapApiError(Object data) {
        Map<String, Object> error = stringMap(data);
        if (error == null) {
            return PlayitApiException.Code.HTTP_ERROR;
        }
        String type = error.get("type") instanceof String ? (String) error.get("type") : "";
        Object messageValue = error.get("message");
        String message = messageValue instanceof String ? (String) messageValue : "";
        switch (type) {
            case "auth":
                switch (message) {
                    case "InvalidAgentKey":
                    case "InvalidApiKey":
                    case "InvalidSignature":
                    case "InvalidTimestamp":
                    case "NoLongerValid":
                        return PlayitApiException.Code.INVALID_AGENT_CREDENTIAL;
                    case "AgentNotSelfManaged":
                        return PlayitApiException.Code.AGENT_TYPE_NOT_SUPPORTED;
                    case "SelfManagedAgentCanOnlyAffectSelf":
                        return PlayitApiException.Code.AGENT_SCOPE_RESTRICTED;
                    case "EmailMustBeVerified":
                        return PlayitApiException.Code.ACCOUNT_VERIFICATION_REQUIRED;
                    default:
                        return PlayitApiException.Code.NOT_AUTHORIZED;
                }
            case "validation":
                return PlayitApiException.Code.REQUEST_VALIDATION_FAILED;
            case "path-not-found":
                return PlayitApiException.Code.API_ENDPOINT_UNAVAILABLE;
            case "internal":
                return PlayitApiException.Code.PROVIDER_INTERNAL_ERROR;
            default:
                return PlayitApiException.Code.HTTP_ERROR;
        }
    }

    private static boolean isDeterministicApiError(Object data) {
        Map<String, Object> error = stringMap(data);
        if (error == null || !(error.get("type") instanceof String)) {
            return false;
        }
        String type = (String) error.get("type");
        return "auth".equals(type) || "validation".equals(type) || "path-not-found".equals(type);
    }

    private static PlayitApiException.ValidationHint extractValidationHint(Object data) {
        Map<String, Object> error = stringMap(data);
        if (error == null || !"validation".equals(error.get("type"))) {
            return null;
        }
        Object messageValue = error.get("message");
        if (!(messageValue instanceof String)) {
            return null;
        }
        String message = (String) messageValue;
        if (message.length() > MAX_VALIDATION_MESSAGE_CHARS) {
            return null;
        }

        Matcher fieldFailure = FIELD_VALIDATION_PATTERN.matcher(message);
        if (fieldFailure.find()) {
            String operation = fieldFailure.group(1).toLowerCase(java.util.Locale.ROOT);
            String field = fieldFailure.group(2);
            if (operation.startsWith("missing")) {
                return PlayitApiException.ValidationHint.missingField(field);
            }
            return PlayitApiException.ValidationHint.unknownField(field);
        }

        Matcher expectedType = EXPECTED_TYPE_PATTERN.matcher(message);
        if (!expectedType.find()) {
            return null;
        }
        PlayitApiException.ExpectedType type = expectedType(expectedType.group(1));
        if (type == null) {
            return null;
        }
        Matcher fieldContext = FIELD_CONTEXT_PATTERN.matcher(message);
        String field = fieldContext.find() ? fieldContext.group(1) : null;
        return PlayitApiException.ValidationHint.expectedType(field, type);
    }

    private static PlayitApiException.ExpectedType expectedType(String token) {
        String normalized = token.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        switch (normalized) {
            case "string":
            case "str":
                return PlayitApiException.ExpectedType.STRING;
            case "object":
            case "struct":
            case "map":
                return PlayitApiException.ExpectedType.OBJECT;
            case "sequence":
            case "array":
                return PlayitApiException.ExpectedType.ARRAY;
            case "boolean":
            case "bool":
                return PlayitApiException.ExpectedType.BOOLEAN;
            case "u8":
            case "u16":
            case "u32":
            case "u64":
            case "i8":
            case "i16":
            case "i32":
            case "i64":
            case "integer":
                return PlayitApiException.ExpectedType.INTEGER;
            case "number":
                return PlayitApiException.ExpectedType.NUMBER;
            case "uuid":
                return PlayitApiException.ExpectedType.UUID;
            case "enum":
            case "one of":
                return PlayitApiException.ExpectedType.ENUM;
            default:
                return null;
        }
    }

    private static PlayitApiException.Code mapTunnelFailure(Object data) {
        String failure = data instanceof String ? (String) data : "";
        switch (failure) {
            case "AgentNotFound":
            case "InvalidAgentId":
                return PlayitApiException.Code.AGENT_UNAVAILABLE;
            case "InvalidTunnelConfig":
                return PlayitApiException.Code.TUNNEL_CONFIGURATION_INVALID;
            case "InvalidPortCount":
            case "PortAllocDoesNotMatchPortDetails":
            case "AllocRequestNotSupportedByPorts":
            case "InvalidSoftwareDescription":
                return PlayitApiException.Code.TUNNEL_CONFIGURATION_INVALID;
            case "DedicatedIpNotFound":
            case "PortAllocNotFound":
            case "InvalidIpHostname":
            case "PortAllocCurrentlyAssigned":
            case "InvalidHostnameId":
                return PlayitApiException.Code.TUNNEL_ALLOCATION_UNAVAILABLE;
            case "TunnelNameIsNotAscii":
            case "TunnelNameTooLong":
                return PlayitApiException.Code.TUNNEL_NAME_INVALID;
            case "FirewallNotFound":
                return PlayitApiException.Code.FIREWALL_UNAVAILABLE;
            case "AgentVersionTooOld":
                return PlayitApiException.Code.AGENT_VERSION_UNSUPPORTED;
            case "RequiresPlayitPremium":
            case "PublicPortRequiresPlayitPremium":
            case "RegionRequiresPlayitPremium":
                return PlayitApiException.Code.PREMIUM_REQUIRED;
            case "RequiresVerifiedAccount":
                return PlayitApiException.Code.ACCOUNT_VERIFICATION_REQUIRED;
            case "RegionNotSupported":
            case "TunnelTypeBlockedOnRegion":
                return PlayitApiException.Code.REGION_UNAVAILABLE;
            case "EndpointDoesNotSupportProtocol":
            case "InvalidGatewayId":
            case "GatewayAlreadyHasTunnelType":
            case "GatewayDoesNotSupportTunnelType":
            case "HostnameHasTunnelTypeTarget":
                return PlayitApiException.Code.TUNNEL_ENDPOINT_UNSUPPORTED;
            default:
                return PlayitApiException.Code.TUNNEL_REJECTED;
        }
    }

    private static PlayitApiException.Code mapRenameFailure(Object data) {
        String failure = data instanceof String ? (String) data : "";
        switch (failure) {
            case "TunnelNotFound":
                return PlayitApiException.Code.TUNNEL_NOT_FOUND;
            case "NameTooLong":
                return PlayitApiException.Code.TUNNEL_NAME_INVALID;
            default:
                return PlayitApiException.Code.TUNNEL_REJECTED;
        }
    }

    private static PlayitApiException.Code mapTunnelNotFoundFailure(Object data) {
        String failure = data instanceof String ? (String) data : "";
        return "TunnelNotFound".equals(failure)
                ? PlayitApiException.Code.TUNNEL_NOT_FOUND
                : PlayitApiException.Code.TUNNEL_REJECTED;
    }

    private static List<AssignedTunnel> parseAssigned(List<Object> raw) {
        List<AssignedTunnel> tunnels = new ArrayList<>(raw.size());
        for (Object value : raw) {
            Map<String, Object> tunnel = stringMap(value);
            if (tunnel == null) {
                throw new IllegalArgumentException("Invalid tunnel");
            }
            UUID id = UUID.fromString(requiredString(tunnel, "id"));
            String name = requiredString(tunnel, "name");
            String address = requiredString(tunnel, "display_address");
            Map<String, String> config = agentConfigFields(tunnel.get("agent_config"));
            String reason = disabledReasonName(tunnel.get("disabled_reason"));
            tunnels.add(new AssignedTunnel(id, name, address, nullableString(tunnel, "tunnel_type"),
                    nullableString(tunnel, "port_type"), config.get("local_ip"), portValue(config.get("local_port")), reason));
        }
        return List.copyOf(tunnels);
    }

    private static List<PendingTunnel> parsePending(List<Object> raw) {
        List<PendingTunnel> tunnels = new ArrayList<>(raw.size());
        for (Object value : raw) {
            Map<String, Object> tunnel = stringMap(value);
            if (tunnel == null) {
                throw new IllegalArgumentException("Invalid pending tunnel");
            }
            UUID id = UUID.fromString(requiredString(tunnel, "id"));
            String name = requiredString(tunnel, "name");
            tunnels.add(new PendingTunnel(id, name));
        }
        return List.copyOf(tunnels);
    }

    private static List<TunnelInventoryItem> parseTunnelInventory(List<Object> raw) {
        List<TunnelInventoryItem> tunnels = new ArrayList<>(raw.size());
        for (Object value : raw) {
            Map<String, Object> tunnel = stringMap(value);
            if (tunnel == null) {
                throw new IllegalArgumentException("Invalid tunnel inventory item");
            }
            UUID id = UUID.fromString(requiredString(tunnel, "id"));
            String name = nullableString(tunnel, "name");
            Object enabledValue = tunnel.get("user_enabled");
            if (!(enabledValue instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid tunnel enabled state");
            }
            String tunnelType = nullableString(tunnel, "tunnel_type");
            String portType = requiredString(tunnel, "port_type");
            if (!"tcp".equals(portType) && !"udp".equals(portType) && !"both".equals(portType)) {
                throw new IllegalArgumentException("Invalid tunnel port type");
            }
            int portCount = requiredInteger(tunnel, "port_count", 0, 65535);
            UUID agentId = parseTunnelAgentId(tunnel.get("origin"));
            List<Object> rawAllocations = requiredList(tunnel, "public_allocations");
            List<String> publicHostnames = parsePublicHostnames(rawAllocations);
            List<TunnelConnectAddress> connectAddresses = parseConnectAddresses(
                    requiredList(tunnel, "connect_addresses"));
            TunnelInventoryItem item = new TunnelInventoryItem(id, name, (Boolean) enabledValue, tunnelType,
                    portType, portCount, agentId, publicHostnames, connectAddresses, parseAllocations(rawAllocations));
            Map<String, Object> props = stringMap(tunnel.get("props"));
            Object level = props == null ? null : props.get("hostname_verify_level");
            if (level instanceof String && ((String) level).matches("[A-Za-z]{1,32}")) {
                item.hostnameVerifyLevel = (String) level;
            }
            tunnels.add(item);
        }
        return List.copyOf(tunnels);
    }

    private static UUID parseTunnelAgentId(Object value) {
        Map<String, Object> origin = stringMap(value);
        if (origin == null) {
            throw new IllegalArgumentException("Invalid tunnel origin");
        }
        String type = requiredString(origin, "type");
        Map<String, Object> details = stringMap(origin.get("details"));
        if (details == null) {
            throw new IllegalArgumentException("Invalid tunnel origin details");
        }
        if ("agent".equals(type)) {
            return UUID.fromString(requiredString(details, "agent_id"));
        }
        if ("not-set".equals(type)) {
            return null;
        }
        throw new IllegalArgumentException("Unknown tunnel origin type");
    }

    private static List<String> parsePublicHostnames(List<Object> raw) {
        List<String> hostnames = new ArrayList<>();
        for (Object value : raw) {
            Map<String, Object> allocation = stringMap(value);
            if (allocation == null) {
                throw new IllegalArgumentException("Invalid public allocation");
            }
            String type = requiredString(allocation, "type");
            Map<String, Object> details = stringMap(allocation.get("details"));
            if (details == null) {
                throw new IllegalArgumentException("Invalid public allocation details");
            }
            switch (type) {
                case "PortAllocation":
                    hostnames.add(requiredString(details, "auto_domain"));
                    break;
                case "Gateway":
                    hostnames.add(requiredString(details, "hostname"));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown public allocation type");
            }
        }
        return List.copyOf(hostnames);
    }

    private static List<PublicAllocation> parseAllocations(List<Object> raw) {
        List<PublicAllocation> allocations = new ArrayList<>();
        for (Object value : raw) {
            Map<String, Object> allocation = stringMap(value);
            Map<String, Object> details = allocation == null ? null : stringMap(allocation.get("details"));
            if (details == null || !"PortAllocation".equals(allocation.get("type"))) {
                continue;
            }
            Object port = details.get("port");
            Integer number = null;
            if (port instanceof Number) {
                Number value2 = (Number) port;
                if (value2.doubleValue() == value2.intValue() && value2.intValue() >= 1 && value2.intValue() <= 65535) {
                    number = value2.intValue();
                }
            }
            allocations.add(new PublicAllocation(nullableString(details, "auto_domain"),
                    nullableString(details, "ip_hostname"), nullableString(details, "ip"), number));
        }
        return List.copyOf(allocations);
    }

    private static List<TunnelConnectAddress> parseConnectAddresses(List<Object> raw) {
        List<TunnelConnectAddress> addresses = new ArrayList<>(raw.size());
        for (Object value : raw) {
            Map<String, Object> connect = stringMap(value);
            if (connect == null) {
                throw new IllegalArgumentException("Invalid connect address");
            }
            String type = requiredString(connect, "type");
            Map<String, Object> details = stringMap(connect.get("value"));
            if (details == null) {
                throw new IllegalArgumentException("Invalid connect address details");
            }
            switch (type) {
                case "addr4":
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.ADDR4,
                            requiredString(details, "address"), null, null, null, null));
                    break;
                case "addr6":
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.ADDR6,
                            requiredString(details, "address"), null, null, null, null));
                    break;
                case "ip4":
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.IP4,
                            requiredString(details, "address"), null, null,
                            requiredInteger(details, "default_port", 0, 65535), null));
                    break;
                case "ip6":
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.IP6,
                            requiredString(details, "address"), null, null,
                            requiredInteger(details, "default_port", 0, 65535), null));
                    break;
                case "auto":
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.AUTO,
                            requiredString(details, "address"), null, null, null, null));
                    break;
                case "domain":
                    UUID domainId = UUID.fromString(requiredString(details, "id"));
                    String domain = requiredString(details, "domain");
                    String address = requiredString(details, "address");
                    String mode = requiredString(details, "mode");
                    if (!"Ip".equals(mode) && !"Srv".equals(mode)
                            && !"SrvAndIp".equals(mode) && !"Hostname".equals(mode)) {
                        throw new IllegalArgumentException("Unknown domain connect mode");
                    }
                    addresses.add(new TunnelConnectAddress(ConnectAddressType.DOMAIN,
                            address, domain, domainId, null, mode));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown connect address type");
            }
        }
        return List.copyOf(addresses);
    }

    private static List<DomainOption> parseDomainOptions(List<Object> raw) {
        List<DomainOption> domains = new ArrayList<>(raw.size());
        for (Object value : raw) {
            Map<String, Object> domain = stringMap(value);
            if (domain == null) {
                throw new IllegalArgumentException("Invalid domain");
            }
            UUID id = UUID.fromString(requiredString(domain, "id"));
            String name = requiredString(domain, "name");
            Object externalValue = domain.get("is_external");
            if (!(externalValue instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid domain metadata");
            }
            UUID parentId = optionalUuid(domain.get("parent"));
            DomainTarget target = parseDomainTarget(domain.get("target"));
            domains.add(new DomainOption(id, name, (Boolean) externalValue, parentId,
                    target == null ? null : target.type, target == null ? null : target.id));
        }
        return List.copyOf(domains);
    }

    private static UUID optionalUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Invalid optional UUID");
        }
        return UUID.fromString((String) value);
    }

    private static DomainTarget parseDomainTarget(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> target = stringMap(value);
        if (target == null || !(target.get("type") instanceof String)) {
            throw new IllegalArgumentException("Invalid domain target");
        }
        Map<String, Object> details = stringMap(target.get("details"));
        if (details == null) {
            throw new IllegalArgumentException("Invalid domain target details");
        }
        switch ((String) target.get("type")) {
            case "ip-address":
                requiredString(details, "ip_address");
                return new DomainTarget(DomainTargetType.IP_ADDRESS, null);
            case "tunnel":
                return new DomainTarget(DomainTargetType.TUNNEL,
                        UUID.fromString(requiredString(details, "tunnel_id")));
            case "external-cname":
                requiredString(details, "cname");
                return new DomainTarget(DomainTargetType.EXTERNAL_CNAME, null);
            case "gateway":
                return new DomainTarget(DomainTargetType.GATEWAY,
                        UUID.fromString(requiredString(details, "gateway_id")));
            default:
                throw new IllegalArgumentException("Unknown domain target type");
        }
    }

    private static final class DomainTarget {
        private final DomainTargetType type;
        private final UUID id;

        private DomainTarget(DomainTargetType type, UUID id) {
            this.type = type;
            this.id = id;
        }
    }

    /** {fields:[{name,value}]} as a name-to-value map; anything else reads as no fields. */
    private static Map<String, String> agentConfigFields(Object value) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Object> config = stringMap(value);
        if (config == null || !(config.get("fields") instanceof List)) {
            return fields;
        }
        for (Object entry : (List<?>) config.get("fields")) {
            Map<String, Object> field = stringMap(entry);
            if (field != null && field.get("name") instanceof String && field.get("value") instanceof String) {
                fields.put((String) field.get("name"), (String) field.get("value"));
            }
        }
        return fields;
    }

    private static Integer portValue(String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535 ? port : null;
    }

    /** Disabled reasons are fixed enum names; a string, or an object tagged with one, is accepted. */
    private static String disabledReasonName(Object value) {
        if (value instanceof String) {
            return ((String) value).matches("[A-Za-z0-9_-]{1,64}") ? (String) value : "Disabled";
        }
        Map<String, Object> tagged = stringMap(value);
        if (tagged != null) {
            Object type = tagged.containsKey("type") ? tagged.get("type") : tagged.get("reason");
            return type instanceof String && ((String) type).matches("[A-Za-z0-9_-]{1,64}") ? (String) type : "Disabled";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredList(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Missing API list");
        }
        return (List<Object>) value;
    }

    private static String requiredString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Missing API string");
        }
        return (String) value;
    }

    private static String nullableString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Invalid optional API string");
        }
        return (String) value;
    }

    private static int requiredInteger(Map<String, Object> object, String key, int min, int max) {
        Object value = object.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Missing API integer");
        }
        Number number = (Number) value;
        long integer = number.longValue();
        if (number.doubleValue() != integer || integer < min || integer > max) {
            throw new IllegalArgumentException("Invalid API integer");
        }
        return (int) integer;
    }

    private static String renameTunnelRequest(UUID tunnelId, String name) {
        return "{\"tunnel_id\":" + PlayitJson.quote(tunnelId.toString())
                + ",\"name\":" + PlayitJson.quote(name) + "}";
    }

    private static String enableTunnelRequest(UUID tunnelId, boolean enabled) {
        return "{\"tunnel_id\":" + PlayitJson.quote(tunnelId.toString())
                + ",\"enabled\":" + enabled + "}";
    }

    private static String deleteTunnelRequest(UUID tunnelId) {
        return "{\"tunnel_id\":" + PlayitJson.quote(tunnelId.toString()) + "}";
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (!(key instanceof String)) {
                return null;
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static final class SafeClientFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final PlayitApiException.Code code;

        private SafeClientFailure(PlayitApiException.Code code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    public static final class AgentRunData {
        private final UUID agentId;
        private final List<AssignedTunnel> tunnels;
        private final List<PendingTunnel> pending;

        private AgentRunData(UUID agentId, List<AssignedTunnel> tunnels, List<PendingTunnel> pending) {
            this.agentId = agentId;
            this.tunnels = List.copyOf(tunnels);
            this.pending = List.copyOf(pending);
        }

        public UUID agentId() {
            return agentId;
        }

        public List<AssignedTunnel> tunnels() {
            return tunnels;
        }

        public List<PendingTunnel> pending() {
            return pending;
        }

        public PlayitIdReconciler.ObservedIds observedIds() {
            Set<UUID> ids = new LinkedHashSet<>();
            for (AssignedTunnel tunnel : tunnels) {
                ids.add(tunnel.id());
            }
            for (PendingTunnel tunnel : pending) {
                ids.add(tunnel.id());
            }
            return PlayitIdentityStore.complete(agentId, ids);
        }
    }

    public static final class AssignedTunnel {
        private final UUID id;
        private final String name;
        private final String displayAddress;
        private final String tunnelType;
        private final String portType;
        private final String localIp;
        private final Integer localPort;
        private final String disabledReason;

        private AssignedTunnel(UUID id, String name, String displayAddress, String tunnelType, String portType,
                String localIp, Integer localPort, String disabledReason) {
            this.id = id;
            this.name = name;
            this.displayAddress = displayAddress;
            this.tunnelType = tunnelType;
            this.portType = portType;
            this.localIp = localIp;
            this.localPort = localPort;
            this.disabledReason = disabledReason;
        }

        /** "minecraft-java", "minecraft-bedrock", or empty for a raw-port tunnel. */
        public Optional<String> tunnelType() {
            return Optional.ofNullable(tunnelType);
        }

        /** "tcp", "udp" or "both", when run-data reports it. */
        public Optional<String> portType() {
            return Optional.ofNullable(portType);
        }

        /** The agent-side target from the tunnel's agent config, when run-data reports it. */
        public Optional<String> localIp() {
            return Optional.ofNullable(localIp);
        }

        public Optional<Integer> localPort() {
            return Optional.ofNullable(localPort);
        }

        /** Why Playit disabled the tunnel, as its fixed reason name; empty while enabled. */
        public Optional<String> disabledReason() {
            return Optional.ofNullable(disabledReason);
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        /** Actual value returned by Playit's agent run-data API. */
        public Optional<String> displayAddress() {
            return displayAddress.isEmpty() ? Optional.empty() : Optional.of(displayAddress);
        }
    }

    public static final class PendingTunnel {
        private final UUID id;
        private final String name;

        private PendingTunnel(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }
    }
}
