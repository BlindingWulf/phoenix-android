package org.phoenix.core.network.netbird;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Client for NetBird Cloud's persistent reverse-proxy Services API. */
public final class NetBirdServicesClient {
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.netbird.io");
    private static final int MAX_RESPONSE_CHARS = 512 * 1024;

    public enum AuthScheme {
        /** Personal access token: `Authorization: Token <token>`. */
        PERSONAL_ACCESS_TOKEN("Token"),
        /** OAuth access token: `Authorization: Bearer <token>`. */
        OAUTH_BEARER("Bearer");

        private final String headerPrefix;
        AuthScheme(String headerPrefix) { this.headerPrefix = headerPrefix; }
    }

    public enum Mode {
        HTTP("http"), TCP("tcp"), UDP("udp"), TLS("tls"), OTHER("other");
        private final String wireValue;
        Mode(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
        static Mode parse(String value) {
            for (Mode mode : values()) if (mode.wireValue.equalsIgnoreCase(value)) return mode;
            // The list endpoint returns every service in an account, not just L4 services. Keep
            // unfamiliar modes parseable so an unrelated service cannot break allocation recovery.
            return OTHER;
        }
    }

    public static final class Target {
        private final String targetId;
        private final String targetType;
        private final String protocol;
        private final String host;
        private final String path;
        private final int port;
        private final boolean enabled;
        private final String sessionIdleTimeout;

        public Target(String targetId, String targetType, String protocol, String host, String path,
                      int port, boolean enabled, String sessionIdleTimeout) {
            this.targetId = nonBlank(targetId, "targetId");
            this.targetType = nonBlank(targetType, "targetType");
            this.protocol = nonBlank(protocol, "protocol").toLowerCase(Locale.ROOT);
            this.host = blankToNull(host);
            this.path = blankToNull(path);
            this.port = validPort(port, "target port");
            this.enabled = enabled;
            this.sessionIdleTimeout = blankToNull(sessionIdleTimeout);
        }

        public static Target peer(String peerId, Mode mode, int localPort) {
            Objects.requireNonNull(mode, "mode");
            return new Target(peerId, "peer", mode.wireValue(), null, null, localPort, true,
                    mode == Mode.UDP ? "2m" : null);
        }

        public String targetId() { return targetId; }
        public String targetType() { return targetType; }
        public String protocol() { return protocol; }
        public String host() { return host; }
        public String path() { return path; }
        public int port() { return port; }
        public boolean enabled() { return enabled; }
        public String sessionIdleTimeout() { return sessionIdleTimeout; }

        private String json() {
            StringBuilder out = new StringBuilder("{")
                    .append("\"target_id\":").append(NetBirdJson.quote(targetId))
                    .append(",\"target_type\":").append(NetBirdJson.quote(targetType))
                    .append(",\"protocol\":").append(NetBirdJson.quote(protocol))
                    .append(",\"port\":").append(port)
                    .append(",\"enabled\":").append(enabled);
            if (host != null) out.append(",\"host\":").append(NetBirdJson.quote(host));
            if (path != null) out.append(",\"path\":").append(NetBirdJson.quote(path));
            if (sessionIdleTimeout != null) {
                out.append(",\"options\":{\"session_idle_timeout\":")
                        .append(NetBirdJson.quote(sessionIdleTimeout)).append('}');
            }
            return out.append('}').toString();
        }
    }

    /** The request has one peer target; the API derives its proxy cluster from the domain. */
    public static final class ServiceSpec {
        private final String name;
        private final String domain;
        private final Mode mode;
        private final int listenPort;
        private final Target target;
        private final boolean enabled;

        public ServiceSpec(String name, String domain, Mode mode, int listenPort, Target target, boolean enabled) {
            this.name = nonBlank(name, "name");
            this.domain = validDomain(domain);
            this.mode = Objects.requireNonNull(mode, "mode");
            if (mode != Mode.TCP && mode != Mode.UDP) {
                throw new IllegalArgumentException("Only TCP and UDP services can be provisioned");
            }
            if (listenPort != 0) validPort(listenPort, "listenPort");
            this.listenPort = listenPort;
            this.target = Objects.requireNonNull(target, "target");
            this.enabled = enabled;
            if (!target.protocol().equals(mode.wireValue())) throw new IllegalArgumentException("Target protocol must match service mode");
        }

        public static ServiceSpec minecraft(String name, String domain, Mode mode,
                                            String peerId, int localPort) {
            return minecraft(name, domain, mode, peerId, localPort, 0);
        }

