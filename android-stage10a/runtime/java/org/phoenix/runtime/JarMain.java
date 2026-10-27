package org.phoenix.runtime;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Headless equivalent of java -jar; the server JAR stays data, loaded by HotSpot. */
public final class JarMain {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 25) throw new IllegalStateException("Java 25 is required");
        if (args.length == 0) throw new IllegalArgumentException("server jar required");
        Path jarPath = Path.of(args[0]).toAbsolutePath();
        String entry;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getManifest() == null) throw new IllegalArgumentException("missing server manifest");
            entry = jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if (entry == null) throw new IllegalArgumentException("missing server Main-Class");
        var loader = new URLClassLoader(new java.net.URL[]{jarPath.toUri().toURL()}, JarMain.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        // Keep the class loader open while the server's non-daemon threads run.
        Class.forName(entry, true, loader).getMethod("main", String[].class)
                .invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
    }
}
