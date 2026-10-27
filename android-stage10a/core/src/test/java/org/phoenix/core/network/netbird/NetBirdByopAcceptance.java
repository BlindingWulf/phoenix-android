package org.phoenix.core.network.netbird;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.phoenix.core.network.netbird.NetBirdHttpTransport.Request;
import org.phoenix.core.network.netbird.NetBirdHttpTransport.Response;

/** Local-only checks for Phoenix's Bring Your Own Proxy (US proxy) provisioning and its Oracle Cloud Shell setup. */
public final class NetBirdByopAcceptance {
    private static final URI BASE = URI.create("https://api.netbird.io");
    private static final String TOKEN = "oauth-test-token";
    private static final String APEX = "129-153-1-2.sslip.io";
    private static final String NEW_APEX = "150-136-9-8.sslip.io";
    private static final String PLAIN = "nbx_TESTONLYabcdefghijklmnopqrstuvwxyz0123456789";

    /** Minimal NetBird Cloud fake: services, account clusters, proxy tokens. */
    private static final class FakeApi implements NetBirdHttpTransport {
        final Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        final List<Request> requests = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();
        final List<String> deletedClusters = new ArrayList<>();
        String clustersJson = "[]";
        String tokenResponse = "{\"id\":\"tok-1\",\"name\":\"phoenix-us-k\",\"plain_token\":\"" + PLAIN + "\",\"revoked\":false}";
        boolean ignoreListenPort;
        String forceCluster;
        int nextId = 1;
        int creates, updates, deletes;

        @Override public synchronized Response execute(Request request) throws IOException {
            requests.add(request);
            check(("Bearer " + TOKEN).equals(request.headers().get("Authorization")), "requests carry the OAuth bearer credential");
            String path = request.uri().getRawPath();
            String method = request.method();
            if (method.equals("GET") && path.equals("/api/reverse-proxies/clusters")) return new Response(200, clustersJson);
            if (method.equals("POST") && path.equals("/api/reverse-proxies/proxy-tokens")) return new Response(200, tokenResponse);
            if (method.equals("GET") && path.equals("/api/reverse-proxies/proxy-tokens")) {
                return new Response(200, "[{\"id\":\"tok-1\",\"name\":\"phoenix-us-k\",\"revoked\":false,\"created_at\":\"2026-10-10T00:00:00Z\"}]");
            }
            if (method.equals("DELETE") && path.startsWith("/api/reverse-proxies/proxy-tokens/")) {
                revoked.add(path.substring(path.lastIndexOf('/') + 1));
                return new Response(204, "");
            }
            if (method.equals("DELETE") && path.startsWith("/api/reverse-proxies/clusters/")) {
                deletedClusters.add(path.substring(path.lastIndexOf('/') + 1));
                return new Response(204, "");
            }
            if (method.equals("GET") && path.equals("/api/reverse-proxies/services")) {
                StringBuilder out = new StringBuilder("[");
                for (Map<String, Object> service : services.values()) {
                    if (out.length() > 1) out.append(',');
                    out.append(json(service));
                }
                return new Response(200, out.append(']').toString());
            }
            if ((method.equals("POST") && path.equals("/api/reverse-proxies/services"))
                    || (method.equals("PUT") && path.startsWith("/api/reverse-proxies/services/"))) {
                Map<String, Object> body = NetBirdJson.object(NetBirdJson.parse(request.body()));
                String id = method.equals("POST") ? "svc-" + nextId++ : path.substring(path.lastIndexOf('/') + 1);
                if (method.equals("POST")) creates++; else updates++;
                String domain = (String) body.get("domain");
                Map<String, Object> service = new LinkedHashMap<>();
                service.put("id", id);
                service.put("name", body.get("name"));
                service.put("domain", domain);
                service.put("mode", body.get("mode"));
                int listen = ((Number) body.get("listen_port")).intValue();
                service.put("listen_port", ignoreListenPort ? 40000 : listen);
                service.put("port_auto_assigned", ignoreListenPort);
                service.put("proxy_cluster", forceCluster != null ? forceCluster : domain.substring(domain.indexOf('.') + 1));
                service.put("targets", body.get("targets"));
                service.put("enabled", true);
                service.put("meta", Map.of("status", "active"));
                services.put(id, service);
                return new Response(200, json(service));
            }
            if (method.equals("DELETE") && path.startsWith("/api/reverse-proxies/services/")) {
                deletes++;
                services.remove(path.substring(path.lastIndexOf('/') + 1));
                return new Response(204, "");
            }
            return new Response(404, "{}");
        }
    }