        /** A fixed [listenPort] needs a cluster that supports custom ports; 0 lets the cluster assign one. */
        public static ServiceSpec minecraft(String name, String domain, Mode mode,
                                            String peerId, int localPort, int listenPort) {
            return new ServiceSpec(name, domain, mode, listenPort, Target.peer(peerId, mode, localPort), true);
        }

        public String name() { return name; }
        public String domain() { return domain; }
        public Mode mode() { return mode; }
        public int listenPort() { return listenPort; }
        public Target target() { return target; }
        public boolean enabled() { return enabled; }

        private String json() {
            return "{\"name\":" + NetBirdJson.quote(name)
                    + ",\"domain\":" + NetBirdJson.quote(domain)
                    + ",\"mode\":" + NetBirdJson.quote(mode.wireValue())
                    + ",\"listen_port\":" + listenPort
                    + ",\"enabled\":" + enabled
                    + ",\"targets\":[" + target.json() + "]}";
        }
    }

    public static final class Domain {
        private final String id;
        private final String domain;
        private final String type;
        private final String targetCluster;
        private final boolean validated;
        private final boolean supportsCustomPorts;
        private final boolean requireSubdomain;

        private Domain(String id, String domain, String type, String targetCluster,
                       boolean validated, boolean supportsCustomPorts, boolean requireSubdomain) {
            this.id = id;
            this.domain = domain;
            this.type = type;
            this.targetCluster = targetCluster;
            this.validated = validated;
            this.supportsCustomPorts = supportsCustomPorts;
            this.requireSubdomain = requireSubdomain;
        }
        public String id() { return id; }
        public String domain() { return domain; }
        public String type() { return type; }
        public String targetCluster() { return targetCluster; }
        public boolean validated() { return validated; }
        public boolean supportsCustomPorts() { return supportsCustomPorts; }
        public boolean requireSubdomain() { return requireSubdomain; }
        public boolean isFree() { return "free".equalsIgnoreCase(type); }
    }

    public static final class ProxyCluster {
        private final String id;
        private final String address;
        private final String type;
        private final boolean online;
        private final int connectedProxies;
        private final boolean supportsCustomPorts;
        private final boolean requireSubdomain;

        private ProxyCluster(String id, String address, String type, boolean online,
                             int connectedProxies, boolean supportsCustomPorts, boolean requireSubdomain) {
            this.id = id;
            this.address = address;
            this.type = type;
            this.online = online;
            this.connectedProxies = connectedProxies;
            this.supportsCustomPorts = supportsCustomPorts;
            this.requireSubdomain = requireSubdomain;
        }
        public String id() { return id; }
        public String address() { return address; }
        public String type() { return type; }
        public boolean online() { return online; }
        public int connectedProxies() { return connectedProxies; }
        public boolean supportsCustomPorts() { return supportsCustomPorts; }
        public boolean requireSubdomain() { return requireSubdomain; }
        public boolean isNetBirdShared() { return "shared".equalsIgnoreCase(type); }
        /** A Bring Your Own Proxy cluster, visible only to the account that registered it. */
        public boolean isAccount() { return "account".equalsIgnoreCase(type); }
    }

    /** Proxy token metadata as listed by the API; the plain token is never returned again. */
    public static final class ProxyToken {
        private final String id;
        private final String name;
        private final boolean revoked;

        private ProxyToken(String id, String name, boolean revoked) {
            this.id = id;
            this.name = name;
            this.revoked = revoked;
        }
        public String id() { return id; }
        public String name() { return name; }
        public boolean revoked() { return revoked; }
    }

    /** A newly created account-scoped proxy token. Its plain value is returned exactly once. */
    public static final class CreatedProxyToken {
        private final String id;
        private final String name;
        private final String plainToken;

        private CreatedProxyToken(String id, String name, String plainToken) {
            this.id = id;
            this.name = name;
            this.plainToken = plainToken;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String plainToken() { return plainToken; }

        @Override public String toString() {
            return "CreatedProxyToken{id=" + id + ", name=" + name + ", plainToken=<redacted>}";
        }
    }

    public static final class ServiceTarget {
        private final String targetId;
        private final String targetType;
        private final String protocol;
        private final String host;
        private final String path;
        private final int port;
        private final boolean enabled;
        private final String sessionIdleTimeout;

