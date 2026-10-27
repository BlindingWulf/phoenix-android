package org.phoenix.core.playit;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Merges actual IDs reported by the Playit runtime into an app-owned store. */
public final class PlayitIdReconciler {
    private PlayitIdReconciler() {
    }

    public static PersistedIds reconcile(ObservedIds observed, PlayitIdentityStore store)
            throws IOException {
        if (observed == null || store == null) {
            throw new IllegalArgumentException("Observed Playit IDs and a store are required");
        }
        Optional<PersistedIds> loaded = store.load();
        PersistedIds current = loaded.orElseGet(PersistedIds::empty);
        PersistedIds merged = merge(current, observed);
        if (!loaded.isPresent() || !merged.equals(current)) {
            store.save(merged);
        }
        return merged;
    }

    /**
     * Partial telemetry preserves the last known IDs. A newly observed agent
     * replaces the previous agent and clears stale tunnel IDs until that
     * agent's authoritative tunnel snapshot arrives.
     */
    public static PersistedIds merge(PersistedIds current, ObservedIds observed) {
        if (current == null || observed == null) {
            throw new IllegalArgumentException("ID snapshots are required");
        }
        Optional<UUID> agentId = observed.agentId().isPresent()
                ? observed.agentId()
                : current.agentId();
        boolean agentChanged = current.agentId().isPresent()
                && observed.agentId().isPresent()
                && !current.agentId().equals(observed.agentId());

        Set<UUID> tunnels;
        if (observed.tunnelIdsComplete()) {
            tunnels = observed.tunnelIds();
        } else if (agentChanged) {
            tunnels = Set.of();
        } else {
            tunnels = current.tunnelIds();
        }
        return new PersistedIds(agentId, tunnels);
    }

    public static final class ObservedIds {
        private final Optional<UUID> agentId;
        private final Set<UUID> tunnelIds;
        private final boolean tunnelIdsComplete;

        public ObservedIds(Optional<UUID> agentId, Set<UUID> tunnelIds, boolean tunnelIdsComplete) {
            if (agentId == null || tunnelIds == null) {
                throw new IllegalArgumentException("ID values are required");
            }
            this.agentId = agentId;
            this.tunnelIds = Set.copyOf(tunnelIds);
            this.tunnelIdsComplete = tunnelIdsComplete;
        }

        public Optional<UUID> agentId() {
            return agentId;
        }

        public Set<UUID> tunnelIds() {
            return tunnelIds;
        }

        public boolean tunnelIdsComplete() {
            return tunnelIdsComplete;
        }
    }

    public static final class PersistedIds {
        private final Optional<UUID> agentId;
        private final Set<UUID> tunnelIds;

        public PersistedIds(Optional<UUID> agentId, Set<UUID> tunnelIds) {
            if (agentId == null || tunnelIds == null) {
                throw new IllegalArgumentException("ID values are required");
            }
            this.agentId = agentId;
            this.tunnelIds = Set.copyOf(tunnelIds);
        }

        public static PersistedIds empty() {
            return new PersistedIds(Optional.empty(), Set.of());
        }

        public Optional<UUID> agentId() {
            return agentId;
        }

        public Set<UUID> tunnelIds() {
            return tunnelIds;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PersistedIds)) {
                return false;
            }
            PersistedIds ids = (PersistedIds) other;
            return agentId.equals(ids.agentId) && tunnelIds.equals(ids.tunnelIds);
        }

        @Override
        public int hashCode() {
            return 31 * agentId.hashCode() + tunnelIds.hashCode();
        }

        @Override
        public String toString() {
            return "PlayitPersistedIds{agentId=" + agentId.orElse(null)
                    + ", tunnelIds=" + tunnelIds + "}";
        }
    }
}
