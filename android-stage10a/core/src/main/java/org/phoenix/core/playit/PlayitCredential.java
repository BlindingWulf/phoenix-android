package org.phoenix.core.playit;

import java.util.Arrays;

/** A redacted, explicitly closeable wrapper for a Playit agent secret key. */
public final class PlayitCredential implements AutoCloseable {
    @FunctionalInterface
    public interface SecretConsumer {
        void accept(char[] secret) throws Exception;
    }

    private char[] secret;

    PlayitCredential(String secretKey) {
        this.secret = secretKey.toCharArray();
    }

    private PlayitCredential(char[] secretKey) {
        this.secret = secretKey;
    }

    /**
     * Recreates a credential from a secure store without converting the key to
     * an immutable String. The input must contain the hexadecimal agent key
     * format accepted by the upstream daemon; the caller retains its array.
     */
    public static PlayitCredential fromStoredSecret(char[] storedSecret) {
        if (storedSecret == null || storedSecret.length == 0 || (storedSecret.length & 1) != 0) {
            throw new IllegalArgumentException("Stored Playit key must be non-empty hexadecimal text");
        }
        for (char value : storedSecret) {
            boolean asciiHex = (value >= '0' && value <= '9')
                    || (value >= 'a' && value <= 'f')
                    || (value >= 'A' && value <= 'F');
            if (!asciiHex) {
                throw new IllegalArgumentException("Stored Playit key must be non-empty hexadecimal text");
            }
        }
        return new PlayitCredential(storedSecret.clone());
    }

    /**
     * Supplies a temporary copy to the caller and clears that copy afterward.
     * Close this object after the app has placed the credential in secure storage.
     */
    public void useSecret(SecretConsumer consumer) throws Exception {
        char[] copy;
        synchronized (this) {
            if (secret == null) {
                throw new IllegalStateException("Playit credential is closed");
            }
            copy = secret.clone();
        }
        try {
            consumer.accept(copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    public synchronized boolean isClosed() {
        return secret == null;
    }

    /** Delegates encrypted persistence to an Android Keystore-backed adapter. */
    public void persistTo(PlayitCredentialStore store) throws Exception {
        if (store == null) {
            throw new IllegalArgumentException("Credential store is required");
        }
        useSecret(store::saveAgentKey);
    }

    @Override
    public synchronized void close() {
        if (secret != null) {
            Arrays.fill(secret, '\0');
            secret = null;
        }
    }

    @Override
    public String toString() {
        return "PlayitCredential{secret=<redacted>, closed=" + isClosed() + "}";
    }
}
