package org.phoenix.core;

/** Only ordinary unoptimized distributions are wired in Stage 10A. */
public enum BaselineProvider implements ServerProvider {
    VANILLA("vanilla-26.2", "server.jar"),
    FABRIC("fabric-26.2-loader-0.19.5", "fabric-server-launch.jar");
    private final String id, jar;
    BaselineProvider(String id,String jar) { this.id=id; this.jar=jar; }
    public String id() { return id; }
    public String minecraftVersion() { return "26.2"; }
    public int requiredJavaMajor() { return 25; }
    public int worldDataVersion() { return 4903; }
    public String serverArtifactName() { return jar; }
    public String instrumentationAdapter() { return "mxbeans-v1-and-minecraft-26.2-commands"; }
}
