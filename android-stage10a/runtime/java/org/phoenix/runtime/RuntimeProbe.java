package org.phoenix.runtime;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compiled with Java 25; every PASS follows an actual operation. */
public final class RuntimeProbe {
    private static void pass(String operation) { System.out.println("PHOENIX_PROBE_PASS " + operation); }
    public static void main(String[] args) throws Exception {
        System.out.println("PHOENIX_JAVA " + System.getProperty("java.runtime.version") + " " + System.getProperty("os.arch"));
        if (Runtime.version().feature() != 25) throw new IllegalStateException("Java 25 is required");
        pass("java25");
        System.setProperty("java.awt.headless", "true");
        var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.RED); graphics.fillRect(0,0,16,16);
            graphics.setFont(new java.awt.Font("Dialog",java.awt.Font.PLAIN,8));
            graphics.drawString("P",1,10);
        } finally { graphics.dispose(); }
        var encoded = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image,"png",encoded)) throw new IllegalStateException("PNG writer unavailable");
        var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(encoded.toByteArray()));
        if (decoded == null || decoded.getRGB(15,15) != java.awt.Color.RED.getRGB()) throw new IllegalStateException("headless image mismatch");
        pass("headless-awt-font-png");
        Path directory = args.length == 0 ? Files.createTempDirectory("phoenix-probe") : Path.of(args[0]);
        Files.createDirectories(directory);
        Path file = directory.resolve("probe.txt");
        Files.writeString(file, "phoenix-runtime-probe");
        if (!Files.readString(file).equals("phoenix-runtime-probe")) throw new IllegalStateException("filesystem mismatch");
        Files.delete(file); pass("filesystem");
        AtomicBoolean threadRan = new AtomicBoolean();
        Thread thread = new Thread(() -> threadRan.set(true), "phoenix-probe-thread");
        thread.start(); thread.join(5000);
        if (!threadRan.get()) throw new IllegalStateException("thread timed out");
        pass("thread");
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            timer.schedule(() -> pass("timer"), 20, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        }
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             var peer = server.accept()) {
            client.setSoTimeout(5000); peer.setSoTimeout(5000);
            client.getOutputStream().write(73);
            if (peer.getInputStream().read() != 73) throw new IllegalStateException("socket mismatch");
            pass("socket");
        }
        byte[] allocation = new byte[1024 * 1024];
        allocation[allocation.length - 1] = 42;
        if (allocation[allocation.length - 1] != 42) throw new IllegalStateException("allocation failed");
        System.gc(); pass("memory");
        System.out.println("PHOENIX_MEMORY heapUsed=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                + " heapCommitted=" + Runtime.getRuntime().totalMemory() + " heapMax=" + Runtime.getRuntime().maxMemory()
                + " threads=" + ManagementFactory.getThreadMXBean().getThreadCount());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> pass("shutdown-hook"), "phoenix-probe-shutdown"));
        System.out.println("PHOENIX_PROBE_MAIN_COMPLETE");
    }
}
