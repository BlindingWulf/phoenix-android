package org.phoenix.core.world;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Plain Java main/assert checks for world archive safety and transaction behavior. */
public final class WorldImporterHarness {
    private WorldImporterHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("phoenix-world-harness-");
        try {
            Path worlds = Files.createDirectory(temporary.resolve("worlds"));
            Path target = worlds.resolve("ActiveWorld");
            Files.createDirectory(target);
            Files.writeString(target.resolve("old-world-marker.txt"), "keep me on failure");
            Path validZip = temporary.resolve("valid-world.zip");
            writeZip(validZip, List.of(
                    new Entry("Upload/level.dat", validLevelDat()),
                    new Entry("Upload/region/r.0.0.mca", new byte[] {1, 2, 3, 4})));
            AtomicBoolean stopped = new AtomicBoolean(true);
            int[] guardCalls = {0};
            WorldImportResult result = new WorldImporter().importZip(validZip, target,
                    () -> {
                        guardCalls[0]++;
                        if (!stopped.get()) {
                            throw new IOException("server is running");
                        }
                    }, () -> false, ignored -> { });
            check(result.worldDirectory().equals(target), "import result target");
            check(result.previousWorldBackup() != null, "replacement reports the prior world backup path");
            check(Files.readString(result.previousWorldBackup().resolve("old-world-marker.txt"))
                    .equals("keep me on failure"), "successful replacement retains the old world marker");
            check(guardCalls[0] == 2, "stopped guard runs before extraction and before commit");
            check(Files.isRegularFile(target.resolve("level.dat")), "nested world folder is flattened");
            check(Files.isRegularFile(target.resolve("region/r.0.0.mca")), "world data extracted");
            check(!Files.exists(target.resolve("old-world-marker.txt")), "successful replacement committed");

            Path newTarget = worlds.resolve("FirstWorld");
            WorldImportResult firstImport = new WorldImporter().importZip(validZip, newTarget,
                    () -> { }, () -> false, ignored -> { });
            check(firstImport.previousWorldBackup() == null,
                    "first import reports no previous-world backup");

            Files.writeString(target.resolve("old-world-marker.txt"), "preserve on mismatch");
            Path wrongDataVersion = temporary.resolve("wrong-data-version.zip");
            writeZip(wrongDataVersion, List.of(new Entry("level.dat", validLevelDat(4902))));
            expectThrows(IOException.class, () -> new WorldImporter().importZip(wrongDataVersion,
                    target, () -> { }, () -> false, ignored -> { }),
                    "world with a non-26.2 DataVersion rejected");
            check(Files.readString(target.resolve("old-world-marker.txt"))
                    .equals("preserve on mismatch"), "DataVersion mismatch preserves the selected world");

            Path missingLevel = temporary.resolve("missing-level.zip");
            writeZip(missingLevel, List.of(new Entry("readme.txt", new byte[] {1, 2, 3})));
            Files.writeString(target.resolve("old-world-marker.txt"), "preserved");
            expectThrows(IOException.class, () -> new WorldImporter().importZip(missingLevel,
                    target, () -> { }, () -> false, ignored -> { }), "missing level.dat rejected");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "missing level.dat preserves existing world");

