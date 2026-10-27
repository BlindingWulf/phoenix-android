package org.phoenix.core.network;

/** Deterministic compatibility checks for one friendly DNS name across Java and Bedrock endpoints. */
public final class MinecraftDnsAcceptance {
    public static void main(String[] args) {
        MinecraftDnsPlan sameHost = MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "relay.example.net", 25570, "RELAY.EXAMPLE.NET.", 19170);
        check(sameHost.records().size() == 2, "one apex route and one Java SRV record");
        check(sameHost.records().get(0).type() == MinecraftDnsPlan.Type.CNAME, "DNS hostname route type");
        check(sameHost.records().get(0).target().equals("relay.example.net"), "normalized CNAME target");
        check(sameHost.records().get(1).name().equals("_minecraft._tcp.friend-hangout.example.net"), "Java service owner");
        check(sameHost.records().get(1).target().equals("relay.example.net"), "Java SRV target");
        check(sameHost.records().get(1).port() == 25570, "Java SRV uses the TCP port");
        check(sameHost.bedrockAddress().equals("friend-hangout.example.net:19170"), "Bedrock address uses the UDP port");
        check(MinecraftDnsPlan.hasCompatibleEndpointHosts("relay.example.net", "RELAY.EXAMPLE.NET."), "hostname match ignores case and root dot");

        MinecraftDnsPlan sameIpv4 = MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "198.51.100.10", 25573, "198.51.100.10", 19144);
        check(sameIpv4.records().get(0).type() == MinecraftDnsPlan.Type.A, "IPv4 route type");
        check(sameIpv4.records().get(1).target().equals("friend-hangout.example.net"), "Java SRV uses friendly hostname for an IP route");
        check(sameIpv4.records().get(1).port() == 25573, "IPv4 plan keeps TCP port");
        check(sameIpv4.bedrockAddress().equals("friend-hangout.example.net:19144"), "IPv4 plan keeps UDP port");
        check(MinecraftDnsPlan.hasCompatibleEndpointHosts("198.51.100.10", "198.51.100.10"), "matching IPv4 endpoint hosts");

        MinecraftDnsPlan sameIpv6 = MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "2001:db8::10", 25565, "[2001:0db8:0:0:0:0:0:10]", 19132);
        check(sameIpv6.records().get(0).type() == MinecraftDnsPlan.Type.AAAA, "IPv6 route type");
        check(sameIpv6.records().get(1).target().equals("friend-hangout.example.net"), "IPv6 SRV target uses friendly hostname");
        check(MinecraftDnsPlan.hasCompatibleEndpointHosts("2001:db8::10", "2001:0db8:0:0:0:0:0:10"), "IPv6 equality compares address bytes");

        check(!MinecraftDnsPlan.hasCompatibleEndpointHosts("relay-a.example.net", "relay-b.example.net"), "different hostnames cannot share one route");
        check(!MinecraftDnsPlan.hasCompatibleEndpointHosts("198.51.100.10", "198.51.100.11"), "different IP addresses cannot share one route");
        expectInvalid(() -> MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "relay-a.example.net", 25570, "relay-b.example.net", 19170),
                "different Java and Bedrock endpoint hostnames");
        expectInvalid(() -> MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "198.51.100.10", 25570, "198.51.100.11", 19170),
                "different Java and Bedrock endpoint addresses");
        expectInvalid(() -> MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "relay.example.net", 0, "relay.example.net", 19170),
                "invalid TCP port");
        expectInvalid(() -> MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "relay.example.net", 25570, "relay.example.net", 65536),
                "invalid UDP port");
        expectInvalid(() -> MinecraftDnsPlan.forEndpoints(
                "friend-hangout.example.net", "relay.example.net", 25570, "not a host", 19170),
                "invalid Bedrock endpoint host");

        System.out.println("MINECRAFT_DNS_ACCEPTANCE PASS: dual-endpoint host compatibility and Java/Bedrock ports");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void expectInvalid(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " must reject invalid input");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
