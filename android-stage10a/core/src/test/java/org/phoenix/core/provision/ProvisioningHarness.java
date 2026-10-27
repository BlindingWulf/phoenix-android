package org.phoenix.core.provision;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plain Java main/assert checks; no JUnit or Android runtime is required. */
public final class ProvisioningHarness {
    private ProvisioningHarness() {
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "pass the checked-in dependency manifest path");
        String checkedIn = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        PinnedDistribution actual = PinnedDistribution.fromJson(checkedIn);
        check(actual.minecraftVersion().equals("26.2"), "Minecraft pin");
        check(actual.minecraftDataVersion() == 4903, "Minecraft 26.2 world DataVersion pin");
        check(actual.fabricLoaderVersion().equals("0.19.5"), "Fabric Loader pin");
        check(actual.artifacts().size() == 2, "base artifact count");
        expectThrows(IllegalArgumentException.class,
                () -> PinnedDistribution.fromJson(checkedIn.replace("\"minecraftDataVersion\": 4903",
                        "\"minecraftDataVersion\": 4902")), "manifest DataVersion must match Minecraft 26.2");
        expectThrows(IllegalArgumentException.class,
                () -> PinnedDistribution.fromJson(checkedIn.replace("\"eulaAccepted\": false",
                        "\"eulaAccepted\": true")), "manifest cannot auto-accept the EULA");
        expectThrows(IllegalArgumentException.class,
                () -> PinnedDistribution.fromJson(checkedIn.replace("\"createDefaultWorld\": false",
                        "\"createDefaultWorld\": true")), "manifest cannot create a default world");