        private ServiceTarget(String targetId, String targetType, String protocol, String host,
                              String path, int port, boolean enabled, String sessionIdleTimeout) {
            this.targetId = targetId;
            this.targetType = targetType;
            this.protocol = protocol;
            this.host = host;
            this.path = path;
            this.port = port;
            this.enabled = enabled;
            this.sessionIdleTimeout = sessionIdleTimeout;
        }
        public String targetId() { return targetId; }
        public String targetType() { return targetType; }
        public String protocol() { return protocol; }
        public String host() { return host; }
        public String path() { return path; }
        public int port() { return port; }
        public boolean enabled() { return enabled; }
        public String sessionIdleTimeout() { return sessionIdleTimeout; }
    }

    public static final class Service {
        private final String id;
        private final String name;
        private final String domain;
        private final Mode mode;
        private final int listenPort;
        private final boolean portAutoAssigned;
        private final String proxyCluster;
        private final List<ServiceTarget> targets;
        private final boolean enabled;
        private final boolean terminated;
        private final String status;

        private Service(String id, String name, String domain, Mode mode, int listenPort,
                        boolean portAutoAssigned, String proxyCluster, List<ServiceTarget> targets,
                        boolean enabled, boolean terminated, String status) {
            this.id = id;
            this.name = name;
            this.domain = domain;
            this.mode = mode;
            this.listenPort = listenPort;
            this.portAutoAssigned = portAutoAssigned;
            this.proxyCluster = proxyCluster;
            this.targets = List.copyOf(targets);
            this.enabled = enabled;
            this.terminated = terminated;
            this.status = status;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String domain() { return domain; }
        public Mode mode() { return mode; }
        public int listenPort() { return listenPort; }
        public boolean portAutoAssigned() { return portAutoAssigned; }
        public String proxyCluster() { return proxyCluster; }
        public List<ServiceTarget> targets() { return targets; }
        public boolean enabled() { return enabled; }
        public boolean terminated() { return terminated; }
        public String status() { return status; }
        public boolean active() { return "active".equalsIgnoreCase(status); }

        @Override public String toString() {
            return "NetBirdService{id=" + id + ", name=" + name + ", mode=" + mode
                    + ", listenPort=" + listenPort + ", status=" + status + "}";
        }
    }

    private final NetBirdHttpTransport transport;
    private final URI baseUri;
    private final String token;
    private final AuthScheme authScheme;
    private final LongSupplier epochMillis;

    public NetBirdServicesClient(NetBirdHttpTransport transport, String token, AuthScheme authScheme) {
        this(transport, DEFAULT_BASE_URI, token, authScheme, System::currentTimeMillis);
    }

    public NetBirdServicesClient(NetBirdHttpTransport transport, URI baseUri, String token, AuthScheme authScheme) {
        this(transport, baseUri, token, authScheme, System::currentTimeMillis);
    }

    /** Clock injection makes HTTP-date and delta Retry-After parsing deterministic in tests. */
    public NetBirdServicesClient(NetBirdHttpTransport transport, URI baseUri, String token,
                                 AuthScheme authScheme, LongSupplier epochMillis) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.token = nonBlank(token, "token");
        this.authScheme = Objects.requireNonNull(authScheme, "authScheme");
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    public List<Domain> listDomains() throws NetBirdApiException {
        List<Object> values = requireArray(request("GET", "/api/reverse-proxies/domains", null, true));
        List<Domain> domains = new ArrayList<>(values.size());
        for (Object value : values) domains.add(parseDomain(requireObject(value)));
        return List.copyOf(domains);
    }

    public List<ProxyCluster> listClusters() throws NetBirdApiException {
        List<Object> values = requireArray(request("GET", "/api/reverse-proxies/clusters", null, true));
        List<ProxyCluster> clusters = new ArrayList<>(values.size());
        for (Object value : values) clusters.add(parseCluster(requireObject(value)));
        return List.copyOf(clusters);
    }

    public List<Service> listServices() throws NetBirdApiException {
        List<Object> values = requireArray(request("GET", "/api/reverse-proxies/services", null, true));
        List<Service> services = new ArrayList<>(values.size());
        for (Object value : values) services.add(parseService(requireObject(value)));
        return List.copyOf(services);
    }

    public Service getService(String serviceId) throws NetBirdApiException {
        return parseService(requireObject(request("GET", "/api/reverse-proxies/services/" + pathSegment(serviceId), null, true)));
    }

    public Service createService(ServiceSpec service) throws NetBirdApiException {
        return parseService(requireObject(request("POST", "/api/reverse-proxies/services", Objects.requireNonNull(service, "service").json(), true)));
    }

