package org.phoenix.core.provision;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One exact, bounded artifact in a reviewed dependency lock. */
public record Artifact(String id, URI uri, String fileName, long sizeBytes,
        String sha256, String sha1) {
    private static final Pattern HEX_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern HEX_1 = Pattern.compile("[0-9a-fA-F]{40}");
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L;

    public Artifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
        if (id.trim().isEmpty() || !id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid artifact id");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !isOfficialHost(uri.getHost())) {
            throw new IllegalArgumentException("artifact URL must use an official HTTPS host");
        }
        if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("artifact URL contains unsupported authority data");
        }
        if (fileName.trim().isEmpty() || fileName.equals(".") || fileName.equals("..")
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")
                || fileName.indexOf('\0') >= 0 || !fileName.equals(fileName.trim())) {
            throw new IllegalArgumentException("artifact filename must be one safe path segment");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("artifact size is outside the supported bound");
        }
        if (!HEX_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("artifact SHA-256 must be 64 hexadecimal characters");
        }
        if (sha1 != null && !HEX_1.matcher(sha1).matches()) {
            throw new IllegalArgumentException("artifact SHA-1 must be 40 hexadecimal characters");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (sha1 != null) {
            sha1 = sha1.toLowerCase(Locale.ROOT);
        }
    }

    private static boolean isOfficialHost(String hostValue) {
        if (hostValue == null) {
            return false;
        }
        String host = hostValue.toLowerCase(Locale.ROOT);
        return host.equals("mojang.com") || host.endsWith(".mojang.com")
                || host.equals("minecraft.net") || host.endsWith(".minecraft.net")
                || host.equals("fabricmc.net") || host.endsWith(".fabricmc.net");
    }
}
