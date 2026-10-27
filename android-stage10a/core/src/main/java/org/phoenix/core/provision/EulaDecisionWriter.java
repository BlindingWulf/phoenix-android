package org.phoenix.core.provision;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes the user's explicit EULA decision. Provisioner never calls this class
 * and never creates or changes eula.txt.
 */
public final class EulaDecisionWriter {
    private EulaDecisionWriter() {
    }

    /** Call only after presenting the Minecraft EULA and receiving the user's decision. */
    public static void writeUserDecision(Path serverDirectory, boolean accepted) throws IOException {
        Path directory = serverDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("server directory must be a real directory");
        }
        Path eula = directory.resolve("eula.txt");
        if (Files.isSymbolicLink(eula)) {
            throw new IOException("eula.txt may not be a symbolic link");
        }
        Path temporary = Files.createTempFile(directory, ".eula-decision-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write("# User decision recorded by Phoenix.\neula=" + accepted + "\n");
            }
            try {
                Files.move(temporary, eula, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, eula, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Returns false if no affirmative user decision has been recorded. */
    public static boolean isAccepted(Path serverDirectory) throws IOException {
        Path directory = serverDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("server directory must be a real directory");
        }
        Path eula = directory.resolve("eula.txt");
        if (!Files.exists(eula, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(eula)
                || !Files.isRegularFile(eula, LinkOption.NOFOLLOW_LINKS)
                || Files.size(eula) > 16 * 1024) {
            throw new IOException("eula.txt is not a bounded regular file");
        }
        List<String> lines = Files.readAllLines(eula);
        String decision = null;
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            if (value.startsWith("eula=")) {
                decision = value.substring("eula=".length()).trim();
            }
        }
        if (decision == null) {
            return false;
        }
        if (decision.equals("true")) {
            return true;
        }
        if (decision.equals("false")) {
            return false;
        }
        throw new IOException("eula.txt contains an invalid decision");
    }
}
