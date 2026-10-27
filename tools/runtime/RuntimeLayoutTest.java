import org.phoenix.runtime.RuntimeLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RuntimeLayoutTest {
    private static byte[] zip(String name, byte[] data) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    private static void rejected(byte[] zip, Path directory, Map<String,String> hashes) throws Exception {
        try { RuntimeLayout.unpackData(new ByteArrayInputStream(zip), directory, hashes); }
        catch (IOException expected) { return; }
        throw new AssertionError("Unsafe/mismatched input accepted");
    }
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("phoenix-layout-test");
        byte[] data = "verified".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        RuntimeLayout.unpackData(new ByteArrayInputStream(zip("lib/modules", data)), root.resolve("valid"), Map.of("lib/modules", hash));
        if (!Files.readString(root.resolve("valid/lib/modules")).equals("verified")) throw new AssertionError("Data mismatch");
        rejected(zip("../escape", data), root.resolve("traversal"), Map.of("../escape", hash));
        rejected(zip("lib/modules", data), root.resolve("hash"), Map.of("lib/modules", "0".repeat(64)));
        rejected(zip("lib/libjvm.so", data), root.resolve("binary"), Map.of("lib/libjvm.so", hash));
        rejected(zip("lib/modules", data), root.resolve("missing"), Map.of("lib/modules", hash, "release", hash));
        System.out.println("RuntimeLayoutTest PASS data, traversal rejection, integrity rejection, native ELF rejection, missing entry rejection");
    }
}
