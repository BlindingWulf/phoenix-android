package org.phoenix.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Original code. Materialize trusted data and symlinks to installer-owned native code. */
public final class RuntimeLayout {
    public record NativeEntry(String runtimePath, String installedName, String sha256) {}
    private RuntimeLayout() {}
    public static void unpackData(InputStream input, Path javaHome, Map<String, String> expectedHashes) throws IOException {
        Files.createDirectories(javaHome);
        Path root = javaHome.toAbsolutePath().normalize();
        Map<String, String> remaining = new HashMap<>(expectedHashes);
        long total = 0;
        try (var zip = new ZipInputStream(input)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || target.equals(root) || entry.getName().contains("\\")) throw new IOException("Invalid runtime data path");
                String expected = remaining.remove(entry.getName());
                if (expected == null || entry.isDirectory() || entry.getName().endsWith(".so")) throw new IOException("Unexpected runtime entry");
                safeParents(root, target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), ".phoenix-runtime-", ".partial");
                MessageDigest digest = sha256();
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[32768];
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        total += read;
                        if (total > 512L * 1024 * 1024) throw new IOException("Runtime data exceeds size limit");
                        digest.update(buffer, 0, read); output.write(buffer, 0, read);
                    }
                }
                if (!hex(digest.digest()).equals(expected)) { Files.deleteIfExists(temporary); throw new IOException("Runtime data hash mismatch: " + entry.getName()); }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!remaining.isEmpty()) throw new IOException("Missing runtime data files");
    }
    public static void linkNative(Path javaHome, Path installedNativeDir, Iterable<NativeEntry> entries) throws IOException {
        Path root = javaHome.toAbsolutePath().normalize();
        Path nativeRoot = installedNativeDir.toRealPath();
        for (NativeEntry entry : entries) {
            Path destination = root.resolve(entry.runtimePath()).normalize();
            Path library = nativeRoot.resolve(entry.installedName()).toRealPath();
            if (!destination.startsWith(root) || !library.getParent().equals(nativeRoot) || !entry.installedName().endsWith(".so")) throw new IOException("Invalid native path");
            if (!hash(library).equals(entry.sha256())) throw new IOException("Native hash mismatch: " + entry.installedName());
            safeParents(root, destination.getParent());
            if (Files.exists(destination) || Files.isSymbolicLink(destination)) Files.delete(destination);
            Files.createSymbolicLink(destination, library);
        }
    }
    public static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new AssertionError(e); } }
    private static void safeParents(Path root, Path parent) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Runtime root cannot be a symlink");
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Runtime parent cannot be a symlink");
            Files.createDirectories(current);
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
