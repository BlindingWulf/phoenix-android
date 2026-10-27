package org.phoenix.core;

/** Provider identity travels with the pinned server and its telemetry adapter. */
public interface ServerProvider {
    String id();
    String minecraftVersion();
    int requiredJavaMajor();
    int worldDataVersion();
    String serverArtifactName();
    String instrumentationAdapter();
}
