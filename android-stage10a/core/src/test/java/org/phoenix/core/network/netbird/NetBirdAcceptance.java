package org.phoenix.core.network.netbird;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.phoenix.core.network.netbird.NetBirdHttpTransport.Request;

/** Meaningful local-provider acceptance checks for the durable Services API integration. */
public final class NetBirdAcceptance {
    private static final URI BASE = URI.create("https://api.netbird.io");
    private static final String PAT = "test-personal-access-token";
    private static final String CLUSTER = "eu.proxy.netbird.io";
    private static final String OTHER_CLUSTER = "us.proxy.netbird.io";

    private static final class MemoryStore implements NetBirdProvisioningCoordinator.JournalStore {
        private String encoded = NetBirdProvisioningCoordinator.Snapshot.empty().encode();

        @Override public synchronized NetBirdProvisioningCoordinator.Snapshot load() {
            return NetBirdProvisioningCoordinator.Snapshot.decode(encoded);
        }

        @Override public synchronized boolean compareAndSet(long expectedRevision,
                NetBirdProvisioningCoordinator.Snapshot replacement) {
            NetBirdProvisioningCoordinator.Snapshot current = load();
            if (current.revision() != expectedRevision) return false;
            if (replacement.revision() != expectedRevision + 1L) throw new IllegalArgumentException("revision must advance once");
            encoded = replacement.encode();
            return true;
        }
    }

    private static final class FakeNetBird implements NetBirdHttpTransport {
        private final Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        private final List<Request> requests = new ArrayList<>();
        private int tcpCreates;
        private int udpCreates;
        private int listCalls;
        private int getCalls;
        private int updateCalls;
        private int deleteCalls;
        private int nextId = 1;
        private boolean loseTcpPostResponseAfterCreate;
        private boolean loseTcpPostResponseBeforeCreate;
        private boolean rateLimitNextTcpPost;
        private int tcpPostRateLimitsRemaining;
        private String retryAfterForTcpPost = "999999";
        private boolean putUdpOnOtherCluster;
        private boolean rateLimitNextServiceList;
        private String retryAfterForServiceList = "90";
        private long publicPort = 32100;

