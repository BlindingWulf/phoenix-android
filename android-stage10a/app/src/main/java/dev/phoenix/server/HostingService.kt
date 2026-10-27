package dev.phoenix.server
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
/** Activity-independent user-started hosting; process death never silently restarts it. */
class HostingService:Service() {
    private lateinit var backend:PhoenixBackend
    private var wake:PowerManager.WakeLock?=null
    /** Keeps Wi-Fi out of power save while hosting, so players' packets are not batched; same scope as [wake]. */
    private var wifi:android.net.wifi.WifiManager.WifiLock?=null
    private val handler=Handler(Looper.getMainLooper())
    private var shown:String?=null
    private val monitor=object:Runnable { override fun run() {
        if(!backend.runtimeActive() && !backend.isBusy() && !backend.networkSetupActive() && !backend.restartPending()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        // Re-post only when the text changes; an identical notification every 2 s woke SystemUI for nothing.
        val line=backend.notificationLine()
        if(line!=shown) { shown=line; getSystemService(NotificationManager::class.java).notify(10,notification(line)) }
        handler.postDelayed(this,2000)
    } }
    private val openApp by lazy { PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE) }
    private val stopAction by lazy { PendingIntent.getService(this,1,Intent(this,HostingService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE) }
    override fun onCreate() { super.onCreate(); backend=PhoenixBackend.get(application)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("hosting","Minecraft hosting",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(detail:String):Notification {
        return Notification.Builder(this,"hosting").setSmallIcon(R.drawable.ic_stat_phoenix).setContentTitle("Phoenix Minecraft 26.2")
            .setContentText(detail).setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Stop",stopAction).build()).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        shown=null
        if(Build.VERSION.SDK_INT>=34) startForeground(10,notification("Starting requested operation"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(10,notification("Starting requested operation"))
        if(wake==null) wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"phoenix:hosting").apply { setReferenceCounted(false); acquire() }
        // HIGH_PERF, not LOW_LATENCY: LOW_LATENCY is only honoured with the screen on and the app in front.
        @Suppress("DEPRECATION") val wifiMode=android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        if(wifi==null) wifi=runCatching { applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            .createWifiLock(wifiMode,"phoenix:hosting").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        when(intent?.action) { "PROVISION_NETBIRD"->backend.provisionNetBird(); "RECONNECT_NETBIRD"->backend.retryTunnelSetup(); "START"->backend.start(); "STOP"->backend.stop(); "RESTART"->backend.restart(); "PROBE"->backend.probe(); "FORCE_STOP"->backend.forceStop(); "RENAME"->backend.renameServer(intent.getStringExtra("server-name") ?: "") }
        handler.removeCallbacks(monitor); handler.postDelayed(monitor,2000); return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(monitor); wake?.let { if(it.isHeld)it.release() }; wake=null
        wifi?.let { if(it.isHeld)it.release() }; wifi=null
        if(backend.runtimeActive()) backend.stop(); super.onDestroy() }
    override fun onBind(intent:Intent?)=null
}