        byte[] server = "tiny pinned server".getBytes(StandardCharsets.UTF_8);
        byte[] fabric = "tiny pinned fabric launcher".getBytes(StandardCharsets.UTF_8);
        String serverSha1 = digest("SHA-1", server);
        String serverSha256 = digest("SHA-256", server);
        String fabricSha1 = digest("SHA-1", fabric);
        String fabricSha256 = digest("SHA-256", fabric);
        String manifest = fixture(server.length, serverSha1, serverSha256,
                fabric.length, fabricSha1, fabricSha256);
        PinnedDistribution distribution = PinnedDistribution.fromJson(manifest);
        Map<String, byte[]> contents = Map.of(
                distribution.artifacts().get(0).uri().toString(), server,
                distribution.artifacts().get(1).uri().toString(), fabric);
        Path temporary = Files.createTempDirectory("phoenix-provision-harness-");
        try {
            Path spaceLimitedDirectory = temporary.resolve("space-limited");
            AtomicBoolean sourceOpened = new AtomicBoolean();
            long requiredSpace = (long) server.length + fabric.length
                    + Provisioner.REQUIRED_FREE_SPACE_RESERVE_BYTES;
            Provisioner insufficientSpace = new Provisioner((uri, expected) -> {
                sourceOpened.set(true);
                return new ByteArrayInputStream(contents.get(uri.toString()));
            }, ignored -> requiredSpace - 1);
            try {
                insufficientSpace.install(distribution, spaceLimitedDirectory,
                        () -> false, ignored -> { });
                throw new AssertionError("insufficient free space must fail before download");
            } catch (IOException expected) {
                check(expected.getMessage().contains("insufficient free space"),
                        "free-space failure identifies the preflight condition");
            }
            check(!sourceOpened.get(), "free-space preflight runs before opening artifact streams");
            check(!Files.exists(spaceLimitedDirectory, LinkOption.NOFOLLOW_LINKS),
                    "free-space preflight does not create the install target");
            try (var contentsAtParent = Files.list(temporary)) {
                check(contentsAtParent.noneMatch(path -> path.getFileName().toString()
                        .startsWith(".phoenix-provision-stage-")),
                        "free-space preflight does not create a staging directory");
            }

            Path installDirectory = temporary.resolve("server");
            AtomicBoolean sawProgress = new AtomicBoolean();
            Provisioner provisioner = new Provisioner((uri, expected) -> {
                byte[] bytes = contents.get(uri.toString());
                if (bytes == null) {
                    throw new IOException("unexpected test URI");
                }
                return new ByteArrayInputStream(bytes);
            });
            ProvisioningResult result = provisioner.install(distribution, installDirectory,
                    () -> false, progress -> {
                        check(progress.totalBytesExpected() == server.length + fabric.length,
                                "known total byte count");
                        sawProgress.set(true);
                    });
            check(sawProgress.get(), "progress callbacks");
            check(Files.readAllBytes(installDirectory.resolve("server.jar")).length == server.length,
                    "verified vanilla artifact installed");
            check(Files.readAllBytes(installDirectory.resolve("fabric-server-launch.jar")).length == fabric.length,
                    "verified Fabric launcher installed");
            check(!Files.exists(installDirectory.resolve("eula.txt"), LinkOption.NOFOLLOW_LINKS),
                    "provisioning leaves EULA decision absent");
            check(!EulaDecisionWriter.isAccepted(installDirectory), "EULA gate defaults to false");
            check(Files.readString(installDirectory.resolve("phoenix-install.json"))
                    .contains("\"eulaAccepted\": false"), "install stamp reports no EULA acceptance");
            check(result.installedFiles().size() == 3, "install stamp and both server artifacts committed");
            EulaDecisionWriter.writeUserDecision(installDirectory, true);
            check(Files.readString(installDirectory.resolve("eula.txt")).contains("eula=true"),
                    "separate explicit EULA decision writer");
            check(EulaDecisionWriter.isAccepted(installDirectory), "EULA gate reads affirmative decision");

            byte[] oldServer = "previous installation".getBytes(StandardCharsets.UTF_8);
            Files.write(installDirectory.resolve("server.jar"), oldServer);
            PinnedDistribution badHash = PinnedDistribution.fromJson(
                    fixture(server.length, serverSha1, "0".repeat(64),
                            fabric.length, fabricSha1, fabricSha256));
            Provisioner badHashProvisioner = new Provisioner((uri, expected) ->
                    new ByteArrayInputStream(contents.get(uri.toString())));
            expectThrows(IOException.class, () -> badHashProvisioner.install(badHash,
                    installDirectory, () -> false, ignored -> { }), "bad digest must fail");
            check(java.util.Arrays.equals(oldServer, Files.readAllBytes(installDirectory.resolve("server.jar"))),
                    "failed verification preserves the previous server file");

            Files.delete(installDirectory.resolve("fabric-server-launch.jar"));
            Files.createDirectory(installDirectory.resolve("fabric-server-launch.jar"));
            expectThrows(IOException.class, () -> provisioner.install(distribution,
                    installDirectory, () -> false, ignored -> { }), "commit rejects a managed directory");
            check(java.util.Arrays.equals(oldServer, Files.readAllBytes(installDirectory.resolve("server.jar"))),
                    "failed commit restores the previous server file");
            Files.delete(installDirectory.resolve("fabric-server-launch.jar"));

            AtomicBoolean cancel = new AtomicBoolean();
            expectThrows(InterruptedIOException.class, () -> provisioner.install(distribution,
                    installDirectory, cancel::get, progress -> {
                        if (progress.artifactBytesReceived() > 0) {
                            cancel.set(true);
                        }
                    }), "cancellation interrupts provisioning");
            check(java.util.Arrays.equals(oldServer, Files.readAllBytes(installDirectory.resolve("server.jar"))),
                    "cancelled staging preserves the previous server file");
            try (var contentsAtParent = Files.list(temporary)) {
                check(contentsAtParent.noneMatch(path -> path.getFileName().toString()
                        .startsWith(".phoenix-provision-stage-")), "failed installs remove staging directories");
            }
        } finally {
            deleteTree(temporary);
        }
        System.out.println("ProvisioningHarness: PASS");
    }

    private static String fixture(long serverSize, String serverSha1, String serverSha256,
            long fabricSize, String fabricSha1, String fabricSha256) {
        return """
                {
                  "schemaVersion": 1,
                  "minecraftVersion": "26.2",
                  "minecraftDataVersion": 4903,
                  "fabricLoaderVersion": "0.19.5",
                  "fabricInstallerVersion": "1.1.2",
                  "fabricServerMainClass": "net.fabricmc.loader.impl.launch.knot.KnotServer",
                  "defaults": {"eulaAccepted": false, "optimizationMods": [], "createDefaultWorld": false},
                  "artifacts": [
                    {"id":"minecraft-server","fileName":"server.jar","url":"https://piston-data.mojang.com/v1/objects/%s/server.jar","sizeBytes":%d,"sha1":"%s","sha256":"%s","expectedSha1FromMojang":"%s"},
                    {"id":"fabric-server-launcher","fileName":"fabric-server-launch.jar","url":"https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.1.2/server/jar","sizeBytes":%d,"sha1":"%s","sha256":"%s","embeddedMinecraftVersion":"26.2","embeddedFabricLoaderVersion":"0.19.5"}
                  ]
                }
                """.formatted(serverSha1, serverSize, serverSha1, serverSha256, serverSha1,
                        fabricSize, fabricSha1, fabricSha256);
    }

    private static String digest(String algorithm, byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }

    private static void expectThrows(Class<? extends Throwable> type, ThrowingRunnable action,
            String description) throws Exception {
        try {
            action.run();
        } catch (Throwable actual) {
            if (type.isInstance(actual)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception " + actual, actual);
        }
        throw new AssertionError(description + ": no exception was thrown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
