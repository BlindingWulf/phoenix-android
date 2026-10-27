package org.phoenix.core.playit;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence adapter for identifiers assigned and reported by the native
 * Playit agent. Android should back this with its protected app storage.
 */
public interface PlayitIdentityStore {
    Optional<PlayitIdReconciler.PersistedIds> load() throws IOException;

    void save(PlayitIdReconciler.PersistedIds ids) throws IOException;

    /** A partial update when the agent has not yet reported a full tunnel list. */
    static PlayitIdReconciler.ObservedIds partial(UUID agentId, Set<UUID> tunnelIds) {
        return new PlayitIdReconciler.ObservedIds(Optional.ofNullable(agentId), tunnelIds, false);
    }

    /** A complete runtime snapshot; an empty tunnel set is authoritative. */
    static PlayitIdReconciler.ObservedIds complete(UUID agentId, Set<UUID> tunnelIds) {
        return new PlayitIdReconciler.ObservedIds(Optional.ofNullable(agentId), tunnelIds, true);
    }
}
