package org.phoenix.telemetry;

import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/**
 * Observes MXBeans only. No transformers or mods. With the argument {@code idleHeap=SECONDS} it also lets G1 give unused
 * heap back to the phone while the server idles: from that long after the start, once no collection ran for a minute,
 * the manageable flags switch to a periodic collection and 10-30% free heap; at the first burst of collections (players
 * or anything else allocating) they go back to the JVM's defaults, so a busy server keeps its larger heap.
 */
public final class MetricsAgent {
    private static final long IDLE_AFTER_QUIET_MS=60_000, BUSY_WINDOW_MS=30_000;
    private static final int BUSY_COLLECTIONS=3;
    private static final ArrayDeque<Long> collections=new ArrayDeque<>();
    private static long lastCount=-1, lastCollectionAt;
    private static boolean idle, unavailable;

    public static void premain(String arguments, Instrumentation ignored) {
        // -Dphoenix.idleHeap=SECONDS overrides the argument (0 turns it off), for measuring on a test phone.
        long idleHeapAfter=Long.getLong("phoenix.idleHeap",option(arguments,"idleHeap"))*1000;
        long started=System.currentTimeMillis();
        var sampler=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"phoenix-metrics"); t.setDaemon(true); return t; });
        sampler.scheduleWithFixedDelay(() -> {
            try {
                var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                var nonheap=ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
                long count=0,time=0;
                for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) { if(gc.getCollectionCount()>=0)count+=gc.getCollectionCount(); if(gc.getCollectionTime()>=0)time+=gc.getCollectionTime(); }
                var os=ManagementFactory.getOperatingSystemMXBean();
                Long cpu=null;
                if(os instanceof com.sun.management.OperatingSystemMXBean extended) cpu=extended.getProcessCpuTime();
                System.out.println("@@PHOENIX_METRICS {\"timestampMs\":"+System.currentTimeMillis()+",\"jvmHeapUsedBytes\":"+heap.getUsed()+",\"jvmHeapCommittedBytes\":"+heap.getCommitted()+",\"jvmHeapMaxBytes\":"+heap.getMax()+",\"jvmNonHeapUsedBytes\":"+nonheap.getUsed()+",\"gcCollections\":"+count+",\"gcCollectionTimeMs\":"+time+",\"jvmThreadCount\":"+ManagementFactory.getThreadMXBean().getThreadCount()+",\"jvmLoadedClassCount\":"+ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()+",\"jvmProcessCpuTimeNs\":"+cpu+",\"jvmAvailableProcessors\":"+os.getAvailableProcessors()+"}");
                if(idleHeapAfter>0) idleHeap(count,System.currentTimeMillis(),started+idleHeapAfter);
            } catch(Throwable error) { System.err.println("@@PHOENIX_METRICS_UNAVAILABLE "+error.getClass().getSimpleName()); }
        },0,2,TimeUnit.SECONDS);
    }

    /** Called every 2 s with the total collection count: switches to idle sizing after a quiet minute, back at a burst. */
    private static void idleHeap(long count,long now,long notBefore) {
        if(unavailable) return;
        if(lastCount<0) { lastCount=count; lastCollectionAt=now; return; }
        for(long i=lastCount;i<count;i++) collections.addLast(now);
        if(count>lastCount) lastCollectionAt=now;
        lastCount=count;
        while(!collections.isEmpty() && now-collections.peekFirst()>BUSY_WINDOW_MS) collections.removeFirst();
        if(!idle && now>=notBefore && now-lastCollectionAt>=IDLE_AFTER_QUIET_MS) switchTo(true);
        else if(idle && collections.size()>=BUSY_COLLECTIONS) switchTo(false);
    }

    private static void switchTo(boolean quiet) {
        try {
            var hotspot=ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            // The minimum may never exceed the maximum, so the order depends on the direction.
            if(quiet) { hotspot.setVMOption("MinHeapFreeRatio","10"); hotspot.setVMOption("MaxHeapFreeRatio","30"); hotspot.setVMOption("G1PeriodicGCInterval","30000"); }
            else { hotspot.setVMOption("G1PeriodicGCInterval","0"); hotspot.setVMOption("MaxHeapFreeRatio","70"); hotspot.setVMOption("MinHeapFreeRatio","40"); }
            idle=quiet;
            System.out.println("@@PHOENIX_IDLE_HEAP "+(quiet ? "on" : "off"));
        } catch(Throwable error) {
            unavailable=true;
            System.out.println("@@PHOENIX_IDLE_HEAP unavailable "+error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }

    private static long option(String arguments,String key) {
        if(arguments==null) return 0;
        for(String part:arguments.split(",")) if(part.startsWith(key+"=")) try { return Long.parseLong(part.substring(key.length()+1)); } catch(NumberFormatException ignored) { return 0; }
        return 0;
    }
}