    public static void main(String[] args) throws Exception {
        proxyTokenApiContract();
        apexAddressing();
        clusterSelection();
        planUsesRealPortsAndDeterministicNames();
        reconcileCreatesThenIsIdempotentAndLeavesSharedServicesAlone();
        reconcileUpdatesMovesRetiresAndFailsClosed();
        pairingCodeRoundTrip();
        pairingProtocol();
        java.nio.file.Path template = java.nio.file.Path.of(args.length > 0 ? args[0] : "../deploy/oracle-proxy");
        oracleStackIsSafe(template);
        boolean python = pythonInterop(template);
        System.out.println("NETBIRD_BYOP_ACCEPTANCE PASS: proxy-token API, sslip apex, cluster selection, real-port plan, idempotent reconcile with migration, "
                + "fail-closed checks, pairing code and protocol (wrong code, unconfirmed token, forged report), Oracle stack safety"
                + (python ? ", Python VM client interop" : ""));
    }

    private static void proxyTokenApiContract() throws Exception {
        FakeApi api = new FakeApi();
        NetBirdServicesClient client = client(api);
        NetBirdServicesClient.CreatedProxyToken token = client.createProxyToken("phoenix-proxy-k", 0);
        Request create = api.requests.get(0);
        Map<String, Object> body = NetBirdJson.object(NetBirdJson.parse(create.body()));
        check(create.method().equals("POST") && create.uri().getPath().equals("/api/reverse-proxies/proxy-tokens"), "token create uses the documented route");
        check("phoenix-proxy-k".equals(body.get("name")) && ((Number) body.get("expires_in")).intValue() == 0, "token create sends name and expires_in");
        check(token.id().equals("tok-1") && token.plainToken().equals(PLAIN), "plain token and id are returned once");
        check(!token.toString().contains(PLAIN), "created token never prints its plain value");
        check(client.listProxyTokens().get(0).id().equals("tok-1"), "tokens list without plain values");
        client.revokeProxyToken("tok-1");
        check(api.revoked.equals(List.of("tok-1")), "revoke uses DELETE /proxy-tokens/{id}");
        client.deleteCluster(APEX);
        check(api.deletedClusters.equals(List.of(APEX)), "cluster delete uses the cluster address");
        api.tokenResponse = "{\"id\":\"tok-2\",\"name\":\"x\",\"plain_token\":\"bad token; rm -rf /\"}";
        try {
            client.createProxyToken("x", 0);
            throw new AssertionError("a token with shell metacharacters must be rejected");
        } catch (NetBirdApiException expected) {
            check(expected.code() == NetBirdApiException.Code.MALFORMED_RESPONSE, "unsafe token text is a malformed response");
        }
    }

    private static void apexAddressing() {
        check(NetBirdByop.apexFor("129.153.1.2").equals(APEX), "IPv4 maps to a dashed sslip.io apex");
        check("129.153.1.2".equals(NetBirdByop.ipv4Of(APEX)), "apex maps back to the VM address");
        check(NetBirdByop.ipv4Of("eu1.netbird.services") == null && NetBirdByop.ipv4Of("999-1-1-1.sslip.io") == null, "non-Phoenix and invalid apexes are rejected");
        expectIllegal(() -> NetBirdByop.apexFor("1.2.3"), "short IPv4");
        expectIllegal(() -> NetBirdByop.apexFor("1.2.3.256"), "octet over 255");
    }

