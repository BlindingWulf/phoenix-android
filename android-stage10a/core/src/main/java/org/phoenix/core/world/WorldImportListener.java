package org.phoenix.core.world;

@FunctionalInterface
public interface WorldImportListener {
    void onProgress(WorldImportProgress progress);
}
