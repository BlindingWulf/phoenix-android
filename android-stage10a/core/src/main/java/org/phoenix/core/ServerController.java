package org.phoenix.core;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Single operation queue; process creation alone never means ONLINE. */
public final class ServerController implements AutoCloseable {
    public enum State { NOT_INSTALLED, INSTALLING, READY, STARTING, ONLINE, STOPPING, STOPPED, RESTARTING, RECOVERING, ERROR }
    /** Cheap to take: callers poll it several times a second, so it carries no console copy. */
    public record Snapshot(State state, String detail, long uptimeMillis, Long startupMillis, Integer exitCode) {}
    private final RuntimeHost host;
    private final BooleanSupplier statusProbe;
    private final ScheduledExecutorService queue = Executors.newSingleThreadScheduledExecutor();
    private final Duration startupTimeout, stopTimeout;
    /** After a graceful stop times out: SIGTERM at once, then ERROR (explicit force stop) after this grace. Null skips SIGTERM. */
    private final Duration killGrace;
    private State state = State.NOT_INSTALLED;
    private String detail = "Minecraft 26.2 has not been installed";
    private Integer exitCode;
    private long startedNanos, generation;
    private long startupStartedNanos;
    /** The last sign of startup progress from the host (0 = none yet); a start that keeps reporting progress is not expired. */
    private long startupProgressNanos;
    private Long startupMillis;
    private boolean readyLine, restartPending, closed, terminateSent;
    /**
     * The readiness timeout expired while Java kept running. Readiness is still watched then: a start that was only slow (a
     * long world load or a quiet spawn preparation) goes ONLINE when Minecraft reports Done after all.
     */
    private boolean readinessTimedOut;
    /** The user pressed Force stop: the exit that follows (SIGTERM 143 or SIGKILL 137 alike) is the stop they asked for. */
    private boolean forceRequested;
    /** The line with which Minecraft reported that this start failed, or null. */
    private String startFailure;
    /**
     * After a failed start, how long Java gets to exit by itself before Phoenix sends SIGTERM. Minecraft 26.2 returns from
     * main on a failed start, but threads that mods leave running keep the JVM alive, so without this the failure showed
     * only when the startup timeout expired.
     */
    private final Duration startFailGrace;
    /**
     * Lines Minecraft 26.2 logs when a start has failed (net.minecraft.server.Main and MinecraftServer.runServer); none of
     * them is logged by a start that goes on to succeed.
     */
    static final String[] START_FAILED = {
        "Failed to start the minecraft server",
        "Failed to load datapacks, can't proceed with server load",
        "Failed to load world data. World files may be corrupted",
        "Encountered an unexpected exception",
    };
    /** Readiness polling runs only while a start is in progress, not for the life of the app. */
    private ScheduledFuture<?> readyChecks;
    public ServerController(RuntimeHost host, BooleanSupplier probe, Duration startupTimeout, Duration stopTimeout) {
        this(host,probe,startupTimeout,stopTimeout,null);
    }
    public ServerController(RuntimeHost host, BooleanSupplier probe, Duration startupTimeout, Duration stopTimeout, Duration killGrace) {
        this(host,probe,startupTimeout,stopTimeout,killGrace,Duration.ofSeconds(5));
    }
    public ServerController(RuntimeHost host, BooleanSupplier probe, Duration startupTimeout, Duration stopTimeout, Duration killGrace, Duration startFailGrace) {
        this.host=host; statusProbe=probe; this.startupTimeout=startupTimeout; this.stopTimeout=stopTimeout; this.killGrace=killGrace;
        this.startFailGrace=startFailGrace;
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(state,detail,state==State.ONLINE ? (System.nanoTime()-startedNanos)/1_000_000 : 0,startupMillis,exitCode);
    }
    public CompletableFuture<Void> installed() { return submit(() -> {
        synchronized(this) { if (isActive()) throw new IOException("Cannot install while runtime is active"); state=State.READY; detail="Installed and ready"; }
    }); }
    public CompletableFuture<Void> start() { return submit(this::startNow); }
    private synchronized void startNow() throws IOException {
        if(closed) throw new IOException("Controller is closed");
        if(isActive() || host.isAlive()) throw new IOException("Server operation already active");
        if(state==State.NOT_INSTALLED || state==State.INSTALLING) throw new IOException("Install server first");
        state=State.STARTING; detail="Waiting for Minecraft readiness and status response"; readyLine=false; exitCode=null;
        startFailure=null; forceRequested=false; terminateSent=false; readinessTimedOut=false;
        startupStartedNanos=System.nanoTime(); startupMillis=null; startupProgressNanos=0;
        final long token=++generation;
        try {
            host.start(listener(token));
        } catch(IOException | RuntimeException e) { state=State.ERROR; detail=e.getMessage(); throw new IOException("Runtime launch failed: "+detail,e); }
        queue.schedule(() -> startupExpired(token),startupTimeout.toMillis(),TimeUnit.MILLISECONDS);
        stopReadyChecks();
        readyChecks=queue.scheduleWithFixedDelay(this::checkReady, 200, 250, TimeUnit.MILLISECONDS);
    }
    private RuntimeHost.Listener listener(long token) {
        return new RuntimeHost.Listener() {
            public void onLine(String line) { queue.execute(() -> log(token,line)); }
            public void onExit(int code) { queue.execute(() -> exited(token,code)); }
        };
    }
    /**
     * Takes over a Minecraft process that an earlier run of this app started and that is still running (the host must have
     * been handed it with attach first). It is shown as [target]: ONLINE once running, STARTING while readiness is still
     * watched, or STOPPING while it finishes a stop that was already under way. Nothing is launched and nothing is signalled.
     */
    public CompletableFuture<Void> adopt(State target, long uptimeMillis) { return submit(() -> adoptNow(target,uptimeMillis)); }
    private synchronized void adoptNow(State target, long uptimeMillis) throws IOException {
        if(closed) throw new IOException("Controller is closed");
        if(isActive() || host.isAlive()) throw new IOException("Server operation already active");
        state=target; readyLine=target==State.ONLINE; exitCode=null; startFailure=null; forceRequested=false; terminateSent=false; readinessTimedOut=false;
        startupStartedNanos=System.nanoTime(); startupMillis=null; startupProgressNanos=0;
        startedNanos=System.nanoTime()-uptimeMillis*1_000_000L;
        detail=target==State.ONLINE ? "Reconnected to Minecraft, which was already running"
            : target==State.STARTING ? "Reconnected to Minecraft while it is starting" : "Reconnected to Minecraft while it is stopping";
        final long token=++generation;
        host.start(listener(token));
        if(target==State.STARTING) {
            queue.schedule(() -> startupExpired(token),startupTimeout.toMillis(),TimeUnit.MILLISECONDS);
            stopReadyChecks();
            readyChecks=queue.scheduleWithFixedDelay(this::checkReady,200,250,TimeUnit.MILLISECONDS);
        }
    }
    private void stopReadyChecks() { if(readyChecks!=null) { readyChecks.cancel(false); readyChecks=null; } }
    private synchronized void log(long token,String line) {
        if(token!=generation) return;
        if(line.contains("Done (") && line.contains("For help, type")) readyLine=true;
        // A start that outlasted the readiness timeout can still fail: that is a failed start too (it then never revives).
        if((state==State.STARTING || lateStart()) && !readyLine && startFailure==null) for(String failed : START_FAILED) if(line.contains(failed)) {
            startFailed(token,failed);
            break;
        }
    }
    /** The start failed: ERROR at once (no automatic restart), and Java is asked to exit if it has not after the grace. */
    private void startFailed(long token,String reason) {
        startFailure=reason; state=State.ERROR; stopReadyChecks();
        detail="Minecraft failed to start: "+reason+". See the console for the cause.";
        queue.schedule(() -> endFailedStart(token),startFailGrace.toMillis(),TimeUnit.MILLISECONDS);
    }
    private synchronized void endFailedStart(long token) {
        if(token!=generation || startFailure==null || state!=State.ERROR || !host.isAlive()) return;
        // Minecraft has given up; a SIGTERM runs its shutdown hook, which waits for anything still saving.
        try { host.terminate(); terminateSent=true; } catch(IOException | RuntimeException e) { detail=detail+" Ending Java failed: "+e.getMessage(); }
    }
    private void checkReady() {
        long token;
        synchronized(this) {
            if(state!=State.STARTING && !lateStart()) { stopReadyChecks(); return; }
            if(!readyLine) return;
            token=generation;
        }
        boolean ok;
        try { ok=statusProbe.getAsBoolean(); } catch(RuntimeException e) { ok=false; }
        synchronized(this) { if(ok && generation==token && (state==State.STARTING || lateStart()) && host.isAlive()) {
            detail=lateStart() ? "Minecraft is accepting Java status connections (it finished starting after the readiness timeout)"
                : "Minecraft is accepting Java status connections";
            state=State.ONLINE; startedNanos=System.nanoTime(); startupMillis=(startedNanos-startupStartedNanos)/1_000_000;
            readinessTimedOut=false;
            stopReadyChecks();
        } }
    }
    /** An ERROR from the readiness timeout alone (no failed-start line), with Java still running: it may still come online. */
    private boolean lateStart() { return state==State.ERROR && readinessTimedOut && startFailure==null; }
    /** A start that timed out with no failed-start line while Java still runs: it can still come online. */
    public synchronized boolean startMayStillFinish() { return lateStart() && host.isAlive(); }
    /**
     * Tells the controller a start that is still in progress is making progress (for example a long spawn preparation that
     * logs every few seconds). The startup timeout then counts from this moment instead of from the start; a start that
     * goes quiet still expires after the full timeout.
     */
    public synchronized void noteStartupProgress() {
        if(state==State.STARTING) startupProgressNanos=System.nanoTime();
    }
    private synchronized void startupExpired(long token) {
        if(token!=generation || state!=State.STARTING) return;
        if(startupProgressNanos!=0) {
            long quiet=System.nanoTime()-startupProgressNanos, timeout=startupTimeout.toNanos();
            if(quiet<timeout) { queue.schedule(() -> startupExpired(token),timeout-quiet,TimeUnit.NANOSECONDS); return; }
        }
        state=State.ERROR; detail="Minecraft readiness timed out; stop or inspect console"; readinessTimedOut=true;
        // Keep the live process owned and prevent another start; user can stop it safely. Readiness is still checked.
    }
    public CompletableFuture<Void> stop() { return submit(() -> stopNow(false)); }
    public CompletableFuture<Void> restart() { return submit(() -> stopNow(true)); }
    private synchronized void stopNow(boolean restart) throws IOException {
        if(!host.isAlive()) { restartPending=false; state=State.STOPPED; detail="Stopped"; return; }
        if(state==State.STOPPING || state==State.RESTARTING) throw new IOException("Stop already in progress");
        if(startFailure!=null) {
            // Minecraft already gave up and reads no console commands: end Java directly (its shutdown hook still runs).
            restartPending=false; state=State.STOPPING; detail="Ending Java after the failed start";
            final long token=generation;
            host.terminate(); terminateSent=true;
            if(killGrace!=null) queue.schedule(() -> killExpired(token),killGrace.toMillis(),TimeUnit.MILLISECONDS);
            return;
        }
        restartPending=restart; state=restart ? State.RESTARTING : State.STOPPING;
        detail="Graceful save and shutdown requested";
        final long token=generation;
        // Armed before the write: a stop whose command cannot be delivered must still time out instead of staying STOPPING.
        queue.schedule(() -> stopExpired(token),stopTimeout.toMillis(),TimeUnit.MILLISECONDS);
        host.command("stop"); // Minecraft stop performs the final world save.
    }
    private synchronized void stopExpired(long token) {
        if(token==generation && host.isAlive() && (state==State.STOPPING || state==State.RESTARTING)) {
            restartPending=false;
            if(killGrace==null) { state=State.ERROR; detail="Graceful stop timed out; explicit force stop required (world may be unsaved)"; return; }
            state=State.STOPPING; detail="Graceful stop timed out; asking Java to exit so its shutdown hook saves the world";
            try { host.terminate(); terminateSent=true; } catch(IOException | RuntimeException e) { detail="Graceful stop timed out; terminate failed: "+e.getMessage(); }
            queue.schedule(() -> killExpired(token),killGrace.toMillis(),TimeUnit.MILLISECONDS);
        }
    }
    // Never SIGKILL automatically: SIGTERM waits for a save that is still running, and a kill mid-save can corrupt region files.
    private synchronized void killExpired(long token) {
        if(token!=generation || !host.isAlive() || state!=State.STOPPING) return;
        state=State.ERROR; detail="Java did not exit after stop and SIGTERM; it may still be saving. Force stop only if it stays stuck (world may be unsaved)";
    }
    private synchronized void exited(long token,int code) {
        if(token!=generation) return;
        exitCode=code;
        boolean expected=state==State.STOPPING || state==State.RESTARTING;
        // 143 is SIGTERM: after Phoenix's own SIGTERM the shutdown hook saved, so it is a clean stop.
        boolean clean=code==0 || (terminateSent && code==143); terminateSent=false;
        boolean forced=forceRequested && expected; forceRequested=false;
        boolean restart=restartPending && expected && clean && !forced; restartPending=false; readyLine=false; readinessTimedOut=false;
        String failure=startFailure; startFailure=null;
        if(forced) {
            state=State.STOPPED;
            detail=code==0 || code==143 ? "Stopped by Force stop (Java ran its shutdown save)"
                : "Stopped by Force stop (exit code "+code+"; unsaved changes may be lost)";
        } else if(failure!=null) {
            // The failed start stays visible; it reads as stopped only when the user pressed Stop after it.
            state=expected && clean ? State.STOPPED : State.ERROR;
            detail=(state==State.STOPPED ? "Stopped after a failed start: " : "Minecraft failed to start: ")+failure
                +" (exit code "+code+"). See the console for the cause.";
        } else {
            state=expected && clean ? State.STOPPED : State.ERROR;
            detail=state==State.STOPPED ? "Stopped after clean shutdown" : code<0 ? "Java ended, and its exit code is not known after Phoenix reconnected to it"
                : "Runtime exited with code "+code;
        }
        if(restart) { try { startNow(); } catch(IOException e) { state=State.ERROR; detail=e.getMessage(); } }
    }
    public CompletableFuture<Void> command(String command) { return submit(() -> {
        synchronized(this) {
            if(state!=State.ONLINE) throw new IOException("Minecraft is not online");
            if(command==null || command.isBlank() || command.length()>512 || command.indexOf('\n')>=0 || command.indexOf('\r')>=0 || command.indexOf('\0')>=0) throw new IOException("Invalid console command");
            if(command.strip().equals("stop")) { stopNow(false); return; }
            host.command(command);
        }
    }); }
    public CompletableFuture<Void> forceStop() { return submit(() -> {
        synchronized(this) {
            if(!host.isAlive()) { restartPending=false; state=State.STOPPED; detail="Stopped"; return; }
            restartPending=false; forceRequested=true; state=State.STOPPING; detail="Forced termination requested; world may be unsaved"; host.forceStop();
        }
    }); }
    /** Whether the Java process is still running, in any state (an ERROR can leave it running): Stop and Force stop apply. */
    public boolean processAlive() { return host.isAlive(); }
    public synchronized boolean isActive() { return state==State.STARTING || state==State.ONLINE || state==State.STOPPING || state==State.RESTARTING || state==State.RECOVERING; }
    @FunctionalInterface private interface Operation { void run() throws IOException; }
    private CompletableFuture<Void> submit(Operation operation) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(queue.isShutdown()) { result.completeExceptionally(new IOException("Controller closed")); return result; }
        queue.execute(() -> { try { operation.run(); result.complete(null); } catch(Exception e) { result.completeExceptionally(e); } });
        return result;
    }
    @Override public synchronized void close() { closed=true; queue.shutdown(); }
}
