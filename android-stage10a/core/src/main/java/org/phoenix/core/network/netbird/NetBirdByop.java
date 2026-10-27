package org.phoenix.core.network.netbird;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bring Your Own Proxy: Phoenix's own NetBird proxy cluster on a VM the owner controls, so public game traffic enters
 * near the players instead of at a shared Cloud cluster. The cluster apex is the VM's public IPv4 under sslip.io
 * (for example {@code 129-153-1-2.sslip.io}), which resolves to the VM without any DNS setup and lets Phoenix read the
 * VM address from the cluster list. Services listen on the server's real ports, so players need no port numbers.
 */
public final class NetBirdByop {
    /** Pinned multi-arch proxy image (amd64/arm64) matching the bundled v0.80 NetBird client. */
    public static final String PROXY_IMAGE = "netbirdio/reverse-proxy:0.80.0@sha256:6d6655b3f13f837d80cc44878033866713932d3c5c1381ed869af9542b523bc5";
    /** Fixed WireGuard port so the phone can reach the proxy directly instead of through a NetBird relay. */
    public static final int WIREGUARD_PORT = 51820;
    public static final String TOKEN_NAME_PREFIX = "phoenix-proxy-";
    private static final Pattern APEX = Pattern.compile("(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.sslip\\.io");

    private NetBirdByop() {}

    /** One forwarded game protocol. */
    public static final class Forward {
        private final String label;
        private final NetBirdServicesClient.Mode mode;
        private final int port;

        Forward(String label, NetBirdServicesClient.Mode mode, int port) {
            this.label = label;
            this.mode = mode;
            this.port = NetBirdServicesClient.validPort(port, label + " port");
        }
        public String label() { return label; }
        public NetBirdServicesClient.Mode mode() { return mode; }
        public int port() { return port; }
    }

    /** The server's actual listener ports. [voiceUdp] is 0 when Simple Voice Chat is not installed. */
    public static final class Ports {
        private final int javaTcp;
        private final int bedrockUdp;
        private final int voiceUdp;

        public Ports(int javaTcp, int bedrockUdp, int voiceUdp) {
            this.javaTcp = NetBirdServicesClient.validPort(javaTcp, "Java port");
            this.bedrockUdp = NetBirdServicesClient.validPort(bedrockUdp, "Bedrock port");
            if (voiceUdp != 0) NetBirdServicesClient.validPort(voiceUdp, "voice port");
            this.voiceUdp = voiceUdp;
            if (voiceUdp == bedrockUdp) throw new IllegalArgumentException("Voice chat and Bedrock cannot share a UDP port");
            if (javaTcp == WIREGUARD_PORT || bedrockUdp == WIREGUARD_PORT || voiceUdp == WIREGUARD_PORT) {
                throw new IllegalArgumentException("Port " + WIREGUARD_PORT + " is reserved for the proxy's WireGuard tunnel");
            }
        }
        public int javaTcp() { return javaTcp; }
        public int bedrockUdp() { return bedrockUdp; }
        public int voiceUdp() { return voiceUdp; }

        public List<Forward> forwards() {
            List<Forward> forwards = new ArrayList<>();
            forwards.add(new Forward("Java", NetBirdServicesClient.Mode.TCP, javaTcp));
            forwards.add(new Forward("Bedrock", NetBirdServicesClient.Mode.UDP, bedrockUdp));
            if (voiceUdp != 0) forwards.add(new Forward("Voice", NetBirdServicesClient.Mode.UDP, voiceUdp));
            return List.copyOf(forwards);
        }
    }