            Path badNbt = temporary.resolve("bad-level.zip");
            writeZip(badNbt, List.of(new Entry("level.dat", new byte[] {0, 1, 2, 3})));
            expectThrows(IOException.class, () -> new WorldImporter().importZip(badNbt,
                    target, () -> { }, () -> false, ignored -> { }), "invalid NBT rejected");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "invalid level.dat preserves existing world");

            Path traversal = temporary.resolve("traversal.zip");
            writeZip(traversal, List.of(
                    new Entry("level.dat", validLevelDat()),
                    new Entry("../escaped.txt", new byte[] {9})));
            expectThrows(IOException.class, () -> new WorldImporter().importZip(traversal,
                    target, () -> { }, () -> false, ignored -> { }), "parent traversal rejected");
            check(!Files.exists(temporary.resolve("escaped.txt")), "traversal did not write outside staging");

            Path symlink = temporary.resolve("symlink.zip");
            writeZip(symlink, List.of(
                    new Entry("level.dat", validLevelDat()),
                    new Entry("link", "../outside".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            patchAsUnixSymlink(symlink, "link");
            expectThrows(IOException.class, () -> new WorldImporter().importZip(symlink,
                    target, () -> { }, () -> false, ignored -> { }), "ZIP symlink rejected");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "symlink rejection preserves existing world");

            Path bomb = temporary.resolve("ratio.zip");
            byte[] repetitive = new byte[8192];
            java.util.Arrays.fill(repetitive, (byte) 'A');
            writeZip(bomb, List.of(new Entry("level.dat", validLevelDat()),
                    new Entry("region/repetitive.bin", repetitive)));
            WorldImportLimits strictRatio = new WorldImportLimits(
                    1024 * 1024, 100, 64 * 1024, 32 * 1024, 2, 4096,
                    16 * 1024, 8 * 1024);
            expectThrows(IOException.class, () -> new WorldImporter(strictRatio).importZip(bomb,
                    target, () -> { }, () -> false, ignored -> { }), "compression ratio bound enforced");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "ratio rejection preserves existing world");

            Path cancellable = temporary.resolve("cancel.zip");
            byte[] randomData = new byte[256 * 1024];
            new java.util.Random(7).nextBytes(randomData);
            writeZip(cancellable, List.of(new Entry("level.dat", validLevelDat()),
                    new Entry("region/large.bin", randomData)));
            AtomicBoolean cancel = new AtomicBoolean();
            expectThrows(InterruptedIOException.class, () -> new WorldImporter().importZip(cancellable,
                    target, () -> { }, cancel::get, update -> {
                        if (update.bytesCopied() > 0) {
                            cancel.set(true);
                        }
                    }), "cancellation interrupts extraction");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "cancelled import preserves existing world");

            stopped.set(false);
            expectThrows(IOException.class, () -> new WorldImporter().importZip(validZip,
                    target, () -> {
                        if (!stopped.get()) {
                            throw new IOException("server is running");
                        }
                    }, () -> false, ignored -> { }), "running server mutation rejected");
            check(Files.readString(target.resolve("old-world-marker.txt")).equals("preserved"),
                    "running-server rejection preserves existing world");
        } finally {
            deleteTree(temporary);
        }
        System.out.println("WorldImporterHarness: PASS");
    }

    private static byte[] validLevelDat() throws IOException {
        return validLevelDat(WorldImporter.MINECRAFT_26_2_DATA_VERSION);
    }

    private static byte[] validLevelDat(int dataVersion) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10); // root compound
            output.writeUTF("");
            output.writeByte(10); // Data compound
            output.writeUTF("Data");
            output.writeByte(3); // DataVersion int
            output.writeUTF("DataVersion");
            output.writeInt(dataVersion);
            output.writeByte(0); // end Data
            output.writeByte(0); // end root
        }
        return bytes.toByteArray();
    }

    private static void writeZip(Path output, List<Entry> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.bytes());
                zip.closeEntry();
            }
        }
    }

    private static void patchAsUnixSymlink(Path zipFile, String entryName) throws IOException {
        byte[] bytes = Files.readAllBytes(zipFile);
        byte[] wanted = entryName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean patched = false;
        for (int at = 0; at + 46 + wanted.length <= bytes.length; at++) {
            if (bytes[at] == 0x50 && bytes[at + 1] == 0x4b
                    && bytes[at + 2] == 0x01 && bytes[at + 3] == 0x02
                    && u16(bytes, at + 28) == wanted.length) {
                boolean match = true;
                for (int index = 0; index < wanted.length; index++) {
                    if (bytes[at + 46 + index] != wanted[index]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    bytes[at + 4] = 20;
                    bytes[at + 5] = 3;
                    bytes[at + 38] = 0;
                    bytes[at + 39] = 0;
                    bytes[at + 40] = (byte) 0xff;
                    bytes[at + 41] = (byte) 0xa1;
                    patched = true;
                    break;
                }
            }
        }
        check(patched, "find ZIP central record to mark as symlink");
        Files.write(zipFile, bytes);
    }

    private static int u16(byte[] bytes, int at) {
        return Byte.toUnsignedInt(bytes[at]) | (Byte.toUnsignedInt(bytes[at + 1]) << 8);
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

    private record Entry(String name, byte[] bytes) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
