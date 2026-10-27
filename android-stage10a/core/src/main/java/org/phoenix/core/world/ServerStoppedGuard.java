package org.phoenix.core.world;

import java.io.IOException;

/** A caller supplied guard that coordinates world mutation with server lifecycle. */
@FunctionalInterface
public interface ServerStoppedGuard {
    void assertServerStopped() throws IOException;
}