        @Override public synchronized Response execute(Request request) throws IOException {
            requests.add(request);
            String auth = request.headers().get("Authorization");
            check(auth != null && (auth.equals("Token " + PAT) || auth.equals("Bearer oauth-test-token")),
                    "API uses an official Authorization scheme");
            check("application/json".equals(request.headers().get("Accept")), "API requests accept JSON");
            String path = request.uri().getPath();
            if (request.method().equals("GET") && path.equals("/api/reverse-proxies/domains")) {
                // Management v0.80 lists each shared cluster address as a free domain: empty id, no target_cluster.
                return json(200, "[{\"id\":\"\",\"domain\":\"" + CLUSTER + "\",\"validated\":true,\"type\":\"free\",\"supports_custom_ports\":true,\"require_subdomain\":true}]");
            }
            if (request.method().equals("GET") && path.equals("/api/reverse-proxies/clusters")) {
                return json(200, "[{\"id\":\"cluster-eu\",\"address\":\"" + CLUSTER + "\",\"type\":\"shared\",\"online\":true,\"connected_proxies\":2,\"supports_custom_ports\":true,\"require_subdomain\":false}]");
            }
            if (request.method().equals("GET") && path.equals("/api/reverse-proxies/services")) {
                listCalls++;
                if (rateLimitNextServiceList) {
                    rateLimitNextServiceList = false;
                    return new Response(429, "provider response text is not retained", Map.of("Retry-After", retryAfterForServiceList));
                }
                return json(200, jsonArray(new ArrayList<>(services.values())));
            }
            if (request.method().equals("GET") && path.startsWith("/api/reverse-proxies/services/")) {
                getCalls++;
                String id = path.substring(path.lastIndexOf('/') + 1);
                Map<String, Object> service = services.get(id);
                return service == null ? json(404, "{}") : json(200, jsonObject(service));
            }
            if (request.method().equals("POST") && path.equals("/api/reverse-proxies/services")) {
                check(request.headers().containsKey("Content-Type"), "create sends JSON content type");
                Map<String, Object> body = object(NetBirdJson.parse(request.body()));
                String mode = string(body, "mode");
                if (mode.equals("tcp")) tcpCreates++; else if (mode.equals("udp")) udpCreates++;
                else throw new AssertionError("unexpected service mode");
                if (mode.equals("tcp") && (rateLimitNextTcpPost || tcpPostRateLimitsRemaining > 0)) {
                    if (rateLimitNextTcpPost) rateLimitNextTcpPost = false;
                    else tcpPostRateLimitsRemaining--;
                    return new Response(429, "private rate-limit body", Map.of("Retry-After", retryAfterForTcpPost));
                }
                if (mode.equals("tcp") && loseTcpPostResponseBeforeCreate) {
                    loseTcpPostResponseBeforeCreate = false;
                    throw new IOException("simulated lost response before provider committed create");
                }
                Map<String, Object> created = serviceFromRequest("service-" + nextId++, body, mode);
                services.put((String) created.get("id"), created);
                if (mode.equals("tcp") && loseTcpPostResponseAfterCreate) {
                    loseTcpPostResponseAfterCreate = false;
                    throw new IOException("simulated lost response after server committed create");
                }
                return json(201, jsonObject(created));
            }
            if (request.method().equals("PUT") && path.startsWith("/api/reverse-proxies/services/")) {
                updateCalls++;
                String id = path.substring(path.lastIndexOf('/') + 1);
                Map<String, Object> current = services.get(id);
                if (current == null) return json(404, "{}");
                Map<String, Object> body = object(NetBirdJson.parse(request.body()));
                Map<String, Object> updated = serviceFromRequest(id, body, string(body, "mode"));
                updated.put("listen_port", current.get("listen_port"));
                updated.put("proxy_cluster", current.get("proxy_cluster"));
                services.put(id, updated);
                return json(200, jsonObject(updated));
            }
            if (request.method().equals("DELETE") && path.startsWith("/api/reverse-proxies/services/")) {
                deleteCalls++;
                String id = path.substring(path.lastIndexOf('/') + 1);
                services.remove(id);
                return new Response(204, "");
            }
            return json(404, "{}");
        }

        private Map<String, Object> serviceFromRequest(String id, Map<String, Object> body, String mode) {
            List<Object> targets = array(body.get("targets"));
            String cluster = mode.equals("udp") && putUdpOnOtherCluster ? OTHER_CLUSTER : CLUSTER;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("name", string(body, "name"));
            result.put("domain", string(body, "domain"));
            result.put("mode", mode);
            result.put("listen_port", (int) publicPort);
            result.put("port_auto_assigned", true);
            result.put("proxy_cluster", cluster);
            result.put("targets", targets);
            result.put("enabled", true);
            result.put("terminated", false);
            result.put("meta", Map.of("status", "active"));
            return result;
        }

        private Response json(int status, String body) { return new Response(status, body); }
    }

    public static void main(String[] args) throws Exception {
        serviceSpecsRejectNonL4Modes();
        serviceApiContractAndAuthentication();
        durableAllocationsSurviveCoordinatorRecreation();
        lostCreateResponseIsReconciledByNameWithoutDuplicatePost();
        uncertainCreateWithoutMatchingServiceFailsClosed();
        lostSavedServiceIdFailsClosed();
        rateLimitBackoffIsPersistedAndBounded();
        localExponentialBackoffIsBounded();
        httpDateRetryAfterIsPreserved();
        clusterMismatchCannotBecomeReadyOrCauseReallocation();
        concurrentDoubleTapNeverDuplicatesAServicePost();
        repeatedAppRestartsFromPersistedJournalNeverReallocate();
        corruptJournalFailsClosedWithoutApiCalls();
        System.out.println("NETBIRD_ACCEPTANCE PASS: API contract/auth, persistent TCP+UDP allocation, deterministic reconciliation, fail-closed recovery, capped local backoff, provider Retry-After preservation, and same-cluster checks");
    }

