package org.phoenix.core.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provider-neutral DNS plan for a relay endpoint. It describes records for
 * manual DNS dashboard entry; it never claims that DNS alone proves Minecraft reachability.
 */
public final class MinecraftDnsPlan {
    private static final Pattern HOST = Pattern.compile("(?i)(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    public enum Type { A, AAAA, CNAME, SRV }

    public static final class Record {
        private final Type type;
        private final String name;
        private final String target;
        private final int priority;
        private final int weight;
        private final int port;
        private final int ttl;

        private Record(Type type, String name, String target, int priority, int weight, int port, int ttl) {
            this.type = type;
            this.name = name;
            this.target = target;
            this.priority = priority;
            this.weight = weight;
            this.port = port;
            this.ttl = ttl;
        }

        public Type type() { return type; }
        public String name() { return name; }
        public String target() { return target; }
        public int priority() { return priority; }
        public int weight() { return weight; }
        public int port() { return port; }
        public int ttl() { return ttl; }
    }

    private final String hostname;
    private final String javaAddress;
    private final String bedrockAddress;
    private final List<Record> records;

    private MinecraftDnsPlan(String hostname, String javaAddress, String bedrockAddress, List<Record> records) {
        this.hostname = hostname;
        this.javaAddress = javaAddress;
        this.bedrockAddress = bedrockAddress;
        this.records = List.copyOf(records);
    }

    /**
     * Creates a host route plus Java SRV plan for manual entry in a DNS provider's
     * dashboard. A DNS relay hostname uses a CNAME; an IPv4/IPv6 relay uses an
     * A/AAAA record and targets the friendly hostname in the SRV record. Bedrock receives the hostname and UDP port,
     * because Bedrock clients do not consume Java SRV records.
     */
    public static MinecraftDnsPlan forRelay(String hostname, String relayHost, int tcpPort, int udpPort) {
        return forEndpoints(hostname, relayHost, tcpPort, relayHost, udpPort);
    }

    /**
     * Creates one friendly DNS route for separate Java/TCP and Bedrock/UDP endpoint
     * descriptions. The route can represent both protocols only when both endpoint
     * hosts identify the same DNS name or IP address. Their ports may differ.
     *
     * <p>Java clients use the apex route plus the generated SRV record, which points
     * to the TCP port. Bedrock clients use the apex route and the UDP port returned
     * by {@link #bedrockAddress()}; DNS SRV is not used for Bedrock.</p>
     *
     * @throws IllegalArgumentException if the endpoint hosts differ or any value is invalid
     */
    public static MinecraftDnsPlan forEndpoints(
            String hostname,
            String tcpHost,
            int tcpPort,
            String udpHost,
            int udpPort) {
        String cleanHostname = normalizeHostname(hostname);
        RelayTarget tcpTarget = validateHost(tcpHost);
        RelayTarget udpTarget = validateHost(udpHost);
        validatePort(tcpPort);
        validatePort(udpPort);
        if (!sameEndpointHost(tcpTarget, udpTarget)) {
            throw new IllegalArgumentException("Java and Bedrock endpoint hosts must match for one friendly hostname");
        }
        List<Record> records = List.of(
                new Record(tcpTarget.recordType, cleanHostname, tcpTarget.value, 0, 0, 0, 300),
                new Record(Type.SRV, "_minecraft._tcp." + cleanHostname, tcpTarget.srvTarget(cleanHostname), 0, 0, tcpPort, 300));
        return new MinecraftDnsPlan(cleanHostname, cleanHostname, cleanHostname + ":" + udpPort, records);
    }

    /** Returns whether Java and Bedrock endpoint hosts can share one apex DNS route. */
    public static boolean hasCompatibleEndpointHosts(String tcpHost, String udpHost) {
        try {
            return sameEndpointHost(validateHost(tcpHost), validateHost(udpHost));
        } catch (IllegalArgumentException invalidHost) {
            return false;
        }
    }

    public String hostname() { return hostname; }
    public String javaAddress() { return javaAddress; }
    public String bedrockAddress() { return bedrockAddress; }
    public List<Record> records() { return records; }

    private static String normalizeHostname(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(clean).matches()) throw new IllegalArgumentException("Invalid hostname");
        return clean;
    }

    private static RelayTarget validateHost(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("[") && clean.endsWith("]")) clean = clean.substring(1, clean.length() - 1);
        if (clean.isBlank() || clean.length() > 253 || clean.indexOf('/') >= 0 || clean.indexOf('@') >= 0 || clean.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Invalid relay host");
        }
        if (isIpv4(clean)) return new RelayTarget(Type.A, clean, clean);
        if (isIpv6(clean)) return new RelayTarget(Type.AAAA, clean, clean);
        String normalized = clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
        if (!HOST.matcher(normalized).matches()) throw new IllegalArgumentException("Invalid relay host");
        return new RelayTarget(Type.CNAME, normalized, normalized);
    }

    private static boolean isIpv4(String value) {
        if (!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        String[] octets = value.split("\\.");
        for (String octet : octets) {
            int part = Integer.parseInt(octet);
            if (part < 0 || part > 255) return false;
        }
        return true;
    }

    private static boolean isIpv6(String value) {
        if (!value.contains(":")) return false;
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sameEndpointHost(RelayTarget left, RelayTarget right) {
        if (left.recordType != right.recordType) return false;
        if (left.recordType == Type.A || left.recordType == Type.AAAA) {
            try {
                return Arrays.equals(InetAddress.getByName(left.value).getAddress(),
                        InetAddress.getByName(right.value).getAddress());
            } catch (Exception invalidAddress) {
                return false;
            }
        }
        return left.value.equalsIgnoreCase(right.value);
    }

    private static final class RelayTarget {
        final Type recordType;
        final String value;
        final String hostnameTarget;

        RelayTarget(Type recordType, String value, String hostnameTarget) {
            this.recordType = recordType;
            this.value = value;
            this.hostnameTarget = hostnameTarget;
        }

        String srvTarget(String hostname) {
            return recordType == Type.CNAME ? hostnameTarget : hostname;
        }
    }

    private static void validatePort(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Invalid port");
    }
}
