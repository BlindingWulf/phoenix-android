package org.phoenix.core.world;

/** Bounds applied before and during ZIP extraction. */
public record WorldImportLimits(
        long maxArchiveBytes,
        int maxEntries,
        long maxTotalUncompressedBytes,
        long maxEntryUncompressedBytes,
        int maxCompressionRatio,
        int maxPathCharacters,
        long maxLevelDatCompressedBytes,
        long maxLevelDatExpandedBytes) {

    public static WorldImportLimits defaults() {
        return new WorldImportLimits(
                2L * 1024 * 1024 * 1024,
                20_000,
                1024L * 1024 * 1024,
                512L * 1024 * 1024,
                200,
                4096,
                64L * 1024 * 1024,
                32L * 1024 * 1024);
    }

    public WorldImportLimits {
        if (maxArchiveBytes <= 0 || maxEntries <= 0 || maxTotalUncompressedBytes <= 0
                || maxEntryUncompressedBytes <= 0 || maxCompressionRatio <= 0
                || maxPathCharacters <= 0 || maxLevelDatCompressedBytes <= 0
                || maxLevelDatExpandedBytes <= 0) {
            throw new IllegalArgumentException("world import limits must all be positive");
        }
        if (maxEntryUncompressedBytes > maxTotalUncompressedBytes
                || maxLevelDatExpandedBytes > maxTotalUncompressedBytes) {
            throw new IllegalArgumentException("per-file bounds may not exceed total expansion bound");
        }
    }
}
