package org.phoenix.core.network.netbird;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Durable, restart-safe coordinator for one persistent TCP and one persistent UDP service.
 * The supplied JournalStore must durably and atomically compare-and-set each replacement.
 */
public final class NetBirdProvisioningCoordinator {
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 60_000L;
    private static final long MAX_RETRY_DELAY_MILLIS = 15 * 60_000L;

    public enum Phase { EMPTY, PROVISIONING, READY }
    public enum PendingCreate { NONE, TCP, UDP }

    public enum Code {
        IN_PROGRESS,
        INVALID_PLAN,
        PLAN_CHANGED,
        STORE_CONFLICT,
        PERSISTENCE_FAILURE,
        RATE_LIMITED,
        OUTCOME_UNKNOWN,
        LOST_SERVICE_ID,
        SERVICE_NAME_CONFLICT,
        SERVICE_MISMATCH,
        PEER_MISMATCH,
        CLUSTER_MISMATCH,
        PORT_CHANGED,
        SERVICE_TERMINATED,
        SERVICE_NOT_READY
    }

    public interface JournalStore {
        Snapshot load();
        /** Must persist replacement before returning true; false means the revision changed. */
        boolean compareAndSet(long expectedRevision, Snapshot replacement);
    }

    /** Immutable service intent. Names/domains must be deterministic and remain stable across app restarts. */
    public static final class Plan {
        private final String serverKey;
        private final String peerId;
        private final String tcpServiceName;
        private final String tcpServiceDomain;
        private final int tcpLocalPort;
        private final String udpServiceName;
        private final String udpServiceDomain;
        private final int udpLocalPort;
        private final String expectedCluster;

        public Plan(String serverKey, String peerId,
                    String tcpServiceName, String tcpServiceDomain, int tcpLocalPort,
                    String udpServiceName, String udpServiceDomain, int udpLocalPort,
                    String expectedCluster) {
            this.serverKey = NetBirdServicesClient.nonBlank(serverKey, "serverKey");
            this.peerId = NetBirdServicesClient.nonBlank(peerId, "peerId");
            this.tcpServiceName = NetBirdServicesClient.nonBlank(tcpServiceName, "tcpServiceName");
            this.tcpServiceDomain = NetBirdServicesClient.validDomain(tcpServiceDomain);
            this.tcpLocalPort = NetBirdServicesClient.validPort(tcpLocalPort, "tcpLocalPort");
            this.udpServiceName = NetBirdServicesClient.nonBlank(udpServiceName, "udpServiceName");
            this.udpServiceDomain = NetBirdServicesClient.validDomain(udpServiceDomain);
            this.udpLocalPort = NetBirdServicesClient.validPort(udpLocalPort, "udpLocalPort");
            this.expectedCluster = NetBirdServicesClient.nonBlank(expectedCluster, "expectedCluster").toLowerCase(Locale.ROOT);
            if (this.tcpServiceName.equals(this.udpServiceName)
                    || this.tcpServiceDomain.equals(this.udpServiceDomain)) {
                throw new IllegalArgumentException("TCP and UDP services need distinct deterministic names and domains");
            }
        }

        public String serverKey() { return serverKey; }
        public String peerId() { return peerId; }
        public String tcpServiceName() { return tcpServiceName; }
        public String tcpServiceDomain() { return tcpServiceDomain; }
        public int tcpLocalPort() { return tcpLocalPort; }
        public String udpServiceName() { return udpServiceName; }
        public String udpServiceDomain() { return udpServiceDomain; }
        public int udpLocalPort() { return udpLocalPort; }
        public String expectedCluster() { return expectedCluster; }

        public NetBirdServicesClient.ServiceSpec tcpSpec() {
            return NetBirdServicesClient.ServiceSpec.minecraft(tcpServiceName, tcpServiceDomain,
                    NetBirdServicesClient.Mode.TCP, peerId, tcpLocalPort);
        }

