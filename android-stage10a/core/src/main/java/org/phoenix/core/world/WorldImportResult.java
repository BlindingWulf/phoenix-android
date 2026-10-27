package org.phoenix.core.world;

import java.nio.file.Path;

/** Outcome of an import; the previous world is retained when a destination was replaced.
 * @param previousWorldBackup exact prior-world directory, or null if no destination existed
 */
public record WorldImportResult(Path worldDirectory, int entries, long uncompressedBytes,
        Path previousWorldBackup) {
    /** Kept for source compatibility with callers that construct result values directly. */
    public WorldImportResult(Path worldDirectory, int entries, long uncompressedBytes) {
        this(worldDirectory, entries, uncompressedBytes, null);
    }
}
