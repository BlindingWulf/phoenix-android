package org.phoenix.core.provision;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable artifact pins read from the checked-in dependency manifest. */
public final class PinnedDistribution {
    private final String minecraftVersion;
    private final int minecraftDataVersion;
    private final String fabricLoaderVersion;
    private final String fabricInstallerVersion;
    private final List<Artifact> artifacts;
    private final String fabricServerMainClass;

    private PinnedDistribution(String minecraftVersion, int minecraftDataVersion,
            String fabricLoaderVersion,
            String fabricInstallerVersion, String fabricServerMainClass,
            List<Artifact> artifacts) {
        this.minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        if (minecraftDataVersion <= 0) {
            throw new IllegalArgumentException("minecraftDataVersion must be positive");
        }
        this.minecraftDataVersion = minecraftDataVersion;
        this.fabricLoaderVersion = requireText(fabricLoaderVersion, "fabricLoaderVersion");
        this.fabricInstallerVersion = requireText(fabricInstallerVersion, "fabricInstallerVersion");
        this.fabricServerMainClass = requireText(fabricServerMainClass, "fabricServerMainClass");
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("manifest contains no artifacts");
        }
        Set<String> ids = new HashSet<>();
        Set<String> fileNames = new HashSet<>();
        for (Artifact artifact : artifacts) {
            if (!ids.add(artifact.id())) {
                throw new IllegalArgumentException("duplicate artifact id: " + artifact.id());
            }
            if (!fileNames.add(artifact.fileName())) {
                throw new IllegalArgumentException("duplicate artifact filename: " + artifact.fileName());
            }
            if (artifact.fileName().equalsIgnoreCase("eula.txt")
                    || artifact.fileName().equalsIgnoreCase("server.properties")) {
                throw new IllegalArgumentException("artifact may not replace server configuration");
            }
        }
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts));
    }

    public static PinnedDistribution fromJson(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > 1_000_000) {
            throw new IllegalArgumentException("manifest exceeds the supported size");
        }
        Object parsed = MiniJson.parse(json);
        Map<String, Object> root = object(parsed, "root");
        if (integer(root.get("schemaVersion"), "schemaVersion") != 1) {
            throw new IllegalArgumentException("unsupported manifest schemaVersion");
        }
        Map<String, Object> defaults = object(root.get("defaults"), "defaults");
        if (bool(defaults.get("eulaAccepted"), "defaults.eulaAccepted")) {
            throw new IllegalArgumentException("pinned distribution must not accept the EULA");
        }
        if (bool(defaults.get("createDefaultWorld"), "defaults.createDefaultWorld")) {
            throw new IllegalArgumentException("pinned distribution must not create a default world");
        }
        Object modValue = defaults.get("optimizationMods");
        if (!(modValue instanceof List<?> mods) || !mods.isEmpty()) {
            throw new IllegalArgumentException("baseline distribution must not include optimization mods");
        }

        List<?> rows = array(root.get("artifacts"), "artifacts");
        List<Artifact> artifacts = new ArrayList<>(rows.size());
        Map<String, Map<String, Object>> artifactMetadata = new java.util.LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> item = object(rows.get(index), "artifacts[" + index + "]");
            String id = string(item.get("id"), "artifact.id");
            artifacts.add(new Artifact(
                    id,
                    URI.create(string(item.get("url"), "artifact.url")),
                    string(item.get("fileName"), "artifact.fileName"),
                    longInteger(item.get("sizeBytes"), "artifact.sizeBytes"),
                    string(item.get("sha256"), "artifact.sha256"),
                    optionalString(item.get("sha1"), "artifact.sha1")));
            artifactMetadata.put(id, item);
        }
        String minecraftVersion = string(root.get("minecraftVersion"), "minecraftVersion");
        int minecraftDataVersion = integer(root.get("minecraftDataVersion"), "minecraftDataVersion");
        String loaderVersion = string(root.get("fabricLoaderVersion"), "fabricLoaderVersion");
        String installerVersion = string(root.get("fabricInstallerVersion"), "fabricInstallerVersion");
        String mainClass = string(root.get("fabricServerMainClass"), "fabricServerMainClass");
        if (!minecraftVersion.equals("26.2") || minecraftDataVersion != 4903
                || !loaderVersion.matches("0\\.[0-9]+\\.[0-9]+")
                || !installerVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !mainClass.equals("net.fabricmc.loader.impl.launch.knot.KnotServer")) {
            throw new IllegalArgumentException("manifest does not describe the Stage10A server profile");
        }
        Map<String, Object> minecraftMetadata = artifactMetadata.get("minecraft-server");
        Map<String, Object> fabricMetadata = artifactMetadata.get("fabric-server-launcher");
        if (minecraftMetadata == null || fabricMetadata == null
                || !string(minecraftMetadata.get("expectedSha1FromMojang"),
                        "minecraft-server.expectedSha1FromMojang")
                        .equalsIgnoreCase(artifactById(artifacts, "minecraft-server").sha1())
                || !string(fabricMetadata.get("embeddedMinecraftVersion"),
                        "fabric-server-launcher.embeddedMinecraftVersion").equals(minecraftVersion)
                || !string(fabricMetadata.get("embeddedFabricLoaderVersion"),
                        "fabric-server-launcher.embeddedFabricLoaderVersion").equals(loaderVersion)) {
            throw new IllegalArgumentException("artifact provenance fields do not match the pinned profile");
        }
        PinnedDistribution distribution = new PinnedDistribution(
                minecraftVersion, minecraftDataVersion, loaderVersion, installerVersion, mainClass, artifacts);
        distribution.validateArtifactPins();
        return distribution;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    /** Minecraft 26.2 world DataVersion, sourced from the pinned Mojang server bundle. */
    public int minecraftDataVersion() {
        return minecraftDataVersion;
    }

    public String fabricLoaderVersion() {
        return fabricLoaderVersion;
    }

    public String fabricInstallerVersion() {
        return fabricInstallerVersion;
    }

    public String fabricServerMainClass() {
        return fabricServerMainClass;
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    private void validateArtifactPins() {
        if (artifacts.size() != 2) {
            throw new IllegalArgumentException("Stage10A manifest must contain exactly two base artifacts");
        }
        Artifact minecraft = artifactById("minecraft-server");
        Artifact fabric = artifactById("fabric-server-launcher");
        if (!minecraft.fileName().equals("server.jar")
                || !"piston-data.mojang.com".equalsIgnoreCase(minecraft.uri().getHost())
                || !minecraft.uri().getPath().matches("/v1/objects/[0-9a-fA-F]{40}/server\\.jar")
                || minecraft.sha1() == null
                || !minecraft.uri().getPath().contains(minecraft.sha1())) {
            throw new IllegalArgumentException("Minecraft server artifact is not pinned to Mojang metadata");
        }
        String expectedFabricPath = "/v2/versions/loader/26.2/" + fabricLoaderVersion
                + "/" + fabricInstallerVersion + "/server/jar";
        if (!fabric.fileName().equals("fabric-server-launch.jar")
                || !"meta.fabricmc.net".equalsIgnoreCase(fabric.uri().getHost())
                || !expectedFabricPath.equals(fabric.uri().getPath())) {
            throw new IllegalArgumentException("Fabric server artifact is not pinned to the selected versions");
        }
    }

    private Artifact artifactById(String id) {
        return artifactById(artifacts, id);
    }

    private static Artifact artifactById(List<Artifact> artifacts, String id) {
        return artifacts.stream().filter(artifact -> artifact.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing pinned artifact: " + id));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value;
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    private static List<?> array(Object value, String field) {
        if (!(value instanceof List<?> result)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return result;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return requireText(result, field);
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        return string(value, field);
    }

    private static int integer(Object value, String field) {
        long number = longInteger(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return (int) number;
    }

    private static long longInteger(Object value, String field) {
        if (!(value instanceof Long result) || result < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return result;
    }

    private static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return result;
    }
}
