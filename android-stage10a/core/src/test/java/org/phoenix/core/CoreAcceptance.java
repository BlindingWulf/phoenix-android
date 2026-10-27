package org.phoenix.core;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class CoreAcceptance {
    private static final class Host implements RuntimeHost {
        volatile boolean alive; volatile int terminates; Listener listener; int starts; ArrayList<String> commands=new ArrayList<>();
        /** Exit code of Force stop (the phone showed 143, desktop JVMs give 137); exitOnTerminate -1: SIGTERM is ignored. */
        int forceExit=137, exitOnTerminate=-1;
        public void start(Listener listener) { this.listener=listener; alive=true; starts++; }
        public void command(String c) { commands.add(c); }
        public void forceStop() { alive=false; listener.onExit(forceExit); }
        public void terminate() { terminates++; if(exitOnTerminate>=0) exit(exitOnTerminate); } // A hung server ignores SIGTERM too.
        public boolean isAlive(){ return alive; }
        void exit(int code) { alive=false; listener.onExit(code); }
        void ready() { listener.onLine("[Server thread/INFO]: Done (1.001s)! For help, type \"help\""); }
        void line(String text) { listener.onLine(text); }
    }
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void until(BooleanSupplier predicate) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(!predicate.getAsBoolean()) { if(System.nanoTime()>end)throw new AssertionError("Timed out"); Thread.sleep(10); } }
    static void fails(CompletableFuture<Void> f) throws Exception { try { f.get(2,TimeUnit.SECONDS); throw new AssertionError("Expected rejection"); } catch(ExecutionException expected) { check(expected.getCause() instanceof IOException,"I/O rejection expected"); } }
    public static void main(String[] args) throws Exception {
        Host host=new Host(); boolean[] protocol={false};
        try(var c=new ServerController(host,()->protocol[0],Duration.ofSeconds(5),Duration.ofMillis(50))) {
            fails(c.start()); c.installed().get(); c.start().get(); fails(c.start());
            check(c.snapshot().state()==ServerController.State.STARTING,"Process is not readiness");
            host.ready(); Thread.sleep(550); check(c.snapshot().state()==ServerController.State.STARTING,"Log alone is not readiness");
            protocol[0]=true; until(()->c.snapshot().state()==ServerController.State.ONLINE);
            fails(c.command("say okay\nstop")); c.command("save-all flush").get();
            c.restart().get(); check(host.commands.contains("stop"),"Restart sends stop");
            host.exit(0); until(()->host.starts==2); host.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            host.exit(9); until(()->c.snapshot().state()==ServerController.State.ERROR);
            check(c.snapshot().exitCode()==9,"Crash exit retained"); c.start().get(); host.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            c.stop().get(); until(()->c.snapshot().state()==ServerController.State.ERROR); fails(c.start());
            c.forceStop().get(); until(()->!host.isAlive()); check(host.starts==3,"No duplicate after timed out shutdown");
        }
        // Bounded stop: stop, then SIGTERM (shutdown hook saves), then ERROR; never auto-killed mid-save, never stuck in STOPPING.
        Host hung=new Host();
        try(var c=new ServerController(hung,()->true,Duration.ofSeconds(5),Duration.ofMillis(50),Duration.ofMillis(300))) {
            c.installed().get(); c.start().get(); hung.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            c.stop().get(); check(hung.commands.contains("stop"),"Stop asks Minecraft to save first");
            until(()->hung.terminates==1); check(hung.isAlive() && c.snapshot().state()==ServerController.State.STOPPING,"SIGTERM sent, still stopping");
            until(()->c.snapshot().state()==ServerController.State.ERROR);
            check(hung.isAlive() && c.snapshot().detail().contains("Force stop"),"No automatic kill; explicit force stop offered");
            fails(c.start()); c.forceStop().get(); until(()->!hung.isAlive());
            c.start().get(); check(hung.starts==2,"Start allowed once the forced runtime is gone");
        }
        // A clean exit (143) after Phoenix's own SIGTERM is a saved stop, not a crash.
        Host slow=new Host();
        try(var c=new ServerController(slow,()->true,Duration.ofSeconds(5),Duration.ofMillis(50),Duration.ofSeconds(1))) {
            c.installed().get(); c.start().get(); slow.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            c.stop().get(); until(()->slow.terminates==1); slow.exit(143);
            until(()->c.snapshot().state()==ServerController.State.STOPPED);
        }
        recovery();
        System.out.println("PASS bounded stop: stop command, SIGTERM after timeout, no automatic kill, exit 143 after SIGTERM is clean");
        System.out.println("PASS lifecycle: install guard, duplicate guard, dual readiness, command injection, same-runtime restart, crash detection, graceful-timeout ownership");
    }

    /** Stage 10 recovery (owner 2026-10-18): failed starts seen at once, Stop in ERROR, Force stop is a stop, crashes stay errors. */
    static void recovery() throws Exception {
        // A failed start (Minecraft 26.2 logs it, then non-daemon mod threads keep Java alive) is an ERROR within the second,
        // not after the 30 s startup timeout here (5 min in the app); Java is then asked to exit, and a new start is allowed.
        Host failed=new Host(); failed.exitOnTerminate=143;
        try(var c=new ServerController(failed,()->true,Duration.ofSeconds(30),Duration.ofMillis(50),Duration.ofMillis(300),Duration.ofMillis(200))) {
            c.installed().get(); c.start().get();
            failed.line("[Server thread/INFO]: Failed to parse bug link https://x"); // a harmless "Failed" line is not a failed start
            failed.line("[Worker-Main-7/ERROR]: Unable to read or access the world gen settings file!");
            Thread.sleep(100); check(c.snapshot().state()==ServerController.State.STARTING,"Only the fatal start lines count");
            failed.line("[main/WARN]: Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode");
            until(()->c.snapshot().state()==ServerController.State.ERROR);
            check(c.snapshot().detail().startsWith("Minecraft failed to start"),"Failed start named: "+c.snapshot().detail());
            until(()->failed.terminates==1); until(()->!failed.isAlive());
            until(()->c.snapshot().exitCode()!=null);
            check(c.snapshot().state()==ServerController.State.ERROR && c.snapshot().detail().contains("Failed to load datapacks"),
                "Exit after a failed start keeps the reason: "+c.snapshot().detail());
            c.start().get(); check(failed.starts==2,"Start allowed after the failed one ended");
            failed.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            // after Done the start lines no longer apply: a crash while online is judged by its exit
            failed.line("[Server thread/ERROR]: Encountered an unexpected exception");
            Thread.sleep(100); check(c.snapshot().state()==ServerController.State.ONLINE,"Online crash is judged by its exit");
            failed.exit(1); until(()->c.snapshot().state()==ServerController.State.ERROR);
            check(c.snapshot().exitCode()==1 && c.snapshot().detail().contains("code 1"),"JVM crash exit is an error");
        }
        System.out.println("PASS failed start: ERROR at once, Java ended after grace, reason kept, restart allowed, online crash is an error");
        // Stop works in ERROR: after a failed start it ends Java at once; after a readiness timeout it asks Minecraft to save.
        Host stuck=new Host(); stuck.exitOnTerminate=143;
        try(var c=new ServerController(stuck,()->true,Duration.ofSeconds(30),Duration.ofMillis(50),Duration.ofMillis(300),Duration.ofSeconds(30))) {
            c.installed().get(); c.start().get();
            stuck.line("[main/ERROR]: Failed to start the minecraft server");
            until(()->c.snapshot().state()==ServerController.State.ERROR); check(c.processAlive(),"Java still running after the failed start");
            c.stop().get(); until(()->!stuck.isAlive()); check(stuck.terminates==1 && !stuck.commands.contains("stop"),"Stop ends Java directly");
            until(()->c.snapshot().state()==ServerController.State.STOPPED);
            check(c.snapshot().detail().contains("failed start"),"Stopped after a failed start: "+c.snapshot().detail());
        }
        Host slowStart=new Host();
        try(var c=new ServerController(slowStart,()->true,Duration.ofMillis(200),Duration.ofSeconds(30),Duration.ofMillis(300),Duration.ofSeconds(30))) {
            c.installed().get(); c.start().get();
            until(()->c.snapshot().state()==ServerController.State.ERROR); check(c.snapshot().detail().contains("timed out"),"Readiness timeout");
            c.stop().get(); check(slowStart.commands.contains("stop") && c.snapshot().state()==ServerController.State.STOPPING,"Stop in ERROR asks for a save");
            slowStart.exit(0); until(()->c.snapshot().state()==ServerController.State.STOPPED);
        }
        System.out.println("PASS stop in ERROR: failed start ends Java, readiness timeout stops with a save");
        // Force stop is the stop the user asked for, whatever signal ended Java: 143 (what the phone showed) or 137.
        for(int code : new int[] {143,137}) {
            Host forced=new Host(); forced.forceExit=code;
            try(var c=new ServerController(forced,()->true,Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMillis(300))) {
                c.installed().get(); c.start().get(); forced.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
                c.forceStop().get(); until(()->c.snapshot().exitCode()!=null);
                check(c.snapshot().state()==ServerController.State.STOPPED && c.snapshot().detail().contains("Force stop"),
                    "Force stop exit "+code+" is a stop: "+c.snapshot().state()+" "+c.snapshot().detail());
                c.start().get(); check(forced.starts==2,"Start after force stop");
            }
        }
        System.out.println("PASS force stop: exit 143 and 137 after Force stop read as stopped");
        // The watchdog's kill (Runtime.halt(1)) while online is a crash, not a stop.
        Host watchdog=new Host();
        try(var c=new ServerController(watchdog,()->true,Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMillis(300))) {
            c.installed().get(); c.start().get(); watchdog.ready(); until(()->c.snapshot().state()==ServerController.State.ONLINE);
            watchdog.line("[Server Watchdog/ERROR]: A single server tick took 60.00 seconds (should be max 0.05)");
            watchdog.exit(1); until(()->c.snapshot().state()==ServerController.State.ERROR);
            check(c.snapshot().exitCode()==1,"Watchdog exit retained");
        }
        System.out.println("PASS watchdog kill: an error with its exit code");
    }
}