    private static void clusterSelection() throws Exception {
        FakeApi api = new FakeApi();
        api.clustersJson = "[{\"id\":\"c0\",\"address\":\"eu1.netbird.services\",\"type\":\"shared\",\"online\":true,\"connected_proxies\":2},"
                + "{\"id\":\"c1\",\"address\":\"" + APEX + "\",\"type\":\"account\",\"online\":false,\"connected_proxies\":0},"
                + "{\"id\":\"c2\",\"address\":\"" + NEW_APEX + "\",\"type\":\"account\",\"online\":true,\"connected_proxies\":1,\"supports_custom_ports\":true}]";
        List<NetBirdServicesClient.ProxyCluster> clusters = client(api).listClusters();
        check(NetBirdByop.selectCluster(clusters, APEX).address().equals(NEW_APEX), "an online account cluster beats an offline preferred one");
        check(clusters.get(2).isAccount() && !clusters.get(0).isAccount(), "account and shared clusters are distinguished");
        api.clustersJson = "[{\"id\":\"c0\",\"address\":\"eu1.netbird.services\",\"type\":\"shared\",\"online\":true}]";
        check(NetBirdByop.selectCluster(client(api).listClusters(), null) == null, "shared clusters are never chosen as the US proxy");
    }

    private static void planUsesRealPortsAndDeterministicNames() {
        List<NetBirdServicesClient.ServiceSpec> plan = NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 24454));
        check(plan.size() == 3, "Java, Bedrock and voice are planned");
        check(plan.get(0).name().equals("Phoenix abc123 Regional Java") && plan.get(0).domain().equals("phoenix-abc123-java." + APEX)
                && plan.get(0).mode() == NetBirdServicesClient.Mode.TCP && plan.get(0).listenPort() == 25565, "Java listens on its real TCP port");
        check(plan.get(1).listenPort() == 19132 && plan.get(1).mode() == NetBirdServicesClient.Mode.UDP, "Bedrock listens on 19132 so players need no port");
        check(plan.get(2).listenPort() == 24454 && plan.get(2).target().port() == 24454, "voice chat UDP keeps its port end to end");
        check(NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 0)).size() == 2, "voice is omitted without Simple Voice Chat");
        expectIllegal(() -> new NetBirdByop.Ports(25565, 19132, 19132), "voice and Bedrock sharing a port");
        expectIllegal(() -> new NetBirdByop.Ports(25565, 51820, 0), "a game port on the WireGuard port");
        expectIllegal(() -> NetBirdByop.plan("abc123", "peer-1", "eu1.netbird.services", new NetBirdByop.Ports(25565, 19132, 0)), "planning onto a shared cluster");
    }

    private static void reconcileCreatesThenIsIdempotentAndLeavesSharedServicesAlone() throws Exception {
        FakeApi api = new FakeApi();
        Map<String, Object> eu = new LinkedHashMap<>();
        eu.put("id", "eu-java"); eu.put("name", "Phoenix abc123 Java"); eu.put("domain", "phoenix-abc123-java.eu1.netbird.services");
        eu.put("mode", "tcp"); eu.put("listen_port", 61000); eu.put("proxy_cluster", "eu1.netbird.services");
        eu.put("targets", List.of(Map.of("target_id", "peer-1", "target_type", "peer", "protocol", "tcp", "port", 25565, "enabled", true)));
        eu.put("enabled", true); eu.put("meta", Map.of("status", "active"));
        api.services.put("eu-java", eu);
        NetBirdServicesClient client = client(api);
        List<NetBirdServicesClient.ServiceSpec> plan = NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 24454));
        NetBirdByop.Outcome first = NetBirdByop.reconcile(client, "abc123", plan, APEX, true);
        check(first.created() == 3 && first.allActive(), "first run creates the three US services");
        NetBirdByop.Outcome second = NetBirdByop.reconcile(client, "abc123", plan, APEX, true);
        check(second.created() == 0 && second.updated() == 0 && second.moved() == 0 && api.creates == 3, "second run makes no writes");
        check(api.services.containsKey("eu-java") && api.updates == 0 && api.deletes == 0, "the shared-cluster (EU) service is untouched, so rollback stays available");
    }

    private static void reconcileUpdatesMovesRetiresAndFailsClosed() throws Exception {
        FakeApi api = new FakeApi();
        NetBirdServicesClient client = client(api);
        NetBirdByop.reconcile(client, "abc123", NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 24454)), APEX, true);

        NetBirdByop.Outcome changed = NetBirdByop.reconcile(client, "abc123",
                NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25566, 19132, 24454)), APEX, true);
        check(changed.updated() == 1 && changed.created() == 0, "a changed server port updates that service in place");

        NetBirdByop.Outcome moved = NetBirdByop.reconcile(client, "abc123",
                NetBirdByop.plan("abc123", "peer-1", NEW_APEX, new NetBirdByop.Ports(25566, 19132, 24454)), NEW_APEX, true);
        check(moved.moved() == 3 && moved.services().stream().allMatch(s -> NEW_APEX.equals(s.proxyCluster())), "a replaced VM's new address moves every service");

        NetBirdByop.Outcome retired = NetBirdByop.reconcile(client, "abc123",
                NetBirdByop.plan("abc123", "peer-1", NEW_APEX, new NetBirdByop.Ports(25566, 19132, 0)), NEW_APEX, true);
        check(retired.services().size() == 2 && api.services.values().stream().noneMatch(s -> String.valueOf(s.get("name")).endsWith("Regional Voice")),
                "removing voice chat retires its service");

        FakeApi wrongCluster = new FakeApi();
        wrongCluster.forceCluster = "eu1.netbird.services";
        expectState(() -> NetBirdByop.reconcile(client(wrongCluster), "abc123",
                NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 0)), APEX, true), "a service placed on another cluster");

        FakeApi noCustomPorts = new FakeApi();
        noCustomPorts.ignoreListenPort = true;
        expectState(() -> NetBirdByop.reconcile(client(noCustomPorts), "abc123",
                NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 0)), APEX, true), "a proxy that ignores the requested port");

        FakeApi duplicate = new FakeApi();
        for (String id : List.of("d1", "d2")) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("id", id); dup.put("name", "Phoenix abc123 Regional Java"); dup.put("domain", "phoenix-abc123-java." + APEX);
            dup.put("mode", "tcp"); dup.put("listen_port", 25565); dup.put("targets", List.of());
            duplicate.services.put(id, dup);
        }
        expectState(() -> NetBirdByop.reconcile(client(duplicate), "abc123",
                NetBirdByop.plan("abc123", "peer-1", APEX, new NetBirdByop.Ports(25565, 19132, 0)), APEX, true), "duplicate service names");
        check(duplicate.creates == 0 && duplicate.deletes == 0, "duplicates stop reconciliation before any write");
    }

    private static final byte[] SECRET = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String VM_INFO = "{\"region\":\"us-ashburn-1\",\"shape\":\"VM.Standard.E2.1.Micro\",\"arch\":\"x86_64\",\"ip\":\"129.153.1.2\",\"ad\":\"Xyz:US-ASHBURN-AD-1\"}";

    private static void pairingCodeRoundTrip() {
        NetBirdByop.Ports ports = new NetBirdByop.Ports(25565, 19132, 24454);
        ProxyPairing.Code code = new ProxyPairing.Code("eu1.netbird.services", 41234, ports, SECRET);
        String text = code.encode();
        check(text.startsWith("phx1:eu1.netbird.services:41234:25565:19132:24454:") && text.length() < 90, "the code is short and readable: " + text);
        ProxyPairing.Code parsed = ProxyPairing.Code.parse("  " + text + "\n");
        check(parsed.host().equals("eu1.netbird.services") && parsed.port() == 41234 && parsed.ports().voiceUdp() == 24454
                && Arrays.equals(parsed.secret(), SECRET), "a pasted code (with stray whitespace) parses back exactly");
        check(!code.toString().contains(text.substring(text.lastIndexOf(':') + 1)), "the code never prints its secret");
        check(ProxyPairing.Code.parse(new ProxyPairing.Code("eu1.netbird.services", 1, new NetBirdByop.Ports(25565, 19132, 0), SECRET).encode())
                .ports().forwards().size() == 2, "voice 0 means no voice forward");
        for (String bad : List.of("", "phx2:eu1.netbird.services:1:2:3:4:" + "A".repeat(26), "phx1:EU1:1:2:3:4:" + "A".repeat(26),
                "phx1:eu1.netbird.services:1:2:3:4:" + "A".repeat(25), "phx1:eu1.netbird.services:0:2:3:4:" + "A".repeat(26),
                "phx1:eu1.netbird.services:1:2:3:4:" + "A".repeat(26) + "';reboot;'", "phx1:a b:1:2:3:4:" + "A".repeat(26))) {
            expectIllegal(() -> ProxyPairing.Code.parse(bad), "pairing code " + bad);
        }
    }

    /** Runs the phone side on a server socket and returns what [vm] did; issued tokens and revocations are recorded. */
    private static final class PhoneSide implements AutoCloseable {
        final java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        final List<String> issued = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();
        volatile Object outcome;
        final Thread thread;

        PhoneSide(byte[] secret) throws IOException {
            thread = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(20000);
                    outcome = ProxyPairing.serve(socket.getInputStream(), socket.getOutputStream(), secret, vm -> {
                        issued.add(vm.ip());
                        return new ProxyPairing.Issued(PLAIN, "https://api.netbird.io", () -> revoked.add("tok"));
                    }, new java.security.SecureRandom());
                } catch (Exception failure) { outcome = failure; }
            });
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        Object await() throws InterruptedException { thread.join(30000); return outcome; }
        @Override public void close() throws IOException { server.close(); }
    }

    private static void pairingProtocol() throws Exception {
        try (PhoneSide phone = new PhoneSide(SECRET); java.net.Socket vm = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), phone.port())) {
            ProxyPairing.Received received = ProxyPairing.pair(vm.getInputStream(), vm.getOutputStream(), SECRET, VM_INFO, new java.security.SecureRandom());
            Object outcome = phone.await();
            check(PLAIN.equals(received.token()) && "https://api.netbird.io".equals(received.managementUrl()), "the VM receives the token and management address");
            check(outcome instanceof ProxyPairing.VmInfo && ((ProxyPairing.VmInfo) outcome).region().equals("us-ashburn-1")
                    && ((ProxyPairing.VmInfo) outcome).ip().equals("129.153.1.2"), "the phone learns the VM's region and public address");
            check(phone.issued.size() == 1 && phone.revoked.isEmpty(), "exactly one token is minted and kept");
            check(!received.toString().contains(PLAIN), "the received token never prints");
        }
        byte[] wrong = "fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        try (PhoneSide phone = new PhoneSide(SECRET); java.net.Socket vm = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), phone.port())) {
            try {
                ProxyPairing.pair(vm.getInputStream(), vm.getOutputStream(), wrong, VM_INFO, new java.security.SecureRandom());
                throw new AssertionError("a VM with the wrong code must not pair");
            } catch (ProxyPairing.PairingException expected) {
                check(expected.authentication(), "the VM detects a phone that does not hold its code");
            }
            vm.close();
            Object outcome = phone.await();
            check(phone.issued.isEmpty(), "no token is minted for a VM that never proved the code");
            check(outcome instanceof Exception, "the phone reports the failed attempt");
        }
        // A VM that proves the code but cannot store the token: the phone revokes it again.
        try (PhoneSide phone = new PhoneSide(SECRET); java.net.Socket vm = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), phone.port())) {
            java.io.InputStream in = vm.getInputStream();
            java.io.OutputStream out = vm.getOutputStream();
            // Let the reference VM run until it has the token, then answer with a failure instead of ok.
            java.io.OutputStream intercept = new java.io.OutputStream() {
                final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                @Override public void write(int b) throws IOException {
                    line.write(b);
                    if (b == '\n') {
                        String text = line.toString(StandardCharsets.UTF_8);
                        line.reset();
                        out.write((text.startsWith("{\"ok\":true}") ? "{\"ok\":false,\"error\":\"disk full\"}\n" : text).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            };
            ProxyPairing.pair(in, intercept, SECRET, VM_INFO, new java.security.SecureRandom());
            Object outcome = phone.await();
            check(outcome instanceof IOException && phone.issued.size() == 1 && phone.revoked.size() == 1, "an unconfirmed token is revoked");
        }
        // A forged VM report is refused before any token is minted.
        try (PhoneSide phone = new PhoneSide(SECRET); java.net.Socket vm = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), phone.port())) {
            try {
                ProxyPairing.pair(vm.getInputStream(), vm.getOutputStream(), SECRET, VM_INFO.replace("129.153.1.2", "not-an-ip"), new java.security.SecureRandom());
            } catch (IOException | ProxyPairing.PairingException expected) { /* the phone hangs up */ }
            phone.await();
            check(phone.issued.isEmpty(), "a VM without a public IPv4 address gets no token");
        }
    }

    /** The real VM client (phoenix_pair.py) against the real phone side, when Python with 'cryptography' is available. */
    private static boolean pythonInterop(java.nio.file.Path template) throws Exception {
        String python = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "python" : "python3";
        Process probe;
        try { probe = new ProcessBuilder(python, "-I", "-c", "import cryptography").redirectErrorStream(true).start(); }
        catch (IOException missing) { System.out.println("SKIP python interop: no " + python); return false; }
        if (probe.waitFor() != 0) { System.out.println("SKIP python interop: python 'cryptography' is not installed"); return false; }
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("phoenix-pair");
        // Windows drops quotes from process arguments, so the VM report goes through a file.
        String infoFile = java.nio.file.Files.writeString(java.nio.file.Files.createTempFile("phoenix-vm", ".json"), VM_INFO).toString();
        try (PhoneSide phone = new PhoneSide(SECRET)) {
            String code = new ProxyPairing.Code("localhost", phone.port(), new NetBirdByop.Ports(25565, 19132, 24454), SECRET).encode();
            Process vm = new ProcessBuilder(python, "-I", template.resolve("phoenix_pair.py").toString(), "--once", code, out.toString(), infoFile)
                    .redirectErrorStream(true).start();
            String output = new String(vm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(vm.waitFor() == 0, "phoenix_pair.py pairs with the phone: " + output);
            check(!output.contains(PLAIN), "the VM client never prints the token");
            Object outcome = phone.await();
            check(outcome instanceof ProxyPairing.VmInfo, "the phone accepts the Python VM: " + outcome);
            String env = java.nio.file.Files.readString(out.resolve("proxy.env"));
            check(env.contains("NB_PROXY_TOKEN=" + PLAIN + "\n") && env.contains("NB_PROXY_DOMAIN=" + APEX + "\n")
                    && env.contains("NB_PROXY_WG_PORT=51820") && env.contains("NB_PROXY_ADDRESS=127.0.0.1:8443")
                    && env.contains("NB_PROXY_HEALTH_ADDRESS=127.0.0.1:8080") && env.contains("NB_PROXY_MANAGEMENT_ADDRESS=https://api.netbird.io"),
                    "the VM writes the proxy configuration for its sslip.io apex");
        } finally {
            try (var files = java.nio.file.Files.list(out)) { files.forEach(f -> f.toFile().delete()); }
            out.toFile().delete();
        }
        new java.io.File(infoFile).deleteOnExit();
        // The wrong code: Python reports an authentication failure and the phone mints nothing.
        try (PhoneSide phone = new PhoneSide(SECRET)) {
            String code = new ProxyPairing.Code("localhost", phone.port(), new NetBirdByop.Ports(25565, 19132, 0),
                    "fedcba9876543210".getBytes(StandardCharsets.US_ASCII)).encode();
            Process vm = new ProcessBuilder(python, "-I", template.resolve("phoenix_pair.py").toString(), "--once", code,
                    System.getProperty("java.io.tmpdir"), infoFile).redirectErrorStream(true).start();
            String output = new String(vm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(vm.waitFor() != 0 && output.contains("AuthError"), "the Python VM refuses a phone without its code: " + output);
            phone.await();
            check(phone.issued.isEmpty(), "no token for a mismatched code");
        }
        return true;
    }

    /** Static checks on the Oracle Resource Manager stack shipped in deploy/oracle-proxy. */
    private static void oracleStackIsSafe(java.nio.file.Path template) throws Exception {
        String main = java.nio.file.Files.readString(template.resolve("main.tf"));
        String variables = java.nio.file.Files.readString(template.resolve("variables.tf"));
        String versions = java.nio.file.Files.readString(template.resolve("versions.tf"));
        String schema = java.nio.file.Files.readString(template.resolve("schema.yaml"));
        String bootstrap = java.nio.file.Files.readString(template.resolve("bootstrap.sh"));
        String pair = java.nio.file.Files.readString(template.resolve("phoenix_pair.py"));
        String all = main + variables + versions + schema + bootstrap;
        check(versions.contains("version = \"= 9.3.0\""), "the OCI provider is pinned to one version");
        check(bootstrap.contains("IMAGE=\"" + NetBirdByop.PROXY_IMAGE + "\""), "the VM runs the same digest-pinned proxy image Phoenix expects");
        check(!all.contains("NB_PROXY_TOKEN") && !all.toLowerCase(java.util.Locale.ROOT).contains("proxy_token")
                && !all.contains("api_key") && !all.contains("private_key"), "no token, API key or private key is a stack input or in user data");
        check(variables.contains("contains([\"VM.Standard.E2.1.Micro\", \"VM.Standard.A1.Flex\"], var.vm_shape)"), "only Always Free shapes can be chosen");
        check(main.contains("ocpus         = 1") && main.contains("memory_in_gbs = 6") && main.contains("boot_volume_size_in_gbs = 50"),
                "the VM stays inside the Always Free allowance");
        check(main.contains("var.region == local.home_region") && main.contains("local.micro_count < 2") && main.contains("local.storage_gb + 50 <= 200"),
                "home region, free VM count and free storage are enforced before anything is created");
        for (String forbidden : List.of("min = 22", "min = 80", "min = 443", "min = 8080", "min = 8443")) {
            check(!main.contains(forbidden), "no SSH, HTTP(S), proxy admin or health port is opened: " + forbidden);
        }
        check(main.contains("concat(local.udp_ports, [51820])") && main.contains("min = local.java_port"), "game ports from the pairing code plus WireGuard");
        check(!main.contains("oci_core_volume\" ") && !main.contains("oci_load_balancer") && !main.contains("oci_core_public_ip\""),
                "no block volumes, load balancers or reserved IPs (only Always Free resources)");
        check(schema.contains("pairing_code:\n    type: password"), "the pairing code is masked in Oracle's form");
        check(variables.contains("sensitive   = true"), "Terraform treats the pairing code as sensitive");
        check(!bootstrap.contains("set -x") && !pair.contains("print(token") && !pair.contains("print(payload"), "nothing traces or prints the token");
        check(bootstrap.contains("--dport 22 -j ACCEPT 2>/dev/null") && bootstrap.contains("hashlimit"), "host firewall drops SSH and rate-limits game ports");
        // Oracle limits user data to 32 KB; the stack base64-encodes two base64-encoded files plus a few lines.
        long estimate = Math.round(((pair.length() + bootstrap.length()) * 4.0 / 3 + 600) * 4.0 / 3);
        check(estimate < 32000, "instance user data fits Oracle's 32 KB limit (estimate " + estimate + ")");
        String code = new ProxyPairing.Code("eu1.netbird.services", 41234, new NetBirdByop.Ports(25565, 19132, 24454), SECRET).encode();
        String tfRegex = variables.substring(variables.indexOf("regex(\"") + 7, variables.indexOf("\", trimspace"));
        check(code.matches(tfRegex.replace("\\\\", "\\")), "Terraform accepts exactly the codes Phoenix makes");
        check(!("x" + code).matches(tfRegex.replace("\\\\", "\\")), "Terraform anchors the code pattern");
    }

    private static NetBirdServicesClient client(FakeApi api) {
        return new NetBirdServicesClient(api, BASE, TOKEN, NetBirdServicesClient.AuthScheme.OAUTH_BEARER);
    }

    private interface Action { void run() throws Exception; }

    private static void expectIllegal(Action action, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError("Expected IllegalArgumentException for " + what + ", got " + other);
        }
        throw new AssertionError("Expected rejection of " + what);
    }

    private static void expectState(Action action, String what) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError("Expected IllegalStateException for " + what + ", got " + other);
        }
        throw new AssertionError("Expected failure for " + what);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return NetBirdJson.quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(NetBirdJson.quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (List<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(json(item));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
