package org.phoenix.core.world;

public record WorldImportProgress(
        int entriesProcessed,
        int entriesExpected,
        long bytesCopied,
        long bytesExpected) {
}