    public Service updateService(String serviceId, ServiceSpec service) throws NetBirdApiException {
        return parseService(requireObject(request("PUT", "/api/reverse-proxies/services/" + pathSegment(serviceId), Objects.requireNonNull(service, "service").json(), true)));
    }

    public void deleteService(String serviceId) throws NetBirdApiException {
        request("DELETE", "/api/reverse-proxies/services/" + pathSegment(serviceId), null, false);
    }

    /**
     * Creates a Bring Your Own Proxy registration token for this account. [expiresInSeconds] 0 means it does not expire;
     * revoking it disconnects every proxy that uses it within about a minute.
     */
    public CreatedProxyToken createProxyToken(String name, int expiresInSeconds) throws NetBirdApiException {
        if (expiresInSeconds < 0) throw new IllegalArgumentException("Invalid expiresInSeconds");
        String body = "{\"name\":" + NetBirdJson.quote(nonBlank(name, "name")) + ",\"expires_in\":" + expiresInSeconds + "}";
        Map<String, Object> object = requireObject(request("POST", "/api/reverse-proxies/proxy-tokens", body, true));
        try {
            String plain = NetBirdJson.string(object, "plain_token", true);
            if (plain.length() > 1024 || !plain.matches("[A-Za-z0-9_.\\-]+")) throw new IllegalArgumentException("Invalid proxy token");
            return new CreatedProxyToken(NetBirdJson.string(object, "id", true), NetBirdJson.string(object, "name", false), plain);
        } catch (RuntimeException invalid) { throw malformed(invalid); }
    }

    public List<ProxyToken> listProxyTokens() throws NetBirdApiException {
        List<Object> values = requireArray(request("GET", "/api/reverse-proxies/proxy-tokens", null, true));
        List<ProxyToken> tokens = new ArrayList<>(values.size());
        for (Object value : values) {
            Map<String, Object> object = requireObject(value);
            try {
                tokens.add(new ProxyToken(NetBirdJson.string(object, "id", true), NetBirdJson.string(object, "name", false),
                        NetBirdJson.bool(object, "revoked", false)));
            } catch (RuntimeException invalid) { throw malformed(invalid); }
        }
        return List.copyOf(tokens);
    }

    public void revokeProxyToken(String tokenId) throws NetBirdApiException {
        request("DELETE", "/api/reverse-proxies/proxy-tokens/" + pathSegment(tokenId), null, false);
    }

    /** Hides an account cluster. A proxy still running with a valid token keeps its stream until stopped or revoked. */
    public void deleteCluster(String address) throws NetBirdApiException {
        request("DELETE", "/api/reverse-proxies/clusters/" + pathSegment(validDomain(address)), null, false);
    }

    @Override public String toString() {
        return "NetBirdServicesClient{baseUri=" + baseUri + ", token=<redacted>, authScheme=" + authScheme + "}";
    }

