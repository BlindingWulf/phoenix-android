package org.phoenix.core.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safely imports a level.dat-bearing ZIP as a stopped-server world replacement. */
public final class WorldImporter {
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final Set<String> WINDOWS_RESERVED_NAMES = reservedNames();
    /** DataVersion read from the official Minecraft 26.2 server bundle's version.json. */
    public static final int MINECRAFT_26_2_DATA_VERSION = 4903;

    private final WorldImportLimits limits;
    private final int expectedDataVersion;

    public WorldImporter() {
        this(WorldImportLimits.defaults(), MINECRAFT_26_2_DATA_VERSION);
    }

    public WorldImporter(WorldImportLimits limits) {
        this(limits, MINECRAFT_26_2_DATA_VERSION);
    }

    public WorldImporter(int expectedDataVersion) {
        this(WorldImportLimits.defaults(), expectedDataVersion);
    }

    public WorldImporter(WorldImportLimits limits, int expectedDataVersion) {
        this.limits = Objects.requireNonNull(limits, "limits");
        if (expectedDataVersion <= 0) {
            throw new IllegalArgumentException("expectedDataVersion must be positive");
        }
        this.expectedDataVersion = expectedDataVersion;
    }

    public WorldImportResult importZip(Path zipFile, Path destination,
            ServerStoppedGuard stoppedGuard, BooleanSupplier cancelled,
            WorldImportListener listener) throws IOException {
        Objects.requireNonNull(zipFile, "zipFile");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(stoppedGuard, "stoppedGuard");
        Objects.requireNonNull(cancelled, "cancelled");
        WorldImportListener progress = listener == null ? ignored -> { } : listener;

        stoppedGuard.assertServerStopped();
        checkCancelled(cancelled);
        Path archive = zipFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(archive)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("world archive must be a regular file, not a symbolic link");
        }
        archive = archive.toRealPath();
        long archiveSize = Files.size(archive);
        if (archiveSize <= 0 || archiveSize > limits.maxArchiveBytes()) {
            throw new IOException("world archive exceeds the supported file size");
        }

        Path target = resolveTarget(destination);
        rejectUnsafeDestination(target);
        Map<String, ZipCentralDirectory.EntryInfo> central =
                ZipCentralDirectory.read(archive, limits.maxEntries());
        Inspection inspection = inspectEntries(central, archiveSize);

        Path parent = target.getParent();
        Path staging = Files.createTempDirectory(parent, ".phoenix-world-stage-");
        try {
            Path payload = staging.resolve("payload");
            Files.createDirectory(payload);
            try (ZipFile zip = new ZipFile(archive.toFile(), Charset.forName("IBM437"))) {
                int entriesSeen = 0;
                long bytesCopied = 0;
                progress.onProgress(new WorldImportProgress(0, central.size(), 0,
                        inspection.totalUncompressedBytes()));
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    checkCancelled(cancelled);
                    ZipEntry entry = entries.nextElement();
                    entriesSeen++;
                    ZipCentralDirectory.EntryInfo info = central.get(entry.getName());
                    if (info == null || info.directory() != entry.isDirectory()
                            || info.method() != entry.getMethod()
                            || info.compressedSize() != entry.getCompressedSize()
                            || info.uncompressedSize() != entry.getSize()
                            || info.crc() != entry.getCrc()) {
                        throw new IOException("ZIP library entry differs from its central directory");
                    }
                    String cleanName = stripDirectorySuffix(entry.getName(), entry.isDirectory());
                    Path outputPath = resolveEntry(payload, cleanName);
                    if (entry.isDirectory()) {
                        Files.createDirectories(outputPath);
                        rejectSymlinkParents(payload, outputPath);
                    } else {
                        Path outputParent = outputPath.getParent();
                        Files.createDirectories(outputParent);
                        rejectSymlinkParents(payload, outputParent);
                        try (InputStream input = zip.getInputStream(entry);
                                var output = Files.newOutputStream(outputPath,
                                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            CRC32 crc = new CRC32();
                            long entryCopied = 0;
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                checkCancelled(cancelled);
                                entryCopied = checkedAdd(entryCopied, read, "ZIP entry byte count overflow");
                                bytesCopied = checkedAdd(bytesCopied, read, "ZIP total byte count overflow");
                                if (entryCopied > info.uncompressedSize()
                                        || entryCopied > limits.maxEntryUncompressedBytes()
                                        || bytesCopied > limits.maxTotalUncompressedBytes()) {
                                    throw new IOException("world archive expanded beyond its declared bounds");
                                }
                                output.write(buffer, 0, read);
                                crc.update(buffer, 0, read);
                                progress.onProgress(new WorldImportProgress(entriesSeen - 1,
                                        central.size(), bytesCopied,
                                        inspection.totalUncompressedBytes()));
                            }
                            if (entryCopied != info.uncompressedSize() || crc.getValue() != info.crc()) {
                                throw new IOException("world ZIP entry size or CRC does not match its directory");
                            }
                        } catch (FileAlreadyExistsException exception) {
                            throw new IOException("world ZIP contains colliding paths", exception);
                        }
                    }
                    progress.onProgress(new WorldImportProgress(entriesSeen, central.size(),
                            bytesCopied, inspection.totalUncompressedBytes()));
                }
                if (entriesSeen != central.size()) {
                    throw new IOException("world ZIP entry count differs from its central directory");
                }
                if (bytesCopied != inspection.totalUncompressedBytes()) {
                    throw new IOException("world ZIP total size differs from its central directory");
                }
            }

