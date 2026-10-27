package org.phoenix.core.playit;

/** Platform adapter contract for protecting a Playit agent key at rest. */
@FunctionalInterface
public interface PlayitCredentialStore {
    /** Store a temporary key copy in app-private, encrypted storage. */
    void saveAgentKey(char[] agentKey) throws Exception;
}
