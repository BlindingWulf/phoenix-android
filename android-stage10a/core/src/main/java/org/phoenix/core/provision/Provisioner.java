package org.phoenix.core.provision;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.BufferedWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Downloads an exact pinned distribution with streaming bounds and checksums,
 * then replaces only its managed files as a rollback-capable transaction.
 */
public final class Provisioner {
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    /** Additional room for install metadata, filesystem overhead, and safe staging. */
    public static final long REQUIRED_FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L;

    @FunctionalInterface
    public interface ArtifactSource {
        InputStream open(URI uri, long expectedSize) throws IOException;
    }

    @FunctionalInterface
    public interface SpaceProbe {
        long usableBytes(Path existingParentDirectory) throws IOException;
    }

    private final ArtifactSource source;
    private final SpaceProbe spaceProbe;

    public Provisioner() {
        this(Provisioner::openOfficialHttps, Provisioner::usableSpace);
    }

    /** Injection point for a controlled test stream. Production callers use the default constructor. */
    public Provisioner(ArtifactSource source) {
        this(source, Provisioner::usableSpace);
    }

    /** Injection points for bounded test streams and deterministic free-space preflight checks. */
    public Provisioner(ArtifactSource source, SpaceProbe spaceProbe) {
        this.source = Objects.requireNonNull(source, "source");
        this.spaceProbe = Objects.requireNonNull(spaceProbe, "spaceProbe");
    }

    public ProvisioningResult install(PinnedDistribution distribution, Path installationDirectory,
            BooleanSupplier cancelled, ProvisioningListener listener) throws IOException {
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(installationDirectory, "installationDirectory");
        Objects.requireNonNull(cancelled, "cancelled");
        ProvisioningListener progress = listener == null ? ignored -> { } : listener;

        Path requested = installationDirectory.toAbsolutePath().normalize();
        Path parent = requested.getParent();
        if (parent == null || requested.getFileName() == null) {
            throw new IOException("installation directory must have a parent and a name");
        }
        Files.createDirectories(parent);
        parent = parent.toRealPath();
        Path target = parent.resolve(requested.getFileName());
        rejectUnsafeTarget(target);

        long totalExpected = 0;
        for (Artifact artifact : distribution.artifacts()) {
            try {
                totalExpected = Math.addExact(totalExpected, artifact.sizeBytes());
            } catch (ArithmeticException exception) {
                throw new IOException("total artifact size exceeds supported range", exception);
            }
        }
        long requiredSpace;
        try {
            requiredSpace = Math.addExact(totalExpected, REQUIRED_FREE_SPACE_RESERVE_BYTES);
        } catch (ArithmeticException exception) {
            throw new IOException("required installation space exceeds supported range", exception);
        }
        long availableSpace = spaceProbe.usableBytes(parent);
        if (availableSpace < 0) {
            throw new IOException("free-space probe returned a negative result");
        }
        if (availableSpace < requiredSpace) {
            throw new IOException("insufficient free space at " + parent + ": requires "
                    + requiredSpace + " bytes including reserve, available " + availableSpace);
        }

        Path staging = Files.createTempDirectory(parent, ".phoenix-provision-stage-");
        boolean committed = false;
        try {
            long totalReceived = 0;
            List<String> stagedNames = new ArrayList<>();
            for (Artifact artifact : distribution.artifacts()) {
                checkCancelled(cancelled);
                Path stagedPart = staging.resolve(artifact.fileName() + ".part");
                Path stagedFile = staging.resolve(artifact.fileName());
                MessageDigest sha256 = digest("SHA-256");
                MessageDigest sha1 = artifact.sha1() == null ? null : digest("SHA-1");
                long artifactReceived = 0;
                progress.onProgress(new ProvisioningProgress(
                        artifact.id(), 0, artifact.sizeBytes(), totalReceived, totalExpected));
                try (InputStream input = source.open(artifact.uri(), artifact.sizeBytes());
                        var output = Files.newOutputStream(stagedPart)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        artifactReceived = Math.addExact(artifactReceived, read);
                        totalReceived = Math.addExact(totalReceived, read);
                        if (artifactReceived > artifact.sizeBytes()) {
                            throw new IOException("artifact exceeded pinned size: " + artifact.id());
                        }
                        output.write(buffer, 0, read);
                        sha256.update(buffer, 0, read);
                        if (sha1 != null) {
                            sha1.update(buffer, 0, read);
                        }
                        progress.onProgress(new ProvisioningProgress(
                                artifact.id(), artifactReceived, artifact.sizeBytes(),
                                totalReceived, totalExpected));
                    }
                } catch (ArithmeticException exception) {
                    throw new IOException("artifact byte count overflow", exception);
                }
                if (artifactReceived != artifact.sizeBytes()) {
                    throw new IOException("artifact size mismatch for " + artifact.id()
                            + ": expected " + artifact.sizeBytes() + ", got " + artifactReceived);
                }
                String actualSha256 = toHex(sha256.digest());
                if (!actualSha256.equals(artifact.sha256())) {
                    throw new IOException("SHA-256 mismatch for " + artifact.id());
                }
                if (sha1 != null) {
                    String actualSha1 = toHex(sha1.digest());
                    if (!actualSha1.equals(artifact.sha1())) {
                        throw new IOException("SHA-1 mismatch for " + artifact.id());
                    }
                }
                move(stagedPart, stagedFile);
                stagedNames.add(artifact.fileName());
            }

            checkCancelled(cancelled);
            String stampName = "phoenix-install.json";
            try (BufferedWriter writer = Files.newBufferedWriter(staging.resolve(stampName),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(createStamp(distribution));
            }
            stagedNames.add(stampName);
            commitManagedFiles(staging, target, stagedNames);
            committed = true;
            return new ProvisioningResult(target, distribution.minecraftVersion(),
                    distribution.fabricLoaderVersion(), stagedNames);
        } finally {
            deleteTreeQuietly(staging);
            if (!committed) {
                removeIfEmpty(target);
            }
        }
    }

