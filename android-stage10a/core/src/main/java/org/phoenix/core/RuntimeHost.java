package org.phoenix.core;

import java.io.IOException;

/** A real runtime transport. Implementations must isolate JVM exit from the UI. */
public interface RuntimeHost {
    interface Listener {
        void onLine(String line);
        void onExit(int code);
    }
    void start(Listener listener) throws IOException;
    void command(String command) throws IOException;
    void forceStop() throws IOException;
    /** Polite termination (SIGTERM): the JVM runs its shutdown hooks, and Minecraft's hook saves the worlds. */
    default void terminate() throws IOException { forceStop(); }
    boolean isAlive();
}