            Path importedRoot = inspection.rootDirectory().isEmpty()
                    ? payload : payload.resolve(inspection.rootDirectory());
            int actualDataVersion = NbtLevelDatVerifier.verify(importedRoot.resolve("level.dat"),
                    limits.maxLevelDatCompressedBytes(), limits.maxLevelDatExpandedBytes());
            if (actualDataVersion != expectedDataVersion) {
                throw new IOException("world DataVersion " + actualDataVersion
                        + " does not match the pinned Minecraft DataVersion " + expectedDataVersion);
            }
            checkCancelled(cancelled);
            stoppedGuard.assertServerStopped();
            Path previousWorldBackup = commitWorld(importedRoot, target);
            return new WorldImportResult(target, central.size(),
                    inspection.totalUncompressedBytes(), previousWorldBackup);
        } finally {
            deleteTreeQuietly(staging);
        }
    }

    private Inspection inspectEntries(Map<String, ZipCentralDirectory.EntryInfo> entries,
            long archiveSize) throws IOException {
        long total = 0;
        long compressedTotal = 0;
        Map<String, ZipCentralDirectory.EntryInfo> normalized = new HashMap<>();
        Set<String> folded = new HashSet<>();
        Set<String> files = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<String> topLevel = new HashSet<>();

        for (ZipCentralDirectory.EntryInfo entry : entries.values()) {
            String name = stripDirectorySuffix(entry.name(), entry.directory());
            validateRelativeName(name);
            String nfc = Normalizer.normalize(name, Normalizer.Form.NFC);
            String foldedName = nfc.toLowerCase(Locale.ROOT);
            if (!folded.add(foldedName)) {
                throw new IOException("world ZIP contains case or Unicode-colliding paths");
            }
            if (normalized.put(name, entry) != null) {
                throw new IOException("world ZIP contains duplicate normalized paths");
            }
            paths.add(name);
            if (!entry.directory()) {
                files.add(name);
                if (entry.uncompressedSize() > limits.maxEntryUncompressedBytes()) {
                    throw new IOException("world ZIP entry exceeds the per-file size limit");
                }
                if (entry.compressedSize() == 0 && entry.uncompressedSize() > 0) {
                    throw new IOException("world ZIP has a non-empty entry with zero compressed size");
                }
                if (entry.compressedSize() > 0
                        && entry.uncompressedSize() > entry.compressedSize() * limits.maxCompressionRatio()) {
                    throw new IOException("world ZIP compression ratio exceeds the supported limit");
                }
            } else if (entry.uncompressedSize() != 0 || entry.compressedSize() != 0) {
                throw new IOException("world ZIP directory entries must not contain file data");
            }
            total = checkedAdd(total, entry.uncompressedSize(), "world ZIP total size overflow");
            compressedTotal = checkedAdd(compressedTotal, entry.compressedSize(),
                    "world ZIP compressed size overflow");
            if (total > limits.maxTotalUncompressedBytes()) {
                throw new IOException("world ZIP total expansion exceeds the supported limit");
            }
            String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
            topLevel.add(first);
        }
        if (compressedTotal > archiveSize) {
            throw new IOException("world ZIP compressed sizes exceed archive length");
        }

        for (String file : files) {
            int slash = file.indexOf('/');
            while (slash >= 0) {
                String ancestor = file.substring(0, slash);
                if (files.contains(ancestor)) {
                    throw new IOException("world ZIP has a file used as a parent directory");
                }
                slash = file.indexOf('/', slash + 1);
            }
        }

        boolean directLevel = files.contains("level.dat");
        String rootDirectory = "";
        if (!directLevel) {
            if (topLevel.size() != 1) {
                throw new IOException("world ZIP must contain level.dat at its root or in one root folder");
            }
            rootDirectory = topLevel.iterator().next();
            if (!files.contains(rootDirectory + "/level.dat")) {
                throw new IOException("world ZIP is missing a root level.dat file");
            }
        }
        return new Inspection(total, rootDirectory);
    }

    private void validateRelativeName(String name) throws IOException {
        if (name.isEmpty() || name.length() > limits.maxPathCharacters()
                || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf(':') >= 0 || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("world ZIP contains an unsafe absolute or long path");
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IOException("world ZIP contains an unsafe path segment");
            }
            String upper = segment.toUpperCase(Locale.ROOT);
            int extension = upper.indexOf('.');
            String base = extension >= 0 ? upper.substring(0, extension) : upper;
            if (WINDOWS_RESERVED_NAMES.contains(base)) {
                throw new IOException("world ZIP contains a reserved platform filename");
            }
        }
    }

    private Path resolveTarget(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (absolute.getParent() == null || absolute.getFileName() == null) {
            throw new IOException("world destination must have a parent and a name");
        }
        Path leaf = absolute.getFileName();
        String leafText = leaf.toString();
        if (leafText.trim().isEmpty() || leafText.equals(".") || leafText.equals("..")
                || leafText.contains(":") || leafText.contains("/") || leafText.contains("\\")) {
            throw new IOException("world destination name is unsafe");
        }
        Path parent = absolute.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("world destination parent must be an existing real directory");
        }
        return parent.toRealPath().resolve(leaf);
    }

    private static void rejectUnsafeDestination(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("world destination may not be a symbolic link");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("world destination exists and is not a directory");
        }
    }

    private static Path resolveEntry(Path payload, String cleanName) throws IOException {
        Path result = payload;
        for (String segment : cleanName.split("/")) {
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (!result.startsWith(payload)) {
            throw new IOException("world archive path escapes the staging directory");
        }
        return result;
    }

    private static String stripDirectorySuffix(String name, boolean directory) throws IOException {
        if (!directory) {
            if (name.endsWith("/")) {
                throw new IOException("file ZIP entry has a directory suffix");
            }
            return name;
        }
        if (!name.endsWith("/") || name.length() == 1) {
            throw new IOException("directory ZIP entry has an invalid path");
        }
        return name.substring(0, name.length() - 1);
    }

    private static void rejectSymlinkParents(Path root, Path path) throws IOException {
        Path current = path;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symbolic link appeared in the staged world tree");
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
    }

    private static Path commitWorld(Path importedRoot, Path target) throws IOException {
        rejectUnsafeDestination(target);
        Path backup = Files.createTempDirectory(target.getParent(), ".phoenix-world-backup-");
        Path oldWorld = backup.resolve("old-world");
        Path rejectedNewWorld = backup.resolve("rejected-new-world");
        boolean oldMoved = false;
        boolean newMoved = false;
        boolean preserveBackup = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                move(target, oldWorld);
                oldMoved = true;
            }
            move(importedRoot, target);
            newMoved = true;
            if (oldMoved) {
                preserveBackup = true;
                return oldWorld;
            }
            return null;
        } catch (IOException failure) {
            IOException rollbackFailure = null;
            if (newMoved) {
                try {
                    move(target, rejectedNewWorld);
                } catch (IOException exception) {
                    rollbackFailure = exception;
                }
            }
            if (oldMoved) {
                try {
                    move(oldWorld, target);
                } catch (IOException exception) {
                    if (rollbackFailure == null) {
                        rollbackFailure = exception;
                    } else {
                        rollbackFailure.addSuppressed(exception);
                    }
                }
            }
            if (rollbackFailure != null) {
                preserveBackup = true;
                failure.addSuppressed(new IOException(
                        "rollback could not restore the previous world; recovery files remain at " + backup,
                        rollbackFailure));
            }
            throw failure;
        } finally {
            if (!preserveBackup) {
                deleteTreeQuietly(backup);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static long checkedAdd(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("world import cancelled");
        }
    }

    private static Set<String> reservedNames() {
        Set<String> names = new HashSet<>();
        Collections.addAll(names, "CON", "PRN", "AUX", "NUL");
        for (int index = 1; index <= 9; index++) {
            names.add("COM" + index);
            names.add("LPT" + index);
        }
        return Collections.unmodifiableSet(names);
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
            // Best effort staging cleanup.
        }
    }

    private record Inspection(long totalUncompressedBytes, String rootDirectory) {
    }
}
