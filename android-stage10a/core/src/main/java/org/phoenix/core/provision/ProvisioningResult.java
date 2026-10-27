package org.phoenix.core.provision;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ProvisioningResult(
        Path installationDirectory,
        String minecraftVersion,
        String fabricLoaderVersion,
        List<String> installedFiles) {
    public ProvisioningResult {
        installedFiles = Collections.unmodifiableList(new ArrayList<>(installedFiles));
    }
}