    private static InputStream openOfficialHttps(URI uri, long expectedSize) throws IOException {
        URL current = uri.toURL();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            try {
                requireOfficialHttps(current.toURI());
            } catch (java.net.URISyntaxException exception) {
                throw new IOException("invalid artifact redirect URL", exception);
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("User-Agent", "Phoenix-Stage10A/1.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException exception) {
                connection.disconnect();
                throw exception;
            }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == MAX_REDIRECTS) {
                    throw new IOException("invalid or excessive artifact redirect");
                }
                current = new URL(current, location);
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("artifact server returned HTTP " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > expectedSize) {
                connection.disconnect();
                throw new IOException("artifact Content-Length exceeds pinned size");
            }
            try {
                InputStream input = connection.getInputStream();
                return new FilterInputStream(input) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            connection.disconnect();
                        }
                    }
                };
            } catch (IOException exception) {
                connection.disconnect();
                throw exception;
            }
        }
        throw new IOException("artifact redirect limit exceeded");
    }

    private static void requireOfficialHttps(URI uri) throws IOException {
        String hostValue = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || hostValue == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("artifact redirect is not an allowed HTTPS URL");
        }
        String host = hostValue.toLowerCase(java.util.Locale.ROOT);
        boolean official = host.equals("mojang.com") || host.endsWith(".mojang.com")
                || host.equals("minecraft.net") || host.endsWith(".minecraft.net")
                || host.equals("fabricmc.net") || host.endsWith(".fabricmc.net");
        if (!official) {
            throw new IOException("artifact redirect left official Mojang/Fabric hosts");
        }
    }

    /**
     * Free space for the download. java.io.File first: Android 16 (moto g play 2026) refuses Files.getFileStore, which
     * stopped "Create server" before anything was downloaded (2026-10-12). File reads the same statfs value; it answers 0
     * when it can't, and only then is the FileStore asked.
     */
    private static long usableSpace(Path parent) throws IOException {
        long usable = parent.toFile().getUsableSpace();
        if (usable > 0) {
            return usable;
        }
        try {
            return Files.getFileStore(parent).getUsableSpace();
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            throw new IOException("Phoenix couldn't read the free space on this phone's storage", e);
        }
    }

    private static void rejectUnsafeTarget(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("installation directory may not be a symbolic link");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("installation target exists and is not a directory");
        }
    }

    private static void commitManagedFiles(Path staging, Path target, List<String> names)
            throws IOException {
        rejectUnsafeTarget(target);
        boolean targetCreated = false;
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(target);
            targetCreated = true;
        }
        Path backup = Files.createTempDirectory(target.getParent(), ".phoenix-provision-backup-");
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> installed = new ArrayList<>();
        try {
            for (String name : names) {
                Path destination = target.resolve(name);
                if (Files.isSymbolicLink(destination)) {
                    throw new IOException("managed destination may not be a symbolic link: " + name);
                }
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("managed destination is not a regular file: " + name);
                    }
                    Path saved = backup.resolve(name);
                    move(destination, saved);
                    backups.put(destination, saved);
                }
                move(staging.resolve(name), destination);
                installed.add(destination);
            }
        } catch (IOException failure) {
            IOException rollbackFailure = rollback(installed, backups);
            if (targetCreated) {
                removeIfEmpty(target);
            }
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            deleteTreeQuietly(backup);
        }
    }

    private static IOException rollback(List<Path> installed, Map<Path, Path> backups) {
        IOException failure = null;
        for (int index = installed.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(installed.get(index));
            } catch (IOException exception) {
                failure = accumulate(failure, exception);
            }
        }
        List<Map.Entry<Path, Path>> entries = new ArrayList<>(backups.entrySet());
        for (int index = entries.size() - 1; index >= 0; index--) {
            Map.Entry<Path, Path> entry = entries.get(index);
            try {
                if (Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(entry.getKey());
                }
                move(entry.getValue(), entry.getKey());
            } catch (IOException exception) {
                failure = accumulate(failure, exception);
            }
        }
        return failure;
    }

    private static IOException accumulate(IOException existing, IOException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static String createStamp(PinnedDistribution distribution) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"minecraftVersion\": \"").append(escape(distribution.minecraftVersion()))
                .append("\",\n  \"minecraftDataVersion\": ").append(distribution.minecraftDataVersion())
                .append(",\n  \"fabricLoaderVersion\": \"")
                .append(escape(distribution.fabricLoaderVersion())).append("\",\n  \"artifacts\": [\n");
        for (int index = 0; index < distribution.artifacts().size(); index++) {
            Artifact artifact = distribution.artifacts().get(index);
            json.append("    {\"id\": \"").append(escape(artifact.id()))
                    .append("\", \"fileName\": \"").append(escape(artifact.fileName()))
                    .append("\", \"sizeBytes\": ").append(artifact.sizeBytes())
                    .append(", \"sha256\": \"").append(artifact.sha256()).append("\"}");
            json.append(index + 1 == distribution.artifacts().size() ? "\n" : ",\n");
        }
        return json.append("  ],\n  \"eulaAccepted\": false,\n  \"defaultWorldCreated\": false\n}\n")
                .toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required digest unavailable: " + algorithm, exception);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("server provisioning cancelled");
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void removeIfEmpty(Path path) {
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (var contents = Files.list(path)) {
                    if (contents.findAny().isEmpty()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException ignored) {
            // Best effort only while preserving the primary installation result.
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Cleanup failure must not replace the download or commit result.
        }
    }
}
