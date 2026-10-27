package org.phoenix.core.network.netbird;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-time pairing between Phoenix and the owner's new Oracle proxy VM, so the NetBird proxy token never appears in
 * Terraform variables, Terraform state, instance user data, URLs or logs.
 *
 * <p>Phoenix creates a pairing code: where to reach the phone (a temporary TCP service on NetBird's shared cluster),
 * the server's game ports and a random 128-bit pairing secret K. The owner pastes the code into Oracle's deployment
 * form. The VM boots, dials the phone and runs this protocol (newline-delimited JSON, each line at most 8 KiB):
 *
 * <ol>
 *   <li>VM: {@code {"v":1,"pub":<P-256 SPKI>,"n":<16 bytes>,"info":{region,shape,arch,ip,ad}}}</li>
 *   <li>Phone: {@code {"pub":<P-256 SPKI>,"n":<16 bytes>,"mac":HMAC(K,"phone"|T)}}</li>
 *   <li>VM: {@code {"mac":HMAC(K,"vm"|T)}}, proving it holds K. Only now does the phone release the proxy token.</li>
 *   <li>Phone: {@code {"iv":..,"ct":..}}: AES-256-GCM of {@code {"token","management"}} under
 *       HKDF-SHA256(salt=K, ikm=ECDH, info="phoenix-pair-v1 key"|T), with T as associated data.</li>
 *   <li>VM: {@code {"ok":true}} once the token is stored, or {@code {"ok":false,"error":..}}.</li>
 * </ol>
 *
 * T is SHA-256 over the protocol label, the VM's first line and the phone's key and nonce. Fresh ECDH keys on both
 * sides mean that someone who later reads K (from Terraform state or instance metadata) and recorded the exchange still
 * cannot decrypt the token; K only matters to an active attacker during the pairing window, and the phone discards it
 * once pairing succeeds.
 */
public final class ProxyPairing {
    public static final String VERSION = "phx1";
    static final int MAX_LINE = 8192;
    private static final String LABEL = "phoenix-pair-v1";
    private static final Pattern CODE = Pattern.compile(
            "phx1:([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?):(\\d{1,5}):(\\d{1,5}):(\\d{1,5}):(\\d{1,5}):([A-Z2-7]{26})");
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private ProxyPairing() {}

    /** What the owner pastes into Oracle: the pairing endpoint, the game ports and the pairing secret. */
    public static final class Code {
        private final String host;
        private final int port;
        private final NetBirdByop.Ports ports;
        private final byte[] secret;

        public Code(String host, int port, NetBirdByop.Ports ports, byte[] secret) {
            this.host = Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT);
            this.port = validPort(port);
            this.ports = Objects.requireNonNull(ports, "ports");
            if (secret == null || secret.length != 16) throw new IllegalArgumentException("Pairing secret must be 16 bytes");
            this.secret = secret.clone();
            if (!CODE.matcher(encode()).matches()) throw new IllegalArgumentException("Invalid pairing endpoint");
        }

        public String host() { return host; }
        public int port() { return port; }
        public NetBirdByop.Ports ports() { return ports; }
        public byte[] secret() { return secret.clone(); }

        /** {@code phx1:<host>:<port>:<java>:<bedrock>:<voice>:<secret>}; voice 0 means no voice chat. */
        public String encode() {
            return VERSION + ":" + host + ":" + port + ":" + ports.javaTcp() + ":" + ports.bedrockUdp() + ":" + ports.voiceUdp()
                    + ":" + base32(secret);
        }

        public static Code parse(String text) {
            Matcher match = CODE.matcher(text == null ? "" : text.trim());
            if (!match.matches()) throw new IllegalArgumentException("Not a Phoenix pairing code");
            int voice = Integer.parseInt(match.group(5));
            NetBirdByop.Ports ports = new NetBirdByop.Ports(validPort(Integer.parseInt(match.group(3))),
                    validPort(Integer.parseInt(match.group(4))), voice == 0 ? 0 : validPort(voice));
            return new Code(match.group(1), Integer.parseInt(match.group(2)), ports, unbase32(match.group(6)));
        }

        @Override public String toString() { return "ProxyPairing.Code{" + host + ":" + port + ", secret=<redacted>}"; }
    }

    /** What the VM reports about itself. The phone trusts it only after the VM has proven it holds the pairing secret. */
    public static final class VmInfo {
        private final String region;
        private final String shape;
        private final String arch;
        private final String ip;
        private final String availabilityDomain;

        VmInfo(String region, String shape, String arch, String ip, String availabilityDomain) {
            this.region = region;
            this.shape = shape;
            this.arch = arch;
            this.ip = ip;
            this.availabilityDomain = availabilityDomain;
        }
        public String region() { return region; }
        public String shape() { return shape; }
        public String arch() { return arch; }
        /** The VM's public IPv4, which names its proxy cluster ({@link NetBirdByop#apexFor}). */
        public String ip() { return ip; }
        public String availabilityDomain() { return availabilityDomain; }
    }

    /** Supplies the proxy token, asked only after the VM is authenticated. */
    public interface TokenIssuer {
        Issued issue(VmInfo vm) throws Exception;
    }

    /** A freshly minted token. {@link #revoke} runs if the VM does not confirm it stored the token. */
    public static final class Issued {
        private final String token;
        private final String managementUrl;
        private final Runnable revoke;

        public Issued(String token, String managementUrl, Runnable revoke) {
            this.token = Objects.requireNonNull(token, "token");
            this.managementUrl = Objects.requireNonNull(managementUrl, "managementUrl");
            this.revoke = Objects.requireNonNull(revoke, "revoke");
        }
        @Override public String toString() { return "ProxyPairing.Issued{<redacted>}"; }
    }

    public static final class PairingException extends Exception {
        private final boolean authentication;

        PairingException(String message, boolean authentication) {
            super(message);
            this.authentication = authentication;
        }
        /** True when the peer failed to prove the pairing secret, rather than a transport or format problem. */
        public boolean authentication() { return authentication; }
    }

    public static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[16];
        random.nextBytes(secret);
        return secret;
    }

    /**
     * Phone side of one pairing connection. Returns the VM's report after it confirmed the token was stored; the
     * token was revoked again if anything failed after it was issued.
     */
    public static VmInfo serve(InputStream in, OutputStream out, byte[] secret, TokenIssuer issuer, SecureRandom random)
            throws IOException, PairingException {
        if (secret == null || secret.length != 16) throw new IllegalArgumentException("Pairing secret must be 16 bytes");
        byte[] hello = readLine(in);
        Map<String, Object> first = object(hello);
        if (!(first.get("v") instanceof Number) || ((Number) first.get("v")).intValue() != 1) throw new PairingException("Unsupported pairing version", false);
        PublicKey vmKey = publicKey(bytes(first, "pub", 128));
        bytes(first, "n", 16);
        VmInfo info = info(first.get("info"));

        try {
            KeyPair own = keyPair(random);
            byte[] ownPub = own.getPublic().getEncoded();
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            byte[] transcript = transcript(hello, ownPub, nonce);
            writeLine(out, "{\"pub\":" + b64(ownPub) + ",\"n\":" + b64(nonce) + ",\"mac\":" + b64(mac(secret, "phone", transcript)) + "}");

            byte[] proof = bytes(object(readLine(in)), "mac", 32);
            if (!MessageDigest.isEqual(proof, mac(secret, "vm", transcript))) throw new PairingException("The VM did not prove the pairing code", true);

            Issued issued;
            try { issued = issuer.issue(info); }
            catch (Exception failure) {
                writeLine(out, "{\"error\":\"phone could not create a proxy token\"}");
                throw new IOException("Could not create the proxy token: " + failure.getMessage(), failure);
            }
            boolean confirmed = false;
            try {
                byte[] key = hkdf(secret, agree(own, vmKey), concat(LABEL.getBytes(StandardCharsets.US_ASCII), " key".getBytes(StandardCharsets.US_ASCII), transcript), 32);
                byte[] iv = new byte[12];
                random.nextBytes(iv);
                String payload = "{\"token\":" + NetBirdJson.quote(issued.token) + ",\"management\":" + NetBirdJson.quote(issued.managementUrl) + "}";
                byte[] plain = payload.getBytes(StandardCharsets.UTF_8);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                cipher.updateAAD(transcript);
                byte[] sealed = cipher.doFinal(plain);
                Arrays.fill(plain, (byte) 0);
                Arrays.fill(key, (byte) 0);
                writeLine(out, "{\"iv\":" + b64(iv) + ",\"ct\":" + b64(sealed) + "}");
                Map<String, Object> done = object(readLine(in));
                if (!Boolean.TRUE.equals(done.get("ok"))) {
                    Object error = done.get("error");
                    throw new IOException("The VM could not store the proxy token" + (error instanceof String ? ": " + clip((String) error) : ""));
                }
                confirmed = true;
                return info;
            } finally {
                if (!confirmed) issued.revoke.run();
            }
        } catch (GeneralSecurityException failure) {
            throw new PairingException("Pairing cryptography failed: " + failure.getClass().getSimpleName(), false);
        }
    }

    // ---------------------------------------------------------------- VM side (used by tests; the VM runs phoenix_pair.py)

    /** The VM's view of a finished pairing. */
    public static final class Received {
        private final String token;
        private final String managementUrl;

        Received(String token, String managementUrl) {
            this.token = token;
            this.managementUrl = managementUrl;
        }
        public String token() { return token; }
        public String managementUrl() { return managementUrl; }
        @Override public String toString() { return "ProxyPairing.Received{<redacted>}"; }
    }

    /** Reference VM implementation of the protocol, mirroring phoenix_pair.py. */
    public static Received pair(InputStream in, OutputStream out, byte[] secret, String infoJson, SecureRandom random)
            throws IOException, PairingException {
        try {
            KeyPair own = keyPair(random);
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String hello = "{\"v\":1,\"pub\":" + b64(own.getPublic().getEncoded()) + ",\"n\":" + b64(nonce) + ",\"info\":" + infoJson + "}";
            writeLine(out, hello);
            Map<String, Object> reply = object(readLine(in));
            byte[] phonePub = bytes(reply, "pub", 128);
            byte[] phoneNonce = bytes(reply, "n", 16);
            byte[] transcript = transcript(hello.getBytes(StandardCharsets.UTF_8), phonePub, phoneNonce);
            if (!MessageDigest.isEqual(bytes(reply, "mac", 32), mac(secret, "phone", transcript))) throw new PairingException("The phone did not prove the pairing code", true);
            writeLine(out, "{\"mac\":" + b64(mac(secret, "vm", transcript)) + "}");
            Map<String, Object> sealed = object(readLine(in));
            if (sealed.containsKey("error")) throw new IOException("Phone refused: " + sealed.get("error"));
            byte[] key = hkdf(secret, agree(own, publicKey(phonePub)), concat(LABEL.getBytes(StandardCharsets.US_ASCII), " key".getBytes(StandardCharsets.US_ASCII), transcript), 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes(sealed, "iv", 12)));
            cipher.updateAAD(transcript);
            Map<String, Object> payload = NetBirdJson.object(NetBirdJson.parse(new String(cipher.doFinal(bytes(sealed, "ct", 4096)), StandardCharsets.UTF_8)));
            writeLine(out, "{\"ok\":true}");
            return new Received((String) payload.get("token"), (String) payload.get("management"));
        } catch (GeneralSecurityException failure) {
            throw new PairingException("Pairing cryptography failed: " + failure.getClass().getSimpleName(), false);
        }
    }

    // ---------------------------------------------------------------- primitives

    private static VmInfo info(Object value) throws PairingException {
        if (!(value instanceof Map)) throw new PairingException("Missing VM report", false);
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
        String ip = field(map, "ip");
        try { NetBirdByop.apexFor(ip); }
        catch (IllegalArgumentException invalid) { throw new PairingException("The VM did not report a public IPv4 address", false); }
        return new VmInfo(field(map, "region"), field(map, "shape"), field(map, "arch"), ip, field(map, "ad"));
    }

    private static String field(Map<String, Object> map, String name) throws PairingException {
        Object value = map.get(name);
        if (value == null) return "";
        if (!(value instanceof String) || ((String) value).length() > 128 || !((String) value).matches("[A-Za-z0-9 ._:()+-]*")) {
            throw new PairingException("Invalid VM report field " + name, false);
        }
        return (String) value;
    }

    private static KeyPair keyPair(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        return generator.generateKeyPair();
    }

    private static PublicKey publicKey(byte[] spki) throws PairingException {
        try { return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki)); }
        catch (GeneralSecurityException | RuntimeException invalid) { throw new PairingException("Invalid pairing key", false); }
    }

    private static byte[] agree(KeyPair own, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own.getPrivate());
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    static byte[] transcript(byte[] hello, byte[] phonePub, byte[] phoneNonce) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(LABEL.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(hello);
        digest.update((byte) 0);
        digest.update(phonePub);
        digest.update(phoneNonce);
        return digest.digest();
    }

    private static byte[] mac(byte[] secret, String role, byte[] transcript) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(role.getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(transcript);
    }

    /** RFC 5869 HKDF-SHA256. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        Arrays.fill(ikm, (byte) 0);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        for (int counter = 1; out.size() < length; counter++) {
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            out.write(previous, 0, previous.length);
        }
        Arrays.fill(prk, (byte) 0);
        return Arrays.copyOf(out.toByteArray(), length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = in.read();
            if (next < 0) throw new IOException("Pairing connection closed");
            if (next == '\n') return line.toByteArray();
            if (line.size() >= MAX_LINE) throw new IOException("Pairing message too long");
            line.write(next);
        }
    }

    static void writeLine(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, Object> object(byte[] line) throws PairingException {
        try { return NetBirdJson.object(NetBirdJson.parse(new String(line, StandardCharsets.UTF_8))); }
        catch (RuntimeException invalid) { throw new PairingException("Malformed pairing message", false); }
    }

    private static byte[] bytes(Map<String, Object> map, String name, int maxLength) throws PairingException {
        Object value = map.get(name);
        if (!(value instanceof String)) throw new PairingException("Missing " + name, false);
        try {
            byte[] decoded = Base64.getDecoder().decode((String) value);
            if (decoded.length == 0 || decoded.length > maxLength) throw new PairingException("Invalid " + name, false);
            return decoded;
        } catch (IllegalArgumentException invalid) { throw new PairingException("Invalid " + name, false); }
    }

    private static String b64(byte[] value) { return "\"" + Base64.getEncoder().encodeToString(value) + "\""; }

    private static String clip(String text) { return text.length() > 160 ? text.substring(0, 160) : text; }

    private static int validPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return port;
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { out.append(BASE32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] unbase32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : text.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Invalid pairing secret");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}