    /** The cluster apex for a VM's public IPv4 address. */
    public static String apexFor(String ipv4) {
        String[] parts = Objects.requireNonNull(ipv4, "ipv4").trim().split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
            if (out.length() > 0) out.append('-');
            out.append(Integer.parseInt(part));
        }
        return out.append(".sslip.io").toString();
    }

    /** The VM's public IPv4 for a Phoenix cluster apex, or null when the address is not one. */
    public static String ipv4Of(String apex) {
        if (apex == null) return null;
        Matcher match = APEX.matcher(apex.trim().toLowerCase(Locale.ROOT));
        if (!match.matches()) return null;
        StringBuilder out = new StringBuilder();
        for (int group = 1; group <= 4; group++) {
            int octet = Integer.parseInt(match.group(group));
            if (octet > 255) return null;
            if (out.length() > 0) out.append('.');
            out.append(octet);
        }
        return out.toString();
    }

    /**
     * Picks Phoenix's own cluster: an account (BYOP) cluster whose apex is a VM address. An online cluster wins over an
     * offline one, and [preferredAddress] (the last one used) wins a tie, so a stale record never displaces a live proxy.
     */
    public static NetBirdServicesClient.ProxyCluster selectCluster(List<NetBirdServicesClient.ProxyCluster> clusters,
                                                                   String preferredAddress) {
        NetBirdServicesClient.ProxyCluster best = null;
        for (NetBirdServicesClient.ProxyCluster cluster : clusters) {
            if (!cluster.isAccount() || ipv4Of(cluster.address()) == null) continue;
            if (best == null || rank(cluster, preferredAddress) > rank(best, preferredAddress)) best = cluster;
        }
        return best;
    }

    private static int rank(NetBirdServicesClient.ProxyCluster cluster, String preferredAddress) {
        return (cluster.online() ? 2 : 0) + (cluster.address().equalsIgnoreCase(String.valueOf(preferredAddress)) ? 1 : 0);
    }

    /** Deterministic service names and domains; [key] is Phoenix's saved allocation key. */
    public static List<NetBirdServicesClient.ServiceSpec> plan(String key, String peerId, String apex, Ports ports) {
        String cleanKey = NetBirdServicesClient.nonBlank(key, "key").toLowerCase(Locale.ROOT);
        if (!cleanKey.matches("[a-z0-9]{4,32}")) throw new IllegalArgumentException("Invalid allocation key");
        if (ipv4Of(apex) == null) throw new IllegalArgumentException("Not a Phoenix proxy cluster address");
        List<NetBirdServicesClient.ServiceSpec> specs = new ArrayList<>();
        for (Forward forward : ports.forwards()) {
            String label = forward.label().toLowerCase(Locale.ROOT);
            specs.add(NetBirdServicesClient.ServiceSpec.minecraft(serviceName(cleanKey, forward.label()),
                    "phoenix-" + cleanKey + "-" + label + "." + apex, forward.mode(), peerId, forward.port(), forward.port()));
        }
        return List.copyOf(specs);
    }

    public static String serviceName(String key, String label) { return "Phoenix " + key + " Regional " + label; }

    /** What reconciliation did. Services are in plan order. */
    public static final class Outcome {
        private final List<NetBirdServicesClient.Service> services;
        private final int created;
        private final int updated;
        private final int moved;

        Outcome(List<NetBirdServicesClient.Service> services, int created, int updated, int moved) {
            this.services = List.copyOf(services);
            this.created = created;
            this.updated = updated;
            this.moved = moved;
        }
        public List<NetBirdServicesClient.Service> services() { return services; }
        public int created() { return created; }
        public int updated() { return updated; }
        /** Services recreated on a new cluster, for example after the VM was replaced and its address changed. */
        public int moved() { return moved; }
        public boolean allActive() {
            for (NetBirdServicesClient.Service service : services) if (!service.active()) return false;
            return !services.isEmpty();
        }
    }

    /**
     * Makes the account's US services match [plan]: creates missing ones, updates changed targets or ports in place, and
     * recreates a service whose domain moved to another cluster (the API derives the cluster from the domain). Services
     * not named in the plan, including the shared-cluster ones, are never touched. A duplicate name fails closed.
     * When [retireUnplanned] is true, a Phoenix US service of the same key that the plan no longer needs (voice chat
     * removed) is deleted.
     */
    public static Outcome reconcile(NetBirdServicesClient api, String key, List<NetBirdServicesClient.ServiceSpec> plan,
                                    String apex, boolean retireUnplanned) throws NetBirdApiException {
        List<NetBirdServicesClient.Service> existing = api.listServices();
        List<NetBirdServicesClient.Service> results = new ArrayList<>();
        int created = 0, updated = 0, moved = 0;
        for (NetBirdServicesClient.ServiceSpec spec : plan) {
            List<NetBirdServicesClient.Service> named = new ArrayList<>();
            for (NetBirdServicesClient.Service service : existing) if (service.name().equals(spec.name())) named.add(service);
            if (named.size() > 1) throw new IllegalStateException("NetBird has more than one service named \"" + spec.name() + "\"; remove the extra one in the NetBird dashboard");
            NetBirdServicesClient.Service result;
            if (named.isEmpty()) {
                result = api.createService(spec);
                created++;
            } else {
                NetBirdServicesClient.Service current = named.get(0);
                if (!current.domain().equals(spec.domain())) {
                    api.deleteService(current.id());
                    result = api.createService(spec);
                    moved++;
                } else if (!matches(current, spec)) {
                    result = api.updateService(current.id(), spec);
                    updated++;
                } else {
                    result = current;
                }
            }
            if (result.proxyCluster() != null && !result.proxyCluster().equalsIgnoreCase(apex)) {
                throw new IllegalStateException("NetBird placed " + spec.name() + " on " + result.proxyCluster() + " instead of the US proxy");
            }
            if (result.listenPort() != 0 && result.listenPort() != spec.listenPort()) {
                throw new IllegalStateException("The US proxy assigned port " + result.listenPort() + " instead of " + spec.listenPort()
                        + " for " + spec.name() + "; it must allow custom ports");
            }
            results.add(result);
        }
        if (retireUnplanned) {
            String prefix = "Phoenix " + key.toLowerCase(Locale.ROOT) + " Regional ";
            for (NetBirdServicesClient.Service service : existing) {
                boolean planned = false;
                for (NetBirdServicesClient.ServiceSpec spec : plan) planned |= spec.name().equals(service.name());
                if (!planned && service.name().startsWith(prefix)) api.deleteService(service.id());
            }
        }
        return new Outcome(results, created, updated, moved);
    }

    private static boolean matches(NetBirdServicesClient.Service current, NetBirdServicesClient.ServiceSpec spec) {
        if (current.mode() != spec.mode() || current.listenPort() != spec.listenPort() || !current.enabled()) return false;
        if (current.targets().size() != 1) return false;
        NetBirdServicesClient.ServiceTarget target = current.targets().get(0);
        return target.targetId().equals(spec.target().targetId()) && target.port() == spec.target().port()
                && target.protocol().equalsIgnoreCase(spec.target().protocol()) && target.enabled();
    }
}