    private static void serviceSpecsRejectNonL4Modes() {
        for (NetBirdServicesClient.Mode mode : List.of(NetBirdServicesClient.Mode.HTTP,
                NetBirdServicesClient.Mode.TLS, NetBirdServicesClient.Mode.OTHER)) {
            try {
                NetBirdServicesClient.ServiceSpec.minecraft("unsupported", "unsupported.example.netbird.app",
                        mode, "peer-android-1", 25565);
                throw new AssertionError("Expected non-L4 service mode to be rejected: " + mode);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("Only TCP and UDP"),
                        "ServiceSpec clearly rejects non-L4 mode " + mode);
            }
        }
        check(NetBirdServicesClient.ServiceSpec.minecraft("tcp", "tcp.example.netbird.app",
                NetBirdServicesClient.Mode.TCP, "peer-android-1", 25565).mode() == NetBirdServicesClient.Mode.TCP,
                "TCP remains a valid service mode");
        check(NetBirdServicesClient.ServiceSpec.minecraft("udp", "udp.example.netbird.app",
                NetBirdServicesClient.Mode.UDP, "peer-android-1", 19132).mode() == NetBirdServicesClient.Mode.UDP,
                "UDP remains a valid service mode");
    }

    private static void serviceApiContractAndAuthentication() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        NetBirdServicesClient client = client(plane, NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, () -> 1_700_000_000_000L);
        List<NetBirdServicesClient.Domain> domains = client.listDomains();
        List<NetBirdServicesClient.ProxyCluster> clusters = client.listClusters();
        check(domains.size() == 1 && domains.get(0).isFree(), "free NetBird Cloud service domain is discoverable");
        check(domains.get(0).domain().equals(CLUSTER) && domains.get(0).targetCluster() == null && domains.get(0).supportsCustomPorts(),
                "a free domain is its shared cluster's address and carries no target_cluster");
        check(clusters.size() == 1 && clusters.get(0).isNetBirdShared() && clusters.get(0).online(), "NetBird-hosted shared cluster is visible and online");

        NetBirdHttpTransport missingValidationResponse = request -> new NetBirdHttpTransport.Response(200,
                "[{\"id\":\"domain-unverified\",\"domain\":\"pending.example.netbird.app\",\"type\":\"free\",\"target_cluster\":\"" + CLUSTER + "\"}]");
        NetBirdServicesClient missingValidationClient = new NetBirdServicesClient(missingValidationResponse, BASE, PAT,
                NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN);
        check(!missingValidationClient.listDomains().get(0).validated(), "an omitted domain validation flag fails closed");

        NetBirdServicesClient.ServiceSpec spec = NetBirdServicesClient.ServiceSpec.minecraft(
                "phoenix-java-generic262", "java.abc123.eu.proxy.netbird.io",
                NetBirdServicesClient.Mode.TCP, "peer-android-1", 25565);
        NetBirdServicesClient.Service created = client.createService(spec);
        check(created.id().equals("service-1") && created.listenPort() == 32100, "create response returns durable ID and assigned public port");
        RequestFields fields = requestFields(plane.requests, "POST", "/api/reverse-proxies/services");
        Map<String, Object> body = object(NetBirdJson.parse(fields.body));
        check("tcp".equals(string(body, "mode")) && integer(body, "listen_port") == 0, "TCP create uses persistent L4 mode with auto-assigned port");
        Map<String, Object> target = object(array(body.get("targets")).get(0));
        check("peer-android-1".equals(string(target, "target_id"))
                && "peer".equals(string(target, "target_type"))
                && "tcp".equals(string(target, "protocol"))
                && integer(target, "port") == 25565,
                "TCP target routes to the enrolled peer's Java listener");

        client.getService(created.id());
        client.updateService(created.id(), spec);
        client.deleteService(created.id());
        check(plane.getCalls == 1 && plane.updateCalls == 1 && plane.deleteCalls == 1,
                "service GET, PUT, and DELETE API methods use the documented endpoints");
        for (Request request : plane.requests) check(request.headers().get("Authorization").equals("Token " + PAT), "PAT uses official Token scheme");
        check(!client.toString().contains(PAT) && !plane.requests.get(0).toString().contains(PAT), "credential is redacted from string representations");

        FakeNetBird oauthPlane = new FakeNetBird();
        NetBirdServicesClient oauthClient = new NetBirdServicesClient(oauthPlane, BASE, "oauth-test-token",
                NetBirdServicesClient.AuthScheme.OAUTH_BEARER);
        oauthClient.listDomains();
        check(oauthPlane.requests.get(0).headers().get("Authorization").equals("Bearer oauth-test-token"), "OAuth access tokens use official Bearer scheme");

        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        FakeNetBird limited = new FakeNetBird();
        limited.rateLimitNextServiceList = true;
        NetBirdServicesClient limitedClient = client(limited, NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, clock::get);
        try {
            limitedClient.listServices();
            throw new AssertionError("Expected API rate limit");
        } catch (NetBirdApiException error) {
            check(error.code() == NetBirdApiException.Code.RATE_LIMITED, "429 is classified safely");
            check(error.retryAfterEpochMillis() == clock.get() + 90_000L, "Retry-After delta is parsed to a deadline");
            check(!error.getMessage().contains("provider response text is not retained"), "response body never reaches errors");
        }

        AtomicLong dateClock = new AtomicLong(1_700_000_000_000L);
        long dateDeadline = ((dateClock.get() + 2 * 60 * 60_000L) / 1000L) * 1000L;
        String retryAfterDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(dateDeadline), ZoneOffset.UTC));
        FakeNetBird dateLimited = new FakeNetBird();
        dateLimited.rateLimitNextServiceList = true;
        dateLimited.retryAfterForServiceList = retryAfterDate;
        NetBirdServicesClient dateLimitedClient = client(dateLimited,
                NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, dateClock::get);
        try {
            dateLimitedClient.listServices();
            throw new AssertionError("Expected HTTP-date rate limit");
        } catch (NetBirdApiException error) {
            check(error.retryAfterEpochMillis() == dateDeadline,
                    "HTTP-date Retry-After parses to the exact provider deadline");
            check(NetBirdApiException.parseRetryAfter(retryAfterDate, dateClock.get()) == dateDeadline,
                    "the public Retry-After parser is reusable by platform adapters");
        }
        long now = dateClock.get();
        long horizon = now + NetBirdApiException.MAX_RETRY_AFTER_MILLIS;
        check(NetBirdApiException.parseRetryAfter("99999999999999999999", now) == horizon,
                "an overflowing Retry-After is bounded to the 30-day horizon instead of locking provisioning forever");
        check(NetBirdApiException.parseRetryAfter(String.valueOf(Long.MAX_VALUE / 1000L), now) == horizon,
                "a multiply-overflowing Retry-After is bounded to the 30-day horizon");
        String farDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(now + 90L * 24 * 60 * 60_000L), ZoneOffset.UTC));
        check(NetBirdApiException.parseRetryAfter(farDate, now) == horizon,
                "an HTTP-date Retry-After beyond 30 days is bounded to the horizon");
    }

    private static void durableAllocationsSurviveCoordinatorRecreation() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        plane.services.put("existing-http", existingHttpService());
        plane.services.put("existing-future", existingUnknownModeService());
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        NetBirdProvisioningCoordinator first = coordinator(plane, store, () -> 1_700_000_000_000L);
        NetBirdProvisioningCoordinator.Result initial = first.provision(plan);
        check(initial.bothActive(), "both TCP and UDP persistent services become active");
        check(initial.peerId().equals(plan.peerId()) && initial.cluster().equals(CLUSTER), "allocation result carries the hosting peer and selected cluster");
        check(initial.tcp().publicPort() == 32100 && initial.udp().publicPort() == 32100, "TCP and UDP may share a numeric port on the same cluster");
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "one persistent TCP and one persistent UDP service were created");
        check(!plane.requests.stream().anyMatch(request -> request.uri().getPath().contains("/expose")), "production provisioning uses Services API rather than ephemeral expose");

        NetBirdProvisioningCoordinator.Snapshot durable = store.load();
        check(durable.phase() == NetBirdProvisioningCoordinator.Phase.READY, "allocation journal reaches READY only after both services are saved");
        check(durable.tcpServiceId().equals(initial.tcp().serviceId()) && durable.udpServiceId().equals(initial.udp().serviceId()), "service IDs are persisted");
        check(durable.tcpPublicPort() == initial.tcp().publicPort() && durable.udpPublicPort() == initial.udp().publicPort(), "public ports are persisted");
        check(durable.plan().peerId().equals(plan.peerId()) && durable.cluster().equals(CLUSTER), "peer identity and proxy cluster are persisted");
        NetBirdProvisioningCoordinator.Snapshot decoded = NetBirdProvisioningCoordinator.Snapshot.decode(durable.encode());
        check(decoded.revision() == durable.revision() && decoded.tcpServiceId().equals(durable.tcpServiceId()), "encrypted-store JSON codec round trips the allocation journal");
        check(!decoded.encode().contains(PAT), "API credential is not part of the allocation journal");
        NetBirdProvisioningCoordinator.Result savedOnly = decoded.savedResult();
        check(savedOnly.tcp().publicPort() == 32100 && !savedOnly.bothActive(), "durable endpoints are displayable without falsely asserting active status");

        NetBirdProvisioningCoordinator recreated = coordinator(plane, store, () -> 1_700_000_000_100L);
        NetBirdProvisioningCoordinator.Result afterRestart = recreated.provision(plan);
        check(afterRestart.tcp().serviceId().equals(initial.tcp().serviceId())
                && afterRestart.udp().serviceId().equals(initial.udp().serviceId()), "restart reuses unchanged service IDs");
        check(afterRestart.tcp().publicPort() == initial.tcp().publicPort()
                && afterRestart.udp().publicPort() == initial.udp().publicPort(), "restart reuses unchanged public ports");
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "restart does not provision duplicate services");
        check(plane.services.containsKey("existing-http"), "listing and reconciling L4 services tolerates unrelated HTTP services");
        check(plane.services.containsKey("existing-future"), "listing and reconciling L4 services tolerates unknown future service modes");
    }

    private static void repeatedAppRestartsFromPersistedJournalNeverReallocate() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        NetBirdProvisioningCoordinator.Result initial = coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan);
        String persisted = store.encoded;
        for (int restart = 1; restart <= 3; restart++) {
            // Process death: only the persisted journal text survives; store, client and coordinator are new.
            MemoryStore reopened = new MemoryStore();
            reopened.encoded = persisted;
            long now = 1_700_000_000_000L + restart * 86_400_000L;
            NetBirdProvisioningCoordinator.Result again = coordinator(plane, reopened, () -> now).provision(plan);
            check(again.tcp().serviceId().equals(initial.tcp().serviceId())
                    && again.udp().serviceId().equals(initial.udp().serviceId()), "every app restart reuses the same service IDs");
            check(again.tcp().publicPort() == initial.tcp().publicPort()
                    && again.udp().publicPort() == initial.udp().publicPort(), "every app restart reuses the same public ports");
            persisted = reopened.encoded;
        }
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "repeated app restarts never POST a new service");
        check(plane.updateCalls == 0 && plane.deleteCalls == 0, "repeated app restarts never mutate or delete services");
    }

    private static void corruptJournalFailsClosedWithoutApiCalls() throws Exception {
        for (String corrupt : List.of("", "{", "{\"revision\":1,\"phase\":\"READY\",\"plan\":null,\"pendingCreate\":\"NONE\"}",
                "{\"revision\":-1,\"phase\":\"EMPTY\",\"plan\":null,\"pendingCreate\":\"NONE\"}")) {
            FakeNetBird plane = new FakeNetBird();
            MemoryStore store = new MemoryStore();
            store.encoded = corrupt;
            try {
                coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan());
                throw new AssertionError("a corrupt allocation journal must not provision");
            } catch (NetBirdProvisioningCoordinator.ProvisioningException expected) {
                check(expected.code() == NetBirdProvisioningCoordinator.Code.PERSISTENCE_FAILURE, "a corrupt journal fails closed as a persistence failure");
            }
            check(plane.requests.isEmpty(), "a corrupt journal makes no NetBird API calls");
            check(store.encoded.equals(corrupt), "a corrupt journal is left untouched for recovery");
        }
    }

    private static void lostCreateResponseIsReconciledByNameWithoutDuplicatePost() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        plane.loseTcpPostResponseAfterCreate = true;
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        try {
            coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan);
            throw new AssertionError("Expected uncertain TCP create response");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.OUTCOME_UNKNOWN, "uncertain write is surfaced without provider secrets");
        }
        check(store.load().pendingCreate() == NetBirdProvisioningCoordinator.PendingCreate.TCP, "pending create marker is durable before POST");
        NetBirdProvisioningCoordinator.Result recovered = coordinator(plane, store, () -> 1_700_000_000_200L).provision(plan);
        check(recovered.tcp().serviceId().equals("service-1"), "GET services reconciles the committed TCP service by deterministic name/domain");
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "lost response recovery never repeats the uncertain TCP POST");
    }

    private static void lostSavedServiceIdFailsClosed() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        NetBirdProvisioningCoordinator.Result created = coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan);
        plane.services.remove(created.tcp().serviceId());
        try {
            coordinator(plane, store, () -> 1_700_000_000_100L).provision(plan);
            throw new AssertionError("Missing persisted service must fail closed");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.LOST_SERVICE_ID, "a lost saved service ID is not silently reallocated");
        }
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "lost-ID recovery creates no new services");
        check(store.load().tcpServiceId().equals(created.tcp().serviceId()), "journal retains the original allocation for recovery");
    }

    /**
     * Stage 9 double-tap regression: two provisioning attempts race on one journal (two coordinators, as two taps or two
     * processes would) while the first TCP POST is held open. The second attempt must not POST; once the first completes,
     * a later attempt reaches READY with exactly one TCP and one UDP service.
     */
    private static void concurrentDoubleTapNeverDuplicatesAServicePost() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        java.util.concurrent.CountDownLatch firstPostStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstPost = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean gated = new java.util.concurrent.atomic.AtomicBoolean();
        // Not synchronized: the second attempt runs its requests while the first POST is still in flight.
        NetBirdHttpTransport slowFirstPost = request -> {
            if (request.method().equals("POST") && gated.compareAndSet(false, true)) {
                firstPostStarted.countDown();
                try { releaseFirstPost.await(20, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
            return plane.execute(request);
        };
        java.util.function.LongSupplier clock = () -> 1_700_000_000_000L;
        java.util.concurrent.atomic.AtomicReference<Object> first = new java.util.concurrent.atomic.AtomicReference<>();
        Thread tapOne = new Thread(() -> {
            try { first.set(new NetBirdProvisioningCoordinator(new NetBirdServicesClient(slowFirstPost, BASE, PAT,
                    NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, clock), store, clock).provision(plan)); }
            catch (Exception e) { first.set(e); }
        });
        tapOne.start();
        check(firstPostStarted.await(20, java.util.concurrent.TimeUnit.SECONDS), "first tap reached its TCP POST");
        Object second;
        try { second = new NetBirdProvisioningCoordinator(new NetBirdServicesClient(slowFirstPost, BASE, PAT,
                NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, clock), store, clock).provision(plan); }
        catch (Exception e) { second = e; }
        check(plane.tcpCreates == 0 && plane.udpCreates == 0, "second tap made no POST while the first create was in flight");
        check(second instanceof Exception, "second tap fails closed instead of creating its own services");
        releaseFirstPost.countDown();
        tapOne.join(30_000);
        check(!tapOne.isAlive(), "first tap finished");
        check(plane.tcpCreates == 1, "exactly one TCP POST across both taps");
        NetBirdProvisioningCoordinator.Result settled = first.get() instanceof NetBirdProvisioningCoordinator.Result r ? r
                : coordinator(plane, store, () -> 1_700_000_000_500L).provision(plan);
        check(settled.bothActive(), "a later attempt (or the first tap) reaches READY");
        check(plane.tcpCreates == 1 && plane.udpCreates == 1, "double tap never duplicates a service: one TCP and one UDP POST in total");
        check(store.load().phase() == NetBirdProvisioningCoordinator.Phase.READY, "journal is READY with the single allocation");
    }

    private static void uncertainCreateWithoutMatchingServiceFailsClosed() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        plane.loseTcpPostResponseBeforeCreate = true;
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        try {
            coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan);
            throw new AssertionError("Expected uncertain TCP create response");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.OUTCOME_UNKNOWN, "pre-commit transport loss leaves a durable uncertain marker");
        }
        try {
            coordinator(plane, store, () -> 1_700_000_000_100L).provision(plan);
            throw new AssertionError("An absent deterministic match must not cause a repeated create");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.OUTCOME_UNKNOWN, "an empty reconciliation lookup remains blocked");
        }
        check(plane.tcpCreates == 1 && plane.udpCreates == 0, "unknown POST outcome is never blindly repeated or advanced to UDP");
    }

    private static void rateLimitBackoffIsPersistedAndBounded() throws Exception {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        FakeNetBird plane = new FakeNetBird();
        plane.rateLimitNextTcpPost = true;
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        NetBirdProvisioningCoordinator coordinator = coordinator(plane, store, clock::get);
        try {
            coordinator.provision(plan);
            throw new AssertionError("Expected provisioning rate limit");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.RATE_LIMITED, "create 429 maps to bounded retry state");
        }
        NetBirdProvisioningCoordinator.Snapshot limited = store.load();
        check(limited.pendingCreate() == NetBirdProvisioningCoordinator.PendingCreate.NONE, "a definitive 429 clears the pending write marker");
        long providerDeadline = clock.get() + 999_999_000L;
        check(limited.retryAfterEpochMillis() == providerDeadline,
                "provider Retry-After beyond the local cap remains authoritative");
        int listCallsBefore = plane.listCalls;
        try {
            coordinator.provision(plan);
            throw new AssertionError("Saved backoff must block early retries");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.RATE_LIMITED, "saved backoff survives a coordinator call");
        }
        check(plane.listCalls == listCallsBefore, "blocked retry sends no provider calls");
        clock.set(providerDeadline + 1);
        NetBirdProvisioningCoordinator.Result result = coordinator.provision(plan);
        check(result.bothActive() && plane.tcpCreates == 2 && plane.udpCreates == 1,
                "one explicit retry provisions after the provider deadline");
    }

    private static void localExponentialBackoffIsBounded() throws Exception {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        FakeNetBird plane = new FakeNetBird();
        plane.tcpPostRateLimitsRemaining = 8;
        plane.retryAfterForTcpPost = "invalid-provider-header";
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator coordinator = coordinator(plane, store, clock::get);
        long[] expectedDelays = {60_000L, 120_000L, 240_000L, 480_000L,
                900_000L, 900_000L, 900_000L, 900_000L};
        for (int attempt = 0; attempt < expectedDelays.length; attempt++) {
            try {
                coordinator.provision(plan());
                throw new AssertionError("Expected local retry backoff attempt " + (attempt + 1));
            } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
                check(error.code() == NetBirdProvisioningCoordinator.Code.RATE_LIMITED,
                        "invalid Retry-After falls back to local backoff");
            }
            long deadline = clock.get() + expectedDelays[attempt];
            check(store.load().retryAfterEpochMillis() == deadline,
                    "local exponential delay is bounded at attempt " + (attempt + 1));
            clock.set(deadline + 1L);
        }
        check(plane.tcpCreates == expectedDelays.length,
                "local exponential backoff caps at 15 minutes");
    }

    private static void httpDateRetryAfterIsPreserved() throws Exception {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        long providerDeadline = ((clock.get() + 3 * 60 * 60_000L) / 1000L) * 1000L;
        String retryAfterDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(providerDeadline), ZoneOffset.UTC));
        FakeNetBird plane = new FakeNetBird();
        plane.rateLimitNextTcpPost = true;
        plane.retryAfterForTcpPost = retryAfterDate;
        MemoryStore store = new MemoryStore();
        try {
            coordinator(plane, store, clock::get).provision(plan());
            throw new AssertionError("Expected HTTP-date provisioning rate limit");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.RATE_LIMITED,
                    "HTTP-date POST response remains a rate limit");
        }
        check(store.load().retryAfterEpochMillis() == providerDeadline,
                "coordinator preserves a long HTTP-date Retry-After deadline");
    }

    private static void clusterMismatchCannotBecomeReadyOrCauseReallocation() throws Exception {
        FakeNetBird plane = new FakeNetBird();
        plane.putUdpOnOtherCluster = true;
        MemoryStore store = new MemoryStore();
        NetBirdProvisioningCoordinator.Plan plan = plan();
        try {
            coordinator(plane, store, () -> 1_700_000_000_000L).provision(plan);
            throw new AssertionError("Different proxy clusters must fail");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.CLUSTER_MISMATCH, "split TCP/UDP clusters are blocked");
        }
        NetBirdProvisioningCoordinator.Snapshot snapshot = store.load();
        check(snapshot.phase() == NetBirdProvisioningCoordinator.Phase.PROVISIONING
                && snapshot.tcpServiceId() != null && snapshot.udpServiceId() != null,
                "mismatched successful allocations remain journaled for safe recovery");
        int creates = plane.tcpCreates + plane.udpCreates;
        try {
            coordinator(plane, store, () -> 1_700_000_000_100L).provision(plan);
            throw new AssertionError("Persisted cluster mismatch must remain blocked");
        } catch (NetBirdProvisioningCoordinator.ProvisioningException error) {
            check(error.code() == NetBirdProvisioningCoordinator.Code.CLUSTER_MISMATCH, "subsequent reconciliation preserves cluster blocker");
        }
        check(plane.tcpCreates + plane.udpCreates == creates, "cluster mismatch does not trigger replacement allocations");
    }

    private static NetBirdProvisioningCoordinator.Plan plan() {
        return new NetBirdProvisioningCoordinator.Plan("phoenix-generic-26.2", "peer-android-1",
                "phoenix-java-generic262", "java.abc123.eu.proxy.netbird.io", 25565,
                "phoenix-bedrock-generic262", "bedrock.abc123.eu.proxy.netbird.io", 19132,
                CLUSTER);
    }

    private static Map<String, Object> existingHttpService() {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", "existing-http");
        service.put("name", "existing-web-service");
        service.put("domain", "web.example.netbird.app");
        service.put("mode", "http");
        service.put("listen_port", 443);
        service.put("port_auto_assigned", false);
        service.put("proxy_cluster", CLUSTER);
        service.put("targets", List.of(Map.of("target_id", "peer-web", "target_type", "peer",
                "protocol", "https", "port", 443, "enabled", true)));
        service.put("enabled", true);
        service.put("terminated", false);
        service.put("meta", Map.of("status", "active"));
        return service;
    }

    private static Map<String, Object> existingUnknownModeService() {
        Map<String, Object> service = existingHttpService();
        service.put("id", "existing-future");
        service.put("name", "existing-future-service");
        service.put("domain", "future.example.netbird.app");
        service.put("mode", "future-mode");
        return service;
    }

    private static NetBirdServicesClient client(FakeNetBird plane, NetBirdServicesClient.AuthScheme scheme,
                                                 java.util.function.LongSupplier clock) {
        String token = scheme == NetBirdServicesClient.AuthScheme.OAUTH_BEARER ? "oauth-test-token" : PAT;
        return new NetBirdServicesClient(plane, BASE, token, scheme, clock);
    }

    private static NetBirdProvisioningCoordinator coordinator(FakeNetBird plane, MemoryStore store,
                                                               java.util.function.LongSupplier clock) {
        return new NetBirdProvisioningCoordinator(
                client(plane, NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN, clock), store, clock);
    }

    private static RequestFields requestFields(List<Request> requests, String method, String path) {
        for (Request request : requests) {
            if (request.method().equals(method) && request.uri().getPath().equals(path)) return new RequestFields(request.body());
        }
        throw new AssertionError("Request not found: " + method + " " + path);
    }

    private record RequestFields(String body) {}

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = NetBirdJson.object(value);
        if (result == null) throw new AssertionError("Expected JSON object");
        return result;
    }

    private static List<Object> array(Object value) {
        List<Object> result = NetBirdJson.array(value);
        if (result == null) throw new AssertionError("Expected JSON array");
        return result;
    }

    private static String string(Map<String, Object> object, String key) {
        return NetBirdJson.string(object, key, true);
    }

    private static int integer(Map<String, Object> object, String key) {
        return NetBirdJson.integer(object, key, true, 0);
    }

    private static String jsonArray(List<?> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(jsonValue(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String jsonObject(Map<?, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(NetBirdJson.quote(String.valueOf(entry.getKey()))).append(':').append(jsonValue(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return NetBirdJson.quote(text);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return jsonObject(map);
        if (value instanceof List<?> list) return jsonArray(list);
        throw new AssertionError("Unsupported JSON test value");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
