package org.phoenix.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Child executable comes only from the APK's immutable native library directory on Android. */
public final class ProcessRuntimeHost implements RuntimeHost {
    private final Supplier<ProcessBuilder> builder;
    /** Volatile so kill and liveness checks never wait behind a command write blocked on a full stdin pipe. */
    private volatile Process process;
    private Writer input;
    /** A Java process this app started before (see attach); the next start() follows it instead of launching one. */
    private Process adopted;
    public ProcessRuntimeHost(Supplier<ProcessBuilder> builder) { this.builder=builder; }
    /** Hands over a Java process that outlived the app that started it: the next start() follows it, reading its output and exit as for a child. */
    public synchronized void attach(Process existing) { adopted=existing; }
    public synchronized void start(Listener listener) throws IOException {
        if(isAlive()) throw new IOException("Runtime already running");
        process=adopted!=null ? adopted : builder.get().redirectErrorStream(true).start();
        adopted=null;
        final Process child=process;
        input=new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8);
        Thread reader=new Thread(() -> {
            try(Reader stream=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)) {
                // Read in blocks; a synchronized read() per character was measurable during log bursts.
                StringBuilder line=new StringBuilder(); char[] buffer=new char[8192]; int n;
                while((n=stream.read(buffer))!=-1) for(int i=0;i<n;i++) {
                    char c=buffer[i];
                    if(c=='\n') { listener.onLine(line.toString()); line.setLength(0); }
                    else if(c!='\r' && line.length()<8192) line.append(c);
                }
                if(line.length()>0) listener.onLine(line.toString());
            } catch(IOException e) { listener.onLine("Runtime output stream closed"); }
            try { listener.onExit(child.waitFor()); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        },"phoenix-runtime-output"); reader.setDaemon(true); reader.start();
    }
    public synchronized void command(String command) throws IOException {
        if(!isAlive() || input==null) throw new IOException("Runtime is not running");
        input.write(command); input.write('\n'); input.flush();
    }
    public void forceStop() { Process p=process; if(p!=null) p.destroyForcibly(); }
    public void terminate() { Process p=process; if(p!=null) p.destroy(); }
    public boolean isAlive() { Process p=process; return p!=null && p.isAlive(); }
}