    private Object request(String method, String path, String body, boolean expectJson) throws NetBirdApiException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", authScheme.headerPrefix + " " + token);
        if (body != null) headers.put("Content-Type", "application/json");
        NetBirdHttpTransport.Response response;
        try {
            response = transport.execute(new NetBirdHttpTransport.Request(method, baseUri.resolve(path), headers, body));
        } catch (IOException | RuntimeException failure) {
            throw new NetBirdApiException(NetBirdApiException.Code.TRANSPORT_FAILURE, 0, null, failure);
        }
        if (response == null) throw new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, 0);
        int status = response.statusCode();
        if (status < 200 || status >= 300) throw classify(response);
        if (!expectJson) return null;
        if (response.body().length() > MAX_RESPONSE_CHARS) {
            throw new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, status);
        }
        try { return NetBirdJson.parse(response.body()); }
        catch (RuntimeException invalid) {
            throw new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, status, null, invalid);
        }
    }

    private NetBirdApiException classify(NetBirdHttpTransport.Response response) {
        int status = response.statusCode();
        NetBirdApiException.Code code;
        if (status == 401) code = NetBirdApiException.Code.CREDENTIAL_REJECTED;
        else if (status == 403) code = NetBirdApiException.Code.PERMISSION_DENIED;
        else if (status == 404) code = NetBirdApiException.Code.NOT_FOUND;
        else if (status == 429) {
            code = NetBirdApiException.Code.RATE_LIMITED;
            return new NetBirdApiException(code, status,
                    NetBirdApiException.parseRetryAfter(response.header("Retry-After").orElse(null), epochMillis.getAsLong()), null);
        } else if (status >= 500) code = NetBirdApiException.Code.PROVIDER_UNAVAILABLE;
        else if (status >= 400) code = NetBirdApiException.Code.INVALID_REQUEST;
        else code = NetBirdApiException.Code.UNEXPECTED_STATUS;
        return new NetBirdApiException(code, status);
    }

    private static Domain parseDomain(Map<String, Object> object) throws NetBirdApiException {
        try {
            return new Domain(NetBirdJson.string(object, "id", false),
                    NetBirdJson.string(object, "domain", true),
                    NetBirdJson.string(object, "type", false),
                    NetBirdJson.string(object, "target_cluster", false),
                    NetBirdJson.bool(object, "validated", false),
                    NetBirdJson.bool(object, "supports_custom_ports", false),
                    NetBirdJson.bool(object, "require_subdomain", false));
        } catch (RuntimeException invalid) { throw malformed(invalid); }
    }

    private static ProxyCluster parseCluster(Map<String, Object> object) throws NetBirdApiException {
        try {
            return new ProxyCluster(NetBirdJson.string(object, "id", true),
                    NetBirdJson.string(object, "address", true),
                    NetBirdJson.string(object, "type", true),
                    NetBirdJson.bool(object, "online", false),
                    NetBirdJson.integer(object, "connected_proxies", false, 0),
                    NetBirdJson.bool(object, "supports_custom_ports", false),
                    NetBirdJson.bool(object, "require_subdomain", false));
        } catch (RuntimeException invalid) { throw malformed(invalid); }
    }

    private static Service parseService(Map<String, Object> object) throws NetBirdApiException {
        try {
            String modeValue = NetBirdJson.string(object, "mode", false);
            Mode mode = modeValue == null ? null : Mode.parse(modeValue);
            int port = NetBirdJson.integer(object, "listen_port", false, 0);
            boolean autoAssigned = NetBirdJson.bool(object, "port_auto_assigned", false);
            String cluster = NetBirdJson.string(object, "proxy_cluster", false);
            List<Object> rawTargets = NetBirdJson.array(object.get("targets"));
            List<ServiceTarget> targets = new ArrayList<>();
            if (rawTargets != null) {
                for (Object rawTarget : rawTargets) targets.add(parseTarget(requireObject(rawTarget)));
            }
            Map<String, Object> meta = NetBirdJson.object(object.get("meta"));
            String status = meta == null ? null : NetBirdJson.string(meta, "status", false);
            return new Service(NetBirdJson.string(object, "id", true),
                    NetBirdJson.string(object, "name", true),
                    validDomain(NetBirdJson.string(object, "domain", true)),
                    mode, port, autoAssigned, cluster, targets,
                    NetBirdJson.bool(object, "enabled", false),
                    NetBirdJson.bool(object, "terminated", false), status);
        } catch (RuntimeException invalid) { throw malformed(invalid); }
    }

    private static ServiceTarget parseTarget(Map<String, Object> object) {
        Map<String, Object> options = NetBirdJson.object(object.get("options"));
        String idleTimeout = options == null ? null : NetBirdJson.string(options, "session_idle_timeout", false);
        return new ServiceTarget(NetBirdJson.string(object, "target_id", true),
                NetBirdJson.string(object, "target_type", true),
                NetBirdJson.string(object, "protocol", true),
                NetBirdJson.string(object, "host", false),
                NetBirdJson.string(object, "path", false),
                NetBirdJson.integer(object, "port", true, 0),
                NetBirdJson.bool(object, "enabled", false), idleTimeout);
    }

    private static NetBirdApiException malformed(RuntimeException cause) {
        return new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, 200, null, cause);
    }

    private static Map<String, Object> requireObject(Object value) throws NetBirdApiException {
        Map<String, Object> object = NetBirdJson.object(value);
        if (object == null) throw new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, 200);
        return object;
    }

    private static List<Object> requireArray(Object value) throws NetBirdApiException {
        List<Object> array = NetBirdJson.array(value);
        if (array == null) throw new NetBirdApiException(NetBirdApiException.Code.MALFORMED_RESPONSE, 200);
        return array;
    }

    private static String pathSegment(String value) {
        String safe = nonBlank(value, "serviceId");
        return URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String validDomain(String value) {
        String domain = nonBlank(value, "domain").trim().toLowerCase(Locale.ROOT);
        if (domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")
                || !domain.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("Invalid domain");
        }
        for (String label : domain.split("\\.")) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("Invalid domain");
            }
        }
        return domain;
    }

    static int validPort(int port, String label) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid " + label);
        return port;
    }

    static String nonBlank(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid " + label);
        return value.trim();
    }

    static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