        public NetBirdServicesClient.ServiceSpec udpSpec() {
            return NetBirdServicesClient.ServiceSpec.minecraft(udpServiceName, udpServiceDomain,
                    NetBirdServicesClient.Mode.UDP, peerId, udpLocalPort);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Plan plan)) return false;
            return serverKey.equals(plan.serverKey) && peerId.equals(plan.peerId)
                    && tcpServiceName.equals(plan.tcpServiceName) && tcpServiceDomain.equals(plan.tcpServiceDomain)
                    && tcpLocalPort == plan.tcpLocalPort && udpServiceName.equals(plan.udpServiceName)
                    && udpServiceDomain.equals(plan.udpServiceDomain) && udpLocalPort == plan.udpLocalPort
                    && expectedCluster.equals(plan.expectedCluster);
        }

        @Override public int hashCode() {
            return Objects.hash(serverKey, peerId, tcpServiceName, tcpServiceDomain, tcpLocalPort,
                    udpServiceName, udpServiceDomain, udpLocalPort, expectedCluster);
        }

        private String json() {
            return "{\"serverKey\":" + NetBirdJson.quote(serverKey)
                    + ",\"peerId\":" + NetBirdJson.quote(peerId)
                    + ",\"tcpServiceName\":" + NetBirdJson.quote(tcpServiceName)
                    + ",\"tcpServiceDomain\":" + NetBirdJson.quote(tcpServiceDomain)
                    + ",\"tcpLocalPort\":" + tcpLocalPort
                    + ",\"udpServiceName\":" + NetBirdJson.quote(udpServiceName)
                    + ",\"udpServiceDomain\":" + NetBirdJson.quote(udpServiceDomain)
                    + ",\"udpLocalPort\":" + udpLocalPort
                    + ",\"expectedCluster\":" + NetBirdJson.quote(expectedCluster) + "}";
        }

        private static Plan fromJson(Object value) {
            var object = NetBirdJson.object(value);
            if (object == null) throw new IllegalArgumentException("Invalid saved plan");
            return new Plan(NetBirdJson.string(object, "serverKey", true),
                    NetBirdJson.string(object, "peerId", true),
                    NetBirdJson.string(object, "tcpServiceName", true),
                    NetBirdJson.string(object, "tcpServiceDomain", true),
                    NetBirdJson.integer(object, "tcpLocalPort", true, 0),
                    NetBirdJson.string(object, "udpServiceName", true),
                    NetBirdJson.string(object, "udpServiceDomain", true),
                    NetBirdJson.integer(object, "udpLocalPort", true, 0),
                    NetBirdJson.string(object, "expectedCluster", true));
        }
    }

    /** Durable allocation journal. It stores identifiers, ports, cluster, and peer only, never credentials. */
    public static final class Snapshot {
        private final long revision;
        private final Phase phase;
        private final Plan plan;
        private final PendingCreate pendingCreate;
        private final String tcpServiceId;
        private final int tcpPublicPort;
        private final String udpServiceId;
        private final int udpPublicPort;
        private final String cluster;
        private final Long retryAfterEpochMillis;
        private final int retryCount;
        private final String lastFailureCode;

        private Snapshot(long revision, Phase phase, Plan plan, PendingCreate pendingCreate,
                         String tcpServiceId, int tcpPublicPort, String udpServiceId, int udpPublicPort,
                         String cluster, Long retryAfterEpochMillis, int retryCount, String lastFailureCode) {
            if (revision < 0 || retryCount < 0) throw new IllegalArgumentException("Invalid journal revision");
            this.revision = revision;
            this.phase = Objects.requireNonNull(phase, "phase");
            this.plan = plan;
            this.pendingCreate = Objects.requireNonNull(pendingCreate, "pendingCreate");
            this.tcpServiceId = blankToNull(tcpServiceId);
            this.tcpPublicPort = optionalPort(tcpPublicPort, this.tcpServiceId);
            this.udpServiceId = blankToNull(udpServiceId);
            this.udpPublicPort = optionalPort(udpPublicPort, this.udpServiceId);
            this.cluster = blankToNull(cluster);
            this.retryAfterEpochMillis = retryAfterEpochMillis;
            this.retryCount = retryCount;
            this.lastFailureCode = blankToNull(lastFailureCode);
            if (phase == Phase.EMPTY && (plan != null || tcpServiceId != null || udpServiceId != null)) {
                throw new IllegalArgumentException("EMPTY journal cannot contain a plan or allocations");
            }
            if (phase != Phase.EMPTY && plan == null) throw new IllegalArgumentException("Provisioning journal requires a plan");
            if (phase == Phase.READY && (tcpServiceId == null || udpServiceId == null
                    || tcpPublicPort == 0 || udpPublicPort == 0 || cluster == null
                    || pendingCreate != PendingCreate.NONE)) {
                throw new IllegalArgumentException("READY journal requires both persistent allocations");
            }
            if (retryAfterEpochMillis != null && retryAfterEpochMillis < 0) throw new IllegalArgumentException("Invalid retry deadline");
        }

        public static Snapshot empty() {
            return new Snapshot(0L, Phase.EMPTY, null, PendingCreate.NONE, null, 0, null, 0, null, null, 0, null);
        }

        public long revision() { return revision; }
        public Phase phase() { return phase; }
        public Plan plan() { return plan; }
        public PendingCreate pendingCreate() { return pendingCreate; }
        public String tcpServiceId() { return tcpServiceId; }
        public int tcpPublicPort() { return tcpPublicPort; }
        public String udpServiceId() { return udpServiceId; }
        public int udpPublicPort() { return udpPublicPort; }
        public String cluster() { return cluster; }
        public Long retryAfterEpochMillis() { return retryAfterEpochMillis; }
        public int retryCount() { return retryCount; }
        public String lastFailureCode() { return lastFailureCode; }

        /** Returns the durable public endpoints with active=false because this value has not refreshed API status. */
        public Result savedResult() {
            if (phase != Phase.READY) throw new IllegalStateException("Both NetBird services are not durably allocated");
            return new Result(plan.peerId(), cluster,
                    new Allocation(tcpServiceId, tcpPublicPort, plan.tcpServiceName(), plan.tcpServiceDomain(), false),
                    new Allocation(udpServiceId, udpPublicPort, plan.udpServiceName(), plan.udpServiceDomain(), false));
        }

        /** JSON suitable for an app-private encrypted preference. Do not log this value. */
        public String encode() {
            return "{\"revision\":" + revision
                    + ",\"phase\":" + NetBirdJson.quote(phase.name())
                    + ",\"plan\":" + (plan == null ? "null" : plan.json())
                    + ",\"pendingCreate\":" + NetBirdJson.quote(pendingCreate.name())
                    + ",\"tcpServiceId\":" + nullableString(tcpServiceId)
                    + ",\"tcpPublicPort\":" + tcpPublicPort
                    + ",\"udpServiceId\":" + nullableString(udpServiceId)
                    + ",\"udpPublicPort\":" + udpPublicPort
                    + ",\"cluster\":" + nullableString(cluster)
                    + ",\"retryAfterEpochMillis\":" + (retryAfterEpochMillis == null ? "null" : retryAfterEpochMillis)
                    + ",\"retryCount\":" + retryCount
                    + ",\"lastFailureCode\":" + nullableString(lastFailureCode) + "}";
        }

        public static Snapshot decode(String encoded) {
            var object = NetBirdJson.object(NetBirdJson.parse(encoded));
            if (object == null) throw new IllegalArgumentException("Invalid NetBird journal");
            try {
                long revision = NetBirdJson.longInteger(object, "revision", true, 0);
                Phase phase = Phase.valueOf(NetBirdJson.string(object, "phase", true));
                Object rawPlan = object.get("plan");
                Plan plan = rawPlan == null ? null : Plan.fromJson(rawPlan);
                PendingCreate pending = PendingCreate.valueOf(NetBirdJson.string(object, "pendingCreate", true));
                String tcpId = NetBirdJson.string(object, "tcpServiceId", false);
                int tcpPort = NetBirdJson.integer(object, "tcpPublicPort", false, 0);
                String udpId = NetBirdJson.string(object, "udpServiceId", false);
                int udpPort = NetBirdJson.integer(object, "udpPublicPort", false, 0);
                String cluster = NetBirdJson.string(object, "cluster", false);
                int retry = NetBirdJson.integer(object, "retryCount", false, 0);
                Long retryAfter = null;
                Object rawRetry = object.get("retryAfterEpochMillis");
                if (rawRetry instanceof java.math.BigDecimal value) retryAfter = value.longValueExact();
                String lastFailure = NetBirdJson.string(object, "lastFailureCode", false);
                return new Snapshot(revision, phase, plan, pending, tcpId, tcpPort, udpId, udpPort,
                        cluster, retryAfter, retry, lastFailure);
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid NetBird journal"); }
        }

        private Snapshot next(Phase nextPhase, PendingCreate nextPending, String nextTcpId, int nextTcpPort,
                              String nextUdpId, int nextUdpPort, String nextCluster, Long nextRetryAfter,
                              int nextRetryCount, String nextFailure) {
            return new Snapshot(revision + 1L, nextPhase, plan, nextPending, nextTcpId, nextTcpPort,
                    nextUdpId, nextUdpPort, nextCluster, nextRetryAfter, nextRetryCount, nextFailure);
        }

        private static int optionalPort(int port, String id) {
            if (id == null && port == 0) return 0;
            return NetBirdServicesClient.validPort(port, "saved public port");
        }
    }

    public static final class Allocation {
        private final String serviceId;
        private final int publicPort;
        private final String name;
        private final String domain;
        private final boolean active;

        private Allocation(String serviceId, int publicPort, String name, String domain, boolean active) {
            this.serviceId = serviceId;
            this.publicPort = publicPort;
            this.name = name;
            this.domain = domain;
            this.active = active;
        }
        public String serviceId() { return serviceId; }
        public int publicPort() { return publicPort; }
        public String name() { return name; }
        public String domain() { return domain; }
        public boolean active() { return active; }
    }

    public static final class Result {
        private final String peerId;
        private final String cluster;
        private final Allocation tcp;
        private final Allocation udp;

        private Result(String peerId, String cluster, Allocation tcp, Allocation udp) {
            this.peerId = peerId;
            this.cluster = cluster;
            this.tcp = tcp;
            this.udp = udp;
        }
        public String peerId() { return peerId; }
        public String cluster() { return cluster; }
        public Allocation tcp() { return tcp; }
        public Allocation udp() { return udp; }
        public boolean bothActive() { return tcp.active() && udp.active(); }
    }

    public static final class ProvisioningException extends Exception {
        private final Code code;
        private final Long retryAfterEpochMillis;

        private ProvisioningException(Code code, Long retryAfterEpochMillis) {
            super(message(code, retryAfterEpochMillis));
            this.code = code;
            this.retryAfterEpochMillis = retryAfterEpochMillis;
        }
        public Code code() { return code; }
        public Long retryAfterEpochMillis() { return retryAfterEpochMillis; }

        private static String message(Code code, Long retryAfter) {
            return switch (code) {
                case IN_PROGRESS -> "NetBird provisioning is already in progress";
                case INVALID_PLAN -> "NetBird provisioning plan is invalid";
                case PLAN_CHANGED -> "Saved NetBird allocation belongs to a different peer or service plan";
                case STORE_CONFLICT -> "NetBird provisioning state changed concurrently";
                case PERSISTENCE_FAILURE -> "NetBird allocation state could not be saved safely";
                case RATE_LIMITED -> "NetBird provisioning is rate limited; retry after " + (retryAfter == null ? "the saved backoff" : retryAfter);
                case OUTCOME_UNKNOWN -> "A NetBird service request may have completed; the deterministic service lookup did not produce a safe allocation, so no new service was created";
                case LOST_SERVICE_ID -> "A saved NetBird service ID no longer resolves; allocation is blocked to preserve its public endpoint";
                case SERVICE_NAME_CONFLICT -> "A deterministic NetBird service name or domain is already used by a different service";
                case SERVICE_MISMATCH -> "A saved NetBird service no longer matches its recorded protocol, peer, or local listener";
                case PEER_MISMATCH -> "A NetBird service targets a different peer than the saved hosting peer";
                case CLUSTER_MISMATCH -> "The TCP and UDP services are not on the selected NetBird proxy cluster";
                case PORT_CHANGED -> "NetBird changed a saved public port; the existing allocation was left untouched";
                case SERVICE_TERMINATED -> "NetBird has terminated the saved service";
                case SERVICE_NOT_READY -> "NetBird service allocation is saved but is not active yet";
            };
        }
    }

    private final NetBirdServicesClient client;
    private final JournalStore store;
    private final LongSupplier epochMillis;

    public NetBirdProvisioningCoordinator(NetBirdServicesClient client, JournalStore store) {
        this(client, store, System::currentTimeMillis);
    }

    public NetBirdProvisioningCoordinator(NetBirdServicesClient client, JournalStore store, LongSupplier epochMillis) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    /**
     * Reuses valid saved services. For an interrupted POST, it lists by deterministic name/domain and adopts
     * a matching response. It never blindly repeats a POST whose outcome may be unknown.
     */
    public Result provision(Plan requestedPlan) throws NetBirdApiException, ProvisioningException {
        Objects.requireNonNull(requestedPlan, "requestedPlan");
        Snapshot snapshot = load();
        if (snapshot.phase() == Phase.EMPTY) {
            snapshot = persist(snapshot, Phase.PROVISIONING, PendingCreate.NONE,
                    null, 0, null, 0, null, null, 0, null, requestedPlan);
        } else if (!snapshot.plan().equals(requestedPlan)) {
            throw failure(Code.PLAN_CHANGED);
        }

        if (snapshot.retryAfterEpochMillis() != null && snapshot.retryAfterEpochMillis() > epochMillis.getAsLong()) {
            throw new ProvisioningException(Code.RATE_LIMITED, snapshot.retryAfterEpochMillis());
        }
        if (snapshot.phase() == Phase.READY) return verifyReady(snapshot);

        List<NetBirdServicesClient.Service> services;
        try {
            services = client.listServices();
        } catch (NetBirdApiException error) {
            if (error.code() == NetBirdApiException.Code.RATE_LIMITED) {
                Snapshot limited = recordRateLimit(snapshot, error);
                throw new ProvisioningException(Code.RATE_LIMITED, limited.retryAfterEpochMillis());
            }
            throw error;
        }

        // Reconcile both deterministic names before the first possible create. This adopts successful
        // responses lost between NetBird and the durable journal and catches conflicts early.
        NetBirdServicesClient.Service tcp = findByDeterministicKey(services, requestedPlan.tcpServiceName(), requestedPlan.tcpServiceDomain());
        NetBirdServicesClient.Service udp = findByDeterministicKey(services, requestedPlan.udpServiceName(), requestedPlan.udpServiceDomain());

        snapshot = resolveRecorded(snapshot, PendingCreate.TCP, tcp);
        snapshot = resolveRecorded(snapshot, PendingCreate.UDP, udp);
        if (snapshot.tcpServiceId() == null && tcp != null) snapshot = saveDiscovered(snapshot, PendingCreate.TCP, tcp);
        if (snapshot.udpServiceId() == null && udp != null) snapshot = saveDiscovered(snapshot, PendingCreate.UDP, udp);

        if (snapshot.pendingCreate() == PendingCreate.TCP && snapshot.tcpServiceId() == null && tcp == null) {
            throw failure(Code.OUTCOME_UNKNOWN);
        }
        if (snapshot.pendingCreate() == PendingCreate.UDP && snapshot.udpServiceId() == null && udp == null) {
            throw failure(Code.OUTCOME_UNKNOWN);
        }

        if (snapshot.tcpServiceId() == null) {
            snapshot = create(snapshot, PendingCreate.TCP, requestedPlan.tcpSpec());
        } else {
            tcp = serviceBySavedId(snapshot, PendingCreate.TCP, services);
        }
        if (snapshot.udpServiceId() == null) {
            snapshot = create(snapshot, PendingCreate.UDP, requestedPlan.udpSpec());
        } else {
            udp = serviceBySavedId(snapshot, PendingCreate.UDP, services);
        }

        // Get the durable IDs from the API one final time so the returned active flags are current.
        tcp = getSavedService(snapshot, PendingCreate.TCP);
        udp = getSavedService(snapshot, PendingCreate.UDP);
        validateService(requestedPlan, PendingCreate.TCP, tcp, snapshot.tcpPublicPort());
        validateService(requestedPlan, PendingCreate.UDP, udp, snapshot.udpPublicPort());
        validateSameCluster(requestedPlan, tcp, udp);
        snapshot = persist(snapshot, Phase.READY, PendingCreate.NONE,
                snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                requestedPlan.expectedCluster(), null, 0, null, null);
        return result(requestedPlan, snapshot, tcp, udp);
    }

    private Snapshot create(Snapshot snapshot, PendingCreate role, NetBirdServicesClient.ServiceSpec spec)
            throws NetBirdApiException, ProvisioningException {
        snapshot = persist(snapshot, Phase.PROVISIONING, role,
                snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                snapshot.cluster(), null, snapshot.retryCount(), null, null);
        NetBirdServicesClient.Service created;
        try {
            created = client.createService(spec);
        } catch (NetBirdApiException error) {
            if (error.code() == NetBirdApiException.Code.RATE_LIMITED) {
                snapshot = clearPendingForRateLimit(snapshot, error);
                throw new ProvisioningException(Code.RATE_LIMITED, snapshot.retryAfterEpochMillis());
            }
            if (error.outcomeMayHaveOccurred()) throw failure(Code.OUTCOME_UNKNOWN);
            persist(snapshot, Phase.PROVISIONING, PendingCreate.NONE,
                    snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                    snapshot.cluster(), null, snapshot.retryCount(), error.code().name(), null);
            throw error;
        }
        snapshot = saveDiscovered(snapshot, role, created);
        validateService(snapshot.plan(), role, created,
                role == PendingCreate.TCP ? snapshot.tcpPublicPort() : snapshot.udpPublicPort());
        return snapshot;
    }

    private Snapshot clearPendingForRateLimit(Snapshot snapshot, NetBirdApiException error) throws ProvisioningException {
        long deadline = boundedRetryDeadline(snapshot.retryCount(), error.retryAfterEpochMillis());
        return persist(snapshot, Phase.PROVISIONING, PendingCreate.NONE,
                snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                snapshot.cluster(), deadline, snapshot.retryCount() + 1, Code.RATE_LIMITED.name(), null);
    }

    private Snapshot resolveRecorded(Snapshot snapshot, PendingCreate role, NetBirdServicesClient.Service listed)
            throws NetBirdApiException, ProvisioningException {
        String serviceId = serviceId(snapshot, role);
        if (serviceId == null) return snapshot;
        NetBirdServicesClient.Service service = listed != null && serviceId.equals(listed.id())
                ? listed : getSavedService(snapshot, role);
        validateService(snapshot.plan(), role, service, publicPort(snapshot, role));
        return snapshot;
    }

    private NetBirdServicesClient.Service serviceBySavedId(Snapshot snapshot, PendingCreate role,
                                                            List<NetBirdServicesClient.Service> listed)
            throws NetBirdApiException, ProvisioningException {
        String serviceId = serviceId(snapshot, role);
        for (NetBirdServicesClient.Service service : listed) {
            if (service.id().equals(serviceId)) return service;
        }
        return getSavedService(snapshot, role);
    }

    private Snapshot saveDiscovered(Snapshot snapshot, PendingCreate role, NetBirdServicesClient.Service service)
            throws ProvisioningException {
        int publicPort = service.listenPort();
        if (publicPort < 1 || publicPort > 65535 || service.id() == null || service.id().isBlank()) {
            throw failure(Code.OUTCOME_UNKNOWN);
        }
        String tcpId = snapshot.tcpServiceId();
        int tcpPort = snapshot.tcpPublicPort();
        String udpId = snapshot.udpServiceId();
        int udpPort = snapshot.udpPublicPort();
        if (role == PendingCreate.TCP) { tcpId = service.id(); tcpPort = publicPort; }
        else { udpId = service.id(); udpPort = publicPort; }
        String cluster = snapshot.cluster() == null ? service.proxyCluster() : snapshot.cluster();
        PendingCreate remainingPending = snapshot.pendingCreate() == role
                ? PendingCreate.NONE : snapshot.pendingCreate();
        Snapshot saved = persist(snapshot, Phase.PROVISIONING, remainingPending,
                tcpId, tcpPort, udpId, udpPort, cluster, null, 0, null, null);
        return saved;
    }

    private Result verifyReady(Snapshot snapshot) throws NetBirdApiException, ProvisioningException {
        if (snapshot.tcpServiceId() == null || snapshot.udpServiceId() == null
                || snapshot.tcpPublicPort() == 0 || snapshot.udpPublicPort() == 0) {
            throw failure(Code.LOST_SERVICE_ID);
        }
        NetBirdServicesClient.Service tcp = getSavedService(snapshot, PendingCreate.TCP);
        NetBirdServicesClient.Service udp = getSavedService(snapshot, PendingCreate.UDP);
        validateService(snapshot.plan(), PendingCreate.TCP, tcp, snapshot.tcpPublicPort());
        validateService(snapshot.plan(), PendingCreate.UDP, udp, snapshot.udpPublicPort());
        validateSameCluster(snapshot.plan(), tcp, udp);
        if (!snapshot.cluster().equalsIgnoreCase(tcp.proxyCluster())
                || !snapshot.cluster().equalsIgnoreCase(udp.proxyCluster())) throw failure(Code.CLUSTER_MISMATCH);
        if (snapshot.retryAfterEpochMillis() != null || snapshot.lastFailureCode() != null) {
            snapshot = persist(snapshot, Phase.READY, PendingCreate.NONE,
                    snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                    snapshot.cluster(), null, 0, null, null);
        }
        return result(snapshot.plan(), snapshot, tcp, udp);
    }

    private NetBirdServicesClient.Service getSavedService(Snapshot snapshot, PendingCreate role)
            throws NetBirdApiException, ProvisioningException {
        String serviceId = serviceId(snapshot, role);
        if (serviceId == null) throw failure(Code.LOST_SERVICE_ID);
        try { return client.getService(serviceId); }
        catch (NetBirdApiException error) {
            if (error.code() == NetBirdApiException.Code.NOT_FOUND) throw failure(Code.LOST_SERVICE_ID);
            if (error.code() == NetBirdApiException.Code.RATE_LIMITED) {
                Snapshot limited = recordRateLimit(snapshot, error);
                throw new ProvisioningException(Code.RATE_LIMITED, limited.retryAfterEpochMillis());
            }
            throw error;
        }
    }

    private void validateService(Plan plan, PendingCreate role, NetBirdServicesClient.Service service,
                                 int expectedPublicPort) throws ProvisioningException {
        if (service.terminated()) throw failure(Code.SERVICE_TERMINATED);
        String expectedName = role == PendingCreate.TCP ? plan.tcpServiceName() : plan.udpServiceName();
        String expectedDomain = role == PendingCreate.TCP ? plan.tcpServiceDomain() : plan.udpServiceDomain();
        int localPort = role == PendingCreate.TCP ? plan.tcpLocalPort() : plan.udpLocalPort();
        NetBirdServicesClient.Mode mode = role == PendingCreate.TCP
                ? NetBirdServicesClient.Mode.TCP : NetBirdServicesClient.Mode.UDP;
        if (!service.name().equals(expectedName) || !service.domain().equalsIgnoreCase(expectedDomain)
                || service.mode() != mode || !service.enabled() || service.targets().size() != 1) {
            throw failure(Code.SERVICE_MISMATCH);
        }
        NetBirdServicesClient.ServiceTarget target = service.targets().get(0);
        if (!target.targetId().equals(plan.peerId()) || !"peer".equalsIgnoreCase(target.targetType())) {
            throw failure(Code.PEER_MISMATCH);
        }
        if (!target.enabled() || !mode.wireValue().equalsIgnoreCase(target.protocol()) || target.port() != localPort) {
            throw failure(Code.SERVICE_MISMATCH);
        }
        if (service.listenPort() < 1 || service.listenPort() > 65535) throw failure(Code.SERVICE_MISMATCH);
        if (expectedPublicPort > 0 && service.listenPort() != expectedPublicPort) throw failure(Code.PORT_CHANGED);
        if (service.proxyCluster() == null || !plan.expectedCluster().equalsIgnoreCase(service.proxyCluster())) {
            throw failure(Code.CLUSTER_MISMATCH);
        }
    }

    private void validateSameCluster(Plan plan, NetBirdServicesClient.Service tcp,
                                     NetBirdServicesClient.Service udp) throws ProvisioningException {
        if (tcp.proxyCluster() == null || udp.proxyCluster() == null
                || !tcp.proxyCluster().equalsIgnoreCase(udp.proxyCluster())
                || !plan.expectedCluster().equalsIgnoreCase(tcp.proxyCluster())) {
            throw failure(Code.CLUSTER_MISMATCH);
        }
    }

    private NetBirdServicesClient.Service findByDeterministicKey(List<NetBirdServicesClient.Service> services,
                                                                 String expectedName, String expectedDomain)
            throws ProvisioningException {
        List<NetBirdServicesClient.Service> matches = new ArrayList<>();
        for (NetBirdServicesClient.Service service : services) {
            boolean nameMatch = expectedName.equals(service.name());
            boolean domainMatch = expectedDomain.equalsIgnoreCase(service.domain());
            if (nameMatch || domainMatch) {
                if (!nameMatch || !domainMatch) throw failure(Code.SERVICE_NAME_CONFLICT);
                matches.add(service);
            }
        }
        if (matches.size() > 1) throw failure(Code.SERVICE_NAME_CONFLICT);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private Snapshot recordRateLimit(Snapshot snapshot, NetBirdApiException error) throws ProvisioningException {
        long deadline = boundedRetryDeadline(snapshot.retryCount(), error.retryAfterEpochMillis());
        return persist(snapshot, snapshot.phase(), snapshot.pendingCreate(),
                snapshot.tcpServiceId(), snapshot.tcpPublicPort(), snapshot.udpServiceId(), snapshot.udpPublicPort(),
                snapshot.cluster(), deadline, snapshot.retryCount() + 1, Code.RATE_LIMITED.name(), null);
    }

    private long boundedRetryDeadline(int priorCount, Long providerDeadline) {
        long now = epochMillis.getAsLong();
        if (providerDeadline != null) return Math.max(now, providerDeadline);
        long exponential = DEFAULT_RETRY_DELAY_MILLIS;
        for (int i = 0; i < Math.min(priorCount, 10); i++) exponential = Math.min(MAX_RETRY_DELAY_MILLIS, exponential * 2L);
        return now > Long.MAX_VALUE - exponential ? Long.MAX_VALUE : now + exponential;
    }

    private Snapshot load() throws ProvisioningException {
        try {
            Snapshot snapshot = store.load();
            if (snapshot == null) throw failure(Code.PERSISTENCE_FAILURE);
            return snapshot;
        } catch (ProvisioningException error) { throw error; }
        catch (RuntimeException error) { throw failure(Code.PERSISTENCE_FAILURE); }
    }

    private Snapshot persist(Snapshot old, Phase phase, PendingCreate pendingCreate,
                             String tcpId, int tcpPort, String udpId, int udpPort, String cluster,
                             Long retryAfter, int retryCount, String lastFailure, Plan replacementPlan)
            throws ProvisioningException {
        Plan plan = replacementPlan == null ? old.plan() : replacementPlan;
        Snapshot replacement;
        try {
            replacement = new Snapshot(old.revision() + 1L, phase, plan, pendingCreate,
                    tcpId, tcpPort, udpId, udpPort, cluster, retryAfter, retryCount, lastFailure);
            if (!store.compareAndSet(old.revision(), replacement)) throw failure(Code.STORE_CONFLICT);
            return replacement;
        } catch (ProvisioningException error) { throw error; }
        catch (RuntimeException error) { throw failure(Code.PERSISTENCE_FAILURE); }
    }

    private Result result(Plan plan, Snapshot snapshot, NetBirdServicesClient.Service tcp,
                          NetBirdServicesClient.Service udp) {
        return new Result(plan.peerId(), snapshot.cluster(),
                new Allocation(snapshot.tcpServiceId(), snapshot.tcpPublicPort(), plan.tcpServiceName(),
                        plan.tcpServiceDomain(), tcp.active()),
                new Allocation(snapshot.udpServiceId(), snapshot.udpPublicPort(), plan.udpServiceName(),
                        plan.udpServiceDomain(), udp.active()));
    }

    private static String serviceId(Snapshot snapshot, PendingCreate role) {
        return role == PendingCreate.TCP ? snapshot.tcpServiceId() : snapshot.udpServiceId();
    }

    private static int publicPort(Snapshot snapshot, PendingCreate role) {
        return role == PendingCreate.TCP ? snapshot.tcpPublicPort() : snapshot.udpPublicPort();
    }

    private ProvisioningException failure(Code code) { return new ProvisioningException(code, null); }

    private static String nullableString(String value) { return value == null ? "null" : NetBirdJson.quote(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
