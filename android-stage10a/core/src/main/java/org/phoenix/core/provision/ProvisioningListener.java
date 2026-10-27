package org.phoenix.core.provision;

@FunctionalInterface
public interface ProvisioningListener {
    void onProgress(ProvisioningProgress progress);
}
