package org.phoenix.core.provision;

/** Byte-level progress for a pinned artifact transfer. */
public record ProvisioningProgress(
        String artifactId,
        long artifactBytesReceived,
        long artifactBytesExpected,
        long totalBytesReceived,
        long totalBytesExpected) {
}
