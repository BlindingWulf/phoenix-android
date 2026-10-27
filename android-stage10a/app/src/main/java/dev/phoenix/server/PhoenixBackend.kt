package dev.phoenix.server

import android.app.ActivityManager
import android.content.*
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import org.json.JSONObject
import org.phoenix.core.*
import org.phoenix.core.provision.*
import org.phoenix.core.world.*
import org.phoenix.runtime.RuntimeLayout
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** App-private backend. No HTTP admin listener, external app, shell, or shared-storage world access. */
class PhoenixBackend private constructor(private val app:Context) {
    companion object {
        @Volatile private var instance:PhoenixBackend?=null
        fun get(context:Context):PhoenixBackend=instance ?: synchronized(this) { instance ?: PhoenixBackend(context.applicationContext).also { instance=it } }
        /** Mod ids Phoenix installs and pins itself (Fabric API, Geyser, Floodgate); packs never carry their own copies. */
        val PINNED_MOD_IDS=setOf("fabric-api","geyser-fabric","floodgate",SpawnPrepManifest.MOD_ID)
        /** Settings: prepare the area around spawn before the server finishes starting (on by default) and how far, in blocks. */
        private const val PREF_SPAWNPREP="spawnprep-enabled"
        private const val PREF_SPAWNPREP_RADIUS="spawnprep-radius"
        /** An empty file in the server folder asks the mod to stop preparing; the mod deletes it when it ends. */
        private const val SPAWNPREP_CANCEL_FILE="phoenix-spawnprep.cancel"
        private const val PREF_AUTO_SETUP_ID="auto-network-setup-id"
        /** Which proxy the saved hostname points players at: "eu" (NetBird's shared cluster) or "us" (Phoenix's own). */
        private const val PREF_PUBLIC_ROUTE="public-route"
        /** Set while the server runs; still set at the next launch means Phoenix was closed or crashed while hosting. */
        private const val PREF_HOSTING_SINCE="hosting-since"
        /** The server's pid and its start time (clock ticks), saved when it starts, so a later run of the app can find it again. */
        private const val PREF_RUNTIME_PID="runtime-pid"
        private const val PREF_RUNTIME_START="runtime-start-ticks"
        /** How friends connect: "easy" (NetBird's hosted EU proxy) or "regional" (the owner's Oracle proxy). */
        private const val PREF_NETWORK_MODE="network-mode"
        /** Where most players are ("us", "ca" or "eu"), chosen with Regional Optimized; guidance only. */
        private const val PREF_REGIONAL_AREA="regional-area"
        /** The voice_host value Phoenix last wrote, so an owner's own value is never overwritten. */
        private const val PREF_VOICE_HOST="voice-host-managed"
        private const val PREF_PROXY_OVERLAY_IP="regional-proxy-overlay-ip"
        private const val PREF_PROXY_COUNTER="regional-proxy-counter"
        private const val PREF_PROXY_MONTH="regional-proxy-month"
        private const val PREF_PROXY_BYTES="regional-proxy-bytes"
        private const val PREF_AUTO_SETUP_STATE="auto-network-setup-state"
        private const val PREF_AUTO_SETUP_DETAIL="auto-network-setup-detail"
        private const val PREF_AUTO_SETUP_HOSTNAME="auto-network-setup-hostname"
        private const val PREF_AUTO_SETUP_ACCOUNT_ID="auto-network-setup-account-id"
        private const val PREF_DNS_STATE="dns-state"
        /** The selected public route (PublicRoute.key); absent on a fresh install. */
        private const val PREF_ROUTE="public-route-v2"
        /** A route the owner turned on that still needed an account; switched to when its setup finishes. */
        private const val PREF_REQUESTED_ROUTE="requested-route"
        /** The optional custom name is in use (records and account are kept when it is off). */
        private const val PREF_NAME_ENABLED="custom-name-enabled"
        /** RouteEndpoints.fingerprint public DNS was verified for; only then is the name the primary address. */
        private const val PREF_NAME_VERIFIED="custom-name-verified-for"
        private const val PREF_NAME_BEDROCK="custom-name-bedrock-verified"
        /** Owner's switch for the custom name's front door (Playit + NetBird + name); on by default. */
        private const val PREF_FRONT_DOOR="name-front-door"
        /** The name's Java SRV leads to the front door (set only after a full check through NetBird). */
        private const val PREF_FRONT_DOOR_ACTIVE="name-front-door-active"
        /** The owner tapped "Set up NetBird" for the front door; its free ports are made once NetBird is connected and signed in. */
        private const val PREF_FRONT_DOOR_SETUP="name-front-door-setup"
        /** Local port of the front door; NetBird's name service forwards here. */
        private const val FRONT_DOOR_PORT=25591
        /**
         * Status pings through a saved front door before it counts as broken: about 85 s. NetBird's proxy opens its tunnel
         * to this phone only when traffic arrives, so right after NetBird connects the first pings time out (measured 19 s).
         */
        private const val FRONT_DOOR_TRIES=10
        /** How long a Playit switch waits for the owner to free an agent slot on playit.gg. */
        private const val OVER_LIMIT_WAIT_MS=15*60_000L
        /** "Set up NetBird (free)" waits this long for NetBird's connect and sign-in, and tries making the ports this often. */
        private const val FRONT_DOOR_SETUP_MS=15*60_000L
        private const val FRONT_DOOR_SETUP_TRIES=4
        /** The owner turned on Regional Optimized, and once confirmed its warning. */
        private const val PREF_REGIONAL_ENABLED="regional-enabled"
        private const val PREF_EXPERIMENTAL_ACK="regional-warning-ack"
        /** Set once Android first killed the server and Phoenix explained how to prevent it. */
        private const val PREF_KILL_NOTICE="system-kill-notice-shown"
        // Compiled once: consume() runs for every server log line on the thread that drains the server's output.
        private val RUNTIME_PID=Regex("PHOENIX_RUNTIME_START.*pid=(\\d+)")
        private val AVERAGE_TICK=Regex("Average time per tick: ([0-9.]+)ms")
        private val TICK_PERCENTILES=Regex("Percentiles: P50: ([0-9.]+)ms P95: ([0-9.]+)ms P99: ([0-9.]+)ms\\. Sample: (\\d+)")
        private val PLAYER_LIST=Regex("players online: (.*)")
        private val WORLD_SEED=Regex("Seed: \\[?(-?\\d+)")
        private val TICK_PROFILE=Regex("Stopped tick profiling after ([0-9.]+) second\\(s\\) and (\\d+) tick\\(s\\) \\(([0-9.]+) tick\\(s\\) per second\\)")
        private val VM_RSS=Regex("VmRSS:\\s+(\\d+)")
        private const val MB=1_048_576L
        private const val SERVER_LOG_LIMIT=8L*MB
        private const val EXPORT_LIMIT=16L*MB
        /** While nobody looks at Phoenix, a measurement is recorded only this often. */
        private const val EXPORT_EVERY_MS=10_000L
    }
    private val work=Executors.newSingleThreadExecutor()
    private val sampler=Executors.newSingleThreadScheduledExecutor()
    private val prefs=app.getSharedPreferences("phoenix-stage10a",Context.MODE_PRIVATE)
    init {
        // Minecraft.How HTTP 429 cooldown survives app restarts so a restart cannot hammer the provider.
        MinecraftHowClient.restoreCooldown(prefs.getLong("minecraft-how-cooldown-until",0L))
        MinecraftHowClient.cooldownSink={ deadline -> prefs.edit().putLong("minecraft-how-cooldown-until",deadline).apply() }
    }
    private val netBird=NetBirdNetworkManager(app)
    private val server=File(app.filesDir,"servers/baseline").apply { mkdirs() }
    /**
     * Where Minecraft keeps its world folders and where world free-space checks look. The server root today; external
     * storage (lane 9) overrides only this. A directory other than [server] is passed to Minecraft as --universe.
     */
    private fun worldContainerDir():File=when(val location=worldLocation()) {
        is WorldLocation.Internal -> location.container
        is WorldLocation.Card -> location.container.apply { mkdirs() }
        // Never fall back to internal once the world lives on a card.
        is WorldLocation.CardMissing -> throw IllegalStateException(ExternalStorage.startRefusal(location))
    }
    private val volumes by lazy { AndroidVolumeProvider(app) }
    private val worldMove by lazy { WorldStorageMigration(File(app.filesDir,"world-move.properties")) }
    /** Internal (today's world, untouched) until a card is chosen and a verified move switched. Storage I/O: call off the main thread. */
    private fun worldLocation():WorldLocation {
        // No card chosen: exactly today's behaviour, without touching StorageManager or the journal.
        val uuid=prefs.getString("world-card-uuid",null) ?: return WorldLocation.Internal(server)
        return ExternalStorage.resolve(uuid,worldMove.journal().phase==WorldStorageMigration.Phase.SWITCHED,volumes.volumes(),server)
    }
    /** Phone and microSD used/free plus where worlds live, for Home and Worlds. Storage I/O: call off the main thread. */
    fun storageSummary():StorageSummary {
        val internal=StatFs(server.absolutePath)
        val all=runCatching { volumes.volumes() }.getOrDefault(emptyList())
        val location=runCatching { worldLocation() }.getOrElse { WorldLocation.Internal(server) }
        val card=(location as? WorldLocation.Card)?.volume ?: ExternalStorage.usableCards(all).firstOrNull()
        return StorageSummary(internal.totalBytes,internal.availableBytes,card?.label,card?.totalBytes ?: 0,card?.freeBytes ?: 0,
            card?.state,location !is WorldLocation.Internal,ExternalStorage.label(location),
            (location as? WorldLocation.CardMissing)?.reason)
    }
    private fun provider():BaselineProvider=if(prefs.getString("server-provider","fabric")=="vanilla")BaselineProvider.VANILLA else BaselineProvider.FABRIC
    fun selectProvider(vanilla:Boolean)=launchAction { stopped(); prefs.edit().putString("server-provider",if(vanilla)"vanilla" else "fabric").apply(); append("Provider selected: ${provider().id()}") }
    private val evidence=File(app.filesDir,"evidence").apply { mkdirs() }
    private val exportFile=File(evidence,"samples.jsonl")
    private val logLock=Any()
    private val abi=Build.SUPPORTED_ABIS.firstOrNull { it=="arm64-v8a" || it=="x86_64" } ?: "unsupported"
    private val home=File(app.filesDir,"runtime/$abi")
    private val native=File(app.applicationInfo.nativeLibraryDir)
    private val cancel=AtomicBoolean(false)
    @Volatile private var busy=false
    @Volatile private var progress:String?=null
    @Volatile private var error:String?=null
    @Volatile private var runtimeResult=prefs.getString("runtime-result","Java 25 probe has not run")!!
    @Volatile private var jvmMetrics=JSONObject()
    @Volatile private var processId:Int?=null
    /** True when this run took over a server that an earlier run of the app left running (see reconnect). */
    @Volatile private var reconnected=false
    /** True while a reconnect hands the server to the controller: the wrapper's start then launches nothing new. */
    @Volatile private var attaching=false
    @Volatile private var mspt:Double?=null
    @Volatile private var tickMetrics=JSONObject()
    @Volatile private var profilerMetrics=JSONObject()
    @Volatile private var listedPlayers:List<String> = emptyList()
    @Volatile private var actualWorldSeed:String?=null
    private var lastTickRequest=0L
    private var lastExportSample=0L
    /** evidence/server.log, kept open and flushed by the sampler instead of reopened for every line. */
    private var serverLog:java.io.Writer?=null
    private var serverLogBytes=0L
    private var previousJvmCpuTime:Long?=null
    private var previousJvmSampleNanos:Long?=null
    /** Everything the Console tab shows, in arrival order: server output, what the user sent and Phoenix's notes. */
    private val consoleLines=ArrayDeque<ConsoleEntry>()
    private var consoleSeq=0L
    /** Replies to Phoenix's own status queries arrive in this window; they stay out of the console. */
    @Volatile private var pollRepliesUntil=0L
    /** A command the user just typed may ask for the same output (for example /list); show it then. */
    @Volatile private var userCommandUntil=0L
    private val autoSetupLock=Any()
    private val autoSetupInFlight=AtomicBoolean(false)
    @Volatile private var autoNetworkSetupState=prefs.getString(PREF_AUTO_SETUP_STATE,"IDLE") ?: "IDLE"
    @Volatile private var autoNetworkSetupDetail=prefs.getString(PREF_AUTO_SETUP_DETAIL,"") ?: ""
    @Volatile private var dnsState=prefs.getString(PREF_DNS_STATE,null)
        ?: autoNetworkSetupDetail.takeIf { it.isNotBlank() }
        ?: if(prefs.contains("minecraft-hostname")) "Saved hostname loaded. Public DNS has not been checked in this session."
        else "Choose a hostname through Minecraft.How, then verify its DNS records"
    @Volatile private var netBirdAccountDetail=""
    @Volatile private var hostnameCheckName:String?=null
    @Volatile private var hostnameCheckDetail="Choose a hostname. You can check and save it while the server is stopped."
    @Volatile private var bedrockState="Bedrock not checked yet"
    @Volatile private var minecraftHowLinked=false
    @Volatile private var minecraftHowState="Sign in once to set up your public hostname"
    @Volatile private var minecraftHowZones:List<String> = emptyList()
    @Volatile private var minecraftHowHasSession=false
    @Volatile private var minecraftHowChecking=false
    private val accountRefreshing=java.util.concurrent.atomic.AtomicBoolean(false)
    private val accountWork=Executors.newSingleThreadExecutor()
    /** The bundled spawn preparation mod; null only if its manifest asset is unreadable (the app then offers nothing of it). */
    private val spawnPrepArtifact:SpawnPrepArtifact? = runCatching { SpawnPrepManifest.parse(app.assets.open(SpawnPrepManifest.ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
    private val modManager=ModManager(File(server,"mods"),runCatching {
        val rows=JSONObject(app.assets.open("CROSSPLAY_MANIFEST.json").bufferedReader().use { it.readText() }).getJSONArray("artifacts")
        (0 until rows.length()).map { rows.getJSONObject(it).getString("fileName") }.toSet()
    }.getOrDefault(emptySet()) + setOfNotNull(spawnPrepArtifact?.fileName))
    private val modPacks=ModPackStore(File(server,"modpacks"),PINNED_MOD_IDS)
    /** The newest pack import result and mod rollback, shown once on the Mods tab. */
    @Volatile private var packImport:PackImport?=null
    @Volatile private var modRollback:String?=null
    private val pinnedMetaCache=ConcurrentHashMap<String,Pair<String,ModMeta?>>()
    private val serverTarget by lazy {
        val manifest=JSONObject(app.assets.open("DEPENDENCY_MANIFEST.json").bufferedReader().use { it.readText() })
        // The server runs on Phoenix's embedded Java 25 runtime, not on the app's ART.
        ServerTarget(manifest.optString("minecraftVersion","26.2"),manifest.optString("fabricLoaderVersion","0.19.5"),25)
    }
    private val worldManager=WorldManager(server,File(app.filesDir,"backups"))
    /** Typed copy of the latest device sample for the dashboard; the JSON form is kept for export. */
    private data class DeviceStats(val memAvailable:Long?=null,val memTotal:Long?=null,val storageFree:Long?=null,val storageTotal:Long?=null,
        val battery:Int?=null,val charging:Boolean=false,val batteryTemp:Double?=null,val thermal:Int=0,val rss:Long?=null)
    @Volatile private var stats=DeviceStats()
    /** When each online player joined, from the server log; cleared when the server stops. */
    private val joinedAt=ConcurrentHashMap<String,Long>()
    @Volatile private var chunky=ChunkyStatus()
    /** The spawn preparation mod's live run (idle until the mod logs); [spawnPrepLock] guards its read-modify-write updates. */
    @Volatile private var spawnPrep=SpawnPrepStatus()
    private val spawnPrepLock=Any()
    /** When the mod last logged, so a long preparation never counts as a stuck start. */
    @Volatile private var spawnPrepLastLineAt=0L
    /** Where worlds lived at the last launch (differs from [server] only on a card); the marker file is read from there. */
    @Volatile private var spawnPrepContainer:File?=null
    private var spawnPrepMarkerCache:Pair<String,SpawnPrepLast?>?=null
    /** Mod ids enabled when the running server started; mods changed later load at the next start. */
    @Volatile private var modIdsAtStart:Set<String> = emptySet()
    @Volatile private var pendingRestart=false
    /** Counted down when the server reports "Saved the game" during a live backup. */
    @Volatile private var saveFlushed:CountDownLatch?=null
    /** The UI's last status read; the sampler idles when nothing runs and nobody looks. */
    @Volatile private var lastDashboardRead=0L
    private var consoleVersion=0L
    private var consoleCopy:Pair<Long,List<ConsoleEntry>> = -1L to emptyList()
    private val propertiesLock=Any()
    private var propertiesCache:Pair<Long,Properties>?=null
    @Volatile private var opsCache:Pair<Long,Set<String>>?=null
    @Volatile private var userCache:Pair<Long,Map<String,String>>?=null
    private var probeHost:ProcessRuntimeHost?=null
    private val host=ProcessRuntimeHost { processBuilder(false) }
    private val controller:ServerController=ServerController(object:RuntimeHost {
        override fun start(listener:RuntimeHost.Listener) { if(!attaching) prepareSpawnPrepForStart(); host.start(object:RuntimeHost.Listener {
            override fun onLine(line:String) { consume(line); listener.onLine(line) }
            override fun onExit(code:Int) { val was=controller.snapshot().state(); prefs.edit().remove(PREF_HOSTING_SINCE).apply(); forgetRuntime(); reconnected=false; onServerExit(); closeServerLog(); processId=null; mspt=null; tickMetrics=JSONObject(); profilerMetrics=JSONObject(); listedPlayers=emptyList(); joinedAt.clear(); modIdsAtStart=emptySet(); chunky=if(chunky.state=="DONE") chunky else ChunkyStatus(); spawnPrep=SpawnPrepStatus(); spawnPrepLastLineAt=0L; bedrockState="Bedrock stopped (ports stay reserved)"; listener.onExit(code)
                // Phoenix moves the controller to STOPPING or RESTARTING before it ends the server; anything else wasn't asked for.
                if(was==ServerController.State.ONLINE || was==ServerController.State.STARTING) runCatching { afterUnexpectedExit(code,was==ServerController.State.ONLINE) }
                // A clean stop after a learning start wrote the recording: build the cache while the server is stopped.
                if((code==0 || code==143) && aotMode==AotCache.Mode.RECORD) runCatching { assembleAotCache() } }
        }) }
        override fun command(command:String)=host.command(command)
        override fun forceStop()=host.forceStop()
        override fun terminate()=host.terminate()
        override fun isAlive()=host.isAlive
    // Stop: "stop" (saves), then after 60 s SIGTERM (Minecraft's shutdown hook saves), then after 30 s more SIGKILL.
    },{ MinecraftStatusProbe.available("127.0.0.1",javaListenerPort()) },Duration.ofMinutes(5),Duration.ofSeconds(60),Duration.ofSeconds(30))
    init {
        if(autoNetworkSetupState=="PENDING" || autoNetworkSetupState=="RUNNING") {
            val requestId=prefs.getString(PREF_AUTO_SETUP_ID,null)
            setAutoNetworkSetupState(requestId,"FAILED",
                "The last setup was interrupted. Tap Set up online play to finish it; nothing is written twice.")
        }
        // A marker left from a running server means the app was closed while it hosted. Its Java process may still run, so
        // one second later Phoenix looks for it and takes it over; only when none is found is the closed notice shown.
        prefs.getLong(PREF_HOSTING_SINCE,0L).takeIf { it>0L }?.let { since ->
            busy=true; progress="Checking for a Minecraft server that is still running"
            sampler.schedule({ reconnectAtLaunch(since) },1,TimeUnit.SECONDS)
        }
        // Clear crash leftovers before any new daemon owns its own temporary file.
        if(File(server,"server.jar").isFile && File(server,"fabric-server-launch.jar").isFile) controller.installed()
        sampler.scheduleWithFixedDelay({ runCatching { sample() }.onFailure { error="Device sample unavailable: ${it.javaClass.simpleName}" } },0,2,TimeUnit.SECONDS)
        sampler.execute {
            runCatching { pairingHost.ensureRunning() }
            if(netBird.regionalProxy().stage in setOf("WAITING_FOR_PROXY","PROXY_ONLINE")) scheduleProxyChecks()
        }
        migrateRoute()
        // An add-on setup interrupted by closing Phoenix carries on (after construction: the watch's fields are set up later).
        sampler.schedule({ if(prefs.getBoolean(PREF_FRONT_DOOR_SETUP,false)) watchFrontDoorSetup() },5,TimeUnit.SECONDS)
        // A saved custom name is re-checked against public DNS once, well after launch; DNS only, no account calls.
        sampler.schedule({ runCatching { if(prefs.getString("minecraft-hostname",null)!=null) endpointsFor(selectedRoute())?.let { e -> routeWork.execute { runCatching { validateCustomName(e) } } } } },20,TimeUnit.SECONDS)
        // Traffic through the regional proxy, once a minute while players use it: one JNI read, no network request.
        sampler.scheduleWithFixedDelay({ runCatching { if(regionalRouteActive() && host.isAlive) recordProxyTraffic() } },60,60,TimeUnit.SECONDS)
        sampler.scheduleWithFixedDelay({ runCatching { healthCheck() } },120,120,TimeUnit.SECONDS)
        sampler.scheduleWithFixedDelay({ runCatching { thermalCheck() }.onFailure { append("Heat check: ${it.message ?: it.javaClass.simpleName}") } },30,30,TimeUnit.SECONDS)
        // Android asks for memory back once Phoenix's screens are hidden; its own caches are rebuilt when they are needed.
        app.registerComponentCallbacks(object:ComponentCallbacks2 {
            override fun onTrimMemory(level:Int) { if(level>=ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseCaches() }
            override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {}
            @Deprecated("Deprecated in Android") override fun onLowMemory() { releaseCaches() }
        })
    }
    private fun launchAction(action:()->Unit) {
        synchronized(this) { if(busy) { error="Phoenix is busy with another task. Try again in a moment."; return }; busy=true; error=null }
        work.execute { try { action() }
            catch(stopped:StartStopped) { append("Phoenix: ${stopped.message}") } // the user's own Stop: not an error
            catch(e:Exception) { error=e.cause?.message ?: e.message ?: e.javaClass.simpleName; append("Phoenix: $error") } finally { busy=false; progress=null } }
    }
    /** A start that ended because the user stopped it (for example during spawn preparation); nothing failed. */
    private class StartStopped:Exception("The start was stopped from Phoenix before Minecraft was ready")
    /** A background follow-up: skipped silently while another task runs, and its failure is logged, not shown. */
    private fun launchQuietly(action:()->Unit) {
        synchronized(this) { if(busy) return; busy=true }
        work.execute { try { action() } catch(e:Exception) { append("Phoenix: ${e.message ?: e.javaClass.simpleName}") } finally { busy=false; progress=null } }
    }
    private fun setAutoNetworkSetupState(requestId:String?,state:String,detail:String,
        targetHostname:String?=null,targetAccountId:Long?=null,replaceTarget:Boolean=false) {
        val edit=prefs.edit().putString(PREF_AUTO_SETUP_STATE,state).putString(PREF_AUTO_SETUP_DETAIL,detail).putString(PREF_DNS_STATE,detail)
        if(requestId!=null)edit.putString(PREF_AUTO_SETUP_ID,requestId)
        if(replaceTarget) {
            if(targetHostname!=null)edit.putString(PREF_AUTO_SETUP_HOSTNAME,targetHostname) else edit.remove(PREF_AUTO_SETUP_HOSTNAME)
            if(targetAccountId!=null)edit.putLong(PREF_AUTO_SETUP_ACCOUNT_ID,targetAccountId) else edit.remove(PREF_AUTO_SETUP_ACCOUNT_ID)
        }
        check(edit.commit()) { "Could not save the automatic network setup status" }
        autoNetworkSetupState=state; autoNetworkSetupDetail=detail; dnsState=detail
    }
    /** Local read of the automatic setup stage for the NetBird screen; no network or Cloud request. */
    fun autoSetupStatus():Pair<String,String> = autoNetworkSetupState to autoNetworkSetupDetail
    /** Called only for an explicit user Connect/Authorize action, before the NetBird UI is opened. */
    fun requestAutoNetworkSetup() {
        synchronized(autoSetupLock) {
            if(autoSetupInFlight.get())return
            val hostname=prefs.getString("minecraft-hostname",null)?.takeIf { it.isNotBlank() }
            val accountId=prefs.getLong("minecraft-hostname-account",-1L).takeIf { it>0L }
            val sameTarget=prefs.getString(PREF_AUTO_SETUP_HOSTNAME,null)==hostname &&
                prefs.getLong(PREF_AUTO_SETUP_ACCOUNT_ID,-1L).takeIf { it>0L }==accountId
            if(sameTarget && autoNetworkSetupState in setOf("WAITING_FOR_NETBIRD","PENDING","RUNNING"))return
            if(sameTarget && autoNetworkSetupState=="COMPLETE" && prefs.getString("dns-pending-hostname",null)==null)return
            if(hostname==null && autoNetworkSetupState=="NEEDS_SIGN_IN" && sameTarget)return
            val hasMinecraftHowSession=if(hostname!=null)MinecraftHowSession.cookieHeader()!=null else false
            if(hostname!=null)minecraftHowHasSession=hasMinecraftHowSession
            if(sameTarget && autoNetworkSetupState=="NEEDS_SIGN_IN" && hostname!=null && !hasMinecraftHowSession)return
            val requestId=UUID.randomUUID().toString()
            if(hostname==null) {
                setAutoNetworkSetupState(requestId,"NEEDS_SIGN_IN",
                    "Sign in to Minecraft.How and save a hostname first (Settings › Server address).",
                    replaceTarget=true)
            } else if(!hasMinecraftHowSession) {
                minecraftHowLinked=false
                minecraftHowState="Not signed in"
                setAutoNetworkSetupState(requestId,"NEEDS_SIGN_IN",
                    "Sign in to Minecraft.How to finish setting up $hostname.",
                    targetHostname=hostname,targetAccountId=accountId,replaceTarget=true)
            } else {
                setAutoNetworkSetupState(requestId,"WAITING_FOR_NETBIRD",
                    "Waiting for NetBird to connect…",
                    targetHostname=hostname,targetAccountId=accountId,replaceTarget=true)
            }
        }
    }
    /** Called once from the NetBird SDK's successful account/peer transition, never from status polling. */
    fun continueNetBirdSetup() {
        if(prefs.getBoolean(PREF_FRONT_DOOR_SETUP,false)) watchFrontDoorSetup()
        val requestId=synchronized(autoSetupLock) {
            if(autoNetworkSetupState!="WAITING_FOR_NETBIRD")return
            if(!autoSetupInFlight.compareAndSet(false,true))return
            val id=prefs.getString(PREF_AUTO_SETUP_ID,null)
            if(id.isNullOrBlank()) {
                autoSetupInFlight.set(false)
                setAutoNetworkSetupState(null,"FAILED","Setup couldn't start. Tap Set up online play to try again.")
                return
            }
            setAutoNetworkSetupState(id,"PENDING","NetBird connected. Checking your Minecraft.How hostname…")
            id
        }
        val admitted=synchronized(this) {
            if(busy)false else { busy=true; error=null; true }
        }
        if(!admitted) {
            autoSetupInFlight.set(false)
            setAutoNetworkSetupState(requestId,"FAILED","Phoenix was busy with another task. Tap Set up online play again when it finishes.")
            return
        }
        try {
            work.execute {
                try {
                    progress="Checking your Minecraft.How hostname"
                    setAutoNetworkSetupState(requestId,"RUNNING","Checking your hostname and creating the public ports…")
                    val hostname=prefs.getString(PREF_AUTO_SETUP_HOSTNAME,null)
                        ?: throw MinecraftHowSignInRequired("No saved Minecraft.How hostname is available. Sign in to the owning account and save the same hostname; Phoenix will not reserve a new one.")
                    val owner=prefs.getLong(PREF_AUTO_SETUP_ACCOUNT_ID,-1L)
                    check(prefs.getString("minecraft-hostname",null)==hostname && prefs.getLong("minecraft-hostname-account",-1L)==owner) {
                        "The saved hostname changed meanwhile. Tap Set up online play again."
                    }
                    val result=saveMinecraftHostnameAction(hostname,allowStopped=true)
                    if(result.state==MinecraftHowClient.EnsureState.NEEDS_SIGN_IN) {
                        throw MinecraftHowSignInRequired(result.detail)
                    }
                    if(result.state!=MinecraftHowClient.EnsureState.SAVED) {
                        val detail="Setup stopped: ${result.detail}"
                        setAutoNetworkSetupState(requestId,"FAILED",detail)
                    } else {
                        setAutoNetworkSetupState(requestId,"COMPLETE","DNS records saved for $hostname.")
                    }
                } catch(failure:MinecraftHowSignInRequired) {
                    minecraftHowLinked=false
                    minecraftHowState=failure.message ?: "Sign in to the Minecraft.How account that owns the saved hostname."
                    setAutoNetworkSetupState(requestId,"NEEDS_SIGN_IN","Sign in to Minecraft.How to continue.")
                } catch(failure:Exception) {
                    val safeDetail=failure.message?.takeIf { it.isNotBlank() } ?: "Automatic public network setup failed."
                    setAutoNetworkSetupState(requestId,"FAILED","Setup stopped: $safeDetail")
                    append("Automatic NetBird/Minecraft.How setup stopped: $safeDetail")
                } finally {
                    autoSetupInFlight.set(false)
                    busy=false
                    progress=null
                }
            }
        } catch(failure:Exception) {
            autoSetupInFlight.set(false)
            busy=false
            progress=null
            setAutoNetworkSetupState(requestId,"FAILED","Setup couldn't start. Try again; nothing was changed.")
        }
    }
    private class MinecraftHowSignInRequired(message:String):Exception(message)
    private fun stopped() { check(!host.isAlive && probeHost?.isAlive!=true && !controller.isActive) { "Stop the server before modifying worlds or installation" } }
    fun install(accepted:Boolean,name:String,seed:String)=launchAction {
        stopped(); require(accepted) { "Minecraft EULA acceptance is required" }
        require(name.isNotBlank() && name.length<=64 && name.none { it=='\n' || it=='\r' || it=='\u0000' }) { "Invalid server name" }
        require(seed.length<=128 && seed.none { it=='\n' || it=='\r' || it=='\u0000' }) { "Invalid world seed" }
        cancel.set(false); progress="Preparing Java 25 data"; ensureRuntime()
        val manifest=app.assets.open("DEPENDENCY_MANIFEST.json").bufferedReader().use { it.readText() }
        Provisioner().install(PinnedDistribution.fromJson(manifest),server.toPath(),{ cancel.get() }) { p -> progress="${p.artifactId()}: ${p.totalBytesReceived()/1048576} / ${p.totalBytesExpected()/1048576} MiB" }
        EulaDecisionWriter.writeUserDecision(server.toPath(),true)
        if(!File(server,"server.properties").exists()) {
            val config=Properties().apply { setProperty("motd",name); setProperty("level-name","world"); setProperty("level-seed",seed); setProperty("server-port","25565"); setProperty("online-mode","true"); setProperty("enable-rcon","false"); setProperty("enable-query","false"); setProperty("view-distance","${PhoneTuning.VIEW}"); setProperty("simulation-distance","${PhoneTuning.SIMULATION}"); setProperty("sync-chunk-writes","false"); setProperty("max-players","20") }
            File(server,"server.properties").outputStream().use { config.store(it,"Phoenix unoptimized baseline") }
        }
        prefs.edit().putString("server-name",name).putString("world-seed",seed).apply(); controller.installed().get()
    }
    fun cancelInstall() { cancel.set(true) }
    private fun ensureRuntime() {
        check(abi!="unsupported") { "No runtime for this device ABI" }
        check(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)==4096L) { "This Java 25 build requires 4 KB pages; a 16 KB runtime rebuild is pending" }
        val text=app.assets.open("runtime/$abi/runtime-manifest.json").bufferedReader().use { it.readText() }
        val manifest=JSONObject(text)
        if(File(home,"manifest.json").takeIf { it.isFile }?.readText()!=text) {
            val hashes=mutableMapOf<String,String>(); val data=manifest.getJSONArray("data")
            for(i in 0 until data.length()) { val entry=data.getJSONObject(i); hashes[entry.getString("path")]=entry.getString("sha256") }
            app.assets.open("runtime/$abi/jre-data.zip").use { RuntimeLayout.unpackData(it,home.toPath(),hashes) }
        }
        val entries=mutableListOf<RuntimeLayout.NativeEntry>(); val natives=manifest.getJSONArray("native")
        for(i in 0 until natives.length()) { val n=natives.getJSONObject(i); entries+=RuntimeLayout.NativeEntry(n.getString("runtimePath"),n.getString("installedName"),n.getString("sha256")) }
        RuntimeLayout.linkNative(home.toPath(),native.toPath(),entries)
        val hostManifest=JSONObject(app.assets.open("runtime/host-manifest.json").bufferedReader().use { it.readText() })
        check(RuntimeLayout.hash(File(native,"libphoenixjvm.so").toPath())==hostManifest.getJSONObject("nativeHosts").getString(abi)) { "Native runtime launcher does not match its build manifest" }
        File(home,"manifest.json").writeText(text)
        // Rewritten only when the APK's copy differs, so the files keep their timestamps: Java's class-data caches
        // (CDS, AOT) refuse to load when a class-path jar's timestamp changed.
        for(asset in listOf("runtime/phoenix-runtime.jar","phoenix-metrics.jar")) {
            val target=File(app.filesDir,asset.substringAfterLast('/'))
            val bytes=app.assets.open(asset).use { it.readBytes() }
            if(!target.isFile || target.length()!=bytes.size.toLong() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        }
        check(RuntimeLayout.hash(File(app.filesDir,"phoenix-runtime.jar").toPath())==hostManifest.getString("runtimeJarSha256")) { "Runtime bootstrap JAR hash mismatch" }
    }
    private fun processBuilder(probe:Boolean):ProcessBuilder {
        val command=mutableListOf(File(native,"libphoenixjvm.so").absolutePath,native.absolutePath,home.absolutePath)
        if(probe) command+=listOf("-Xms32m","-Xmx128m")
        else {
            reclaimMemory()
            val info=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            check(!info.lowMemory && info.availMem>=650L*1048576) { "Insufficient available device memory for hosting" }
            val fixed=prefs.getInt("server-memory-mb",0)
            val heapMb=if(fixed>0) fixed.toLong() else ServerMemory.dynamicMb(info.availMem)
            if(fixed>0 && fixed.toLong()*1048576>info.availMem) show(Console.PHOENIX_PREFIX+"Server memory is set to ${ServerMemory.label(fixed)}, but only ${info.availMem/1048576} MB is free now. Android may close the server; Dynamic is safer.")
            prefs.edit().putLong("heap-max-mb",heapMb).apply()
            command+=listOf("-Xms128m","-Xmx${heapMb}m","-Djava.awt.headless=true","-javaagent:${File(app.filesDir,"phoenix-metrics.jar")}=${JvmTuning.agentArguments()}","--enable-native-access=ALL-UNNAMED")
            // Opt-in only (off by default, see PERFORMANCE_QUICK_WINS.md); the pinned launch is unchanged without it.
            command+=JvmTuning.flags(prefs.getBoolean(JvmTuning.PREF,false),heapMb,app.cacheDir)
            aotMode=runCatching { aotModeNow() }.getOrDefault(AotCache.Mode.NONE)
            command+=AotCache.flags(aotMode,app.cacheDir)
            if(aotMode==AotCache.Mode.RECORD) append("Faster starts: Java is learning this server's start-up. This start and its stop take a little longer, once; later starts are about 10 s faster.")
            // Phoenium's tile engine, chosen in Settings > Performance. Standard (the default) adds no flags.
            val phoeniumFlags=PhoeniumTuning.flags(prefs.getBoolean(PhoeniumTuning.PREF_ACCELERATION,PhoeniumTuning.DEFAULT_ACCELERATION),phoeniumEngineUsable(),phoeniumWorkers())
            command+=phoeniumFlags
            if(phoeniumFlags.isNotEmpty()) append("Phoenium acceleration: ${phoeniumFlags.joinToString(" ")}")
            // Test phones only: flags under measurement, without a rebuild (see PERFORMANCE_QUICK_WINS.md).
            if(BuildConfig.DEBUG) JvmTuning.experimentFlags(File(app.filesDir,JvmTuning.EXPERIMENT_FILE).takeIf { it.isFile }?.readText())
                .takeIf { it.isNotEmpty() }?.let { command+=it; append("JVM experiment flags: ${it.joinToString(" ")}") }
            // Geyser allows 10 Bedrock connections per source address by default. Through the NetBird relay every player
            // arrives from the relay's overlay address, so that cap would be the whole server's Bedrock limit. Raise it to
            // max-players; Geyser's join-rate throttle (3 attempts per 4 s per address) and Floodgate auth stay as they are.
            val maxPlayers=properties().getProperty("max-players")?.trim()?.toIntOrNull()?.coerceIn(10,100) ?: 20
            command+="-DGeyser.MaxConnectionsPerAddress=$maxPlayers"
        }
        command+=listOf("-Djava.io.tmpdir=${app.cacheDir.absolutePath}","-Djava.class.path=${File(app.filesDir,"phoenix-runtime.jar")}","--")
        if(probe) command+=listOf("org.phoenix.runtime.RuntimeProbe",File(evidence,"probe-data").absolutePath)
        else {
            command+=listOf("org.phoenix.runtime.JarMain",File(server,provider().serverArtifactName()).absolutePath,"nogui")
            val container=worldContainerDir()
            spawnPrepContainer=container
            container.takeIf { it.absoluteFile!=server.absoluteFile }?.let { command+=listOf("--universe",it.absolutePath) }
        }
        return ProcessBuilder(command).directory(server).apply {
            environment()["JAVA_HOME"]=home.absolutePath; environment()["HOME"]=server.absolutePath
            environment()["TMPDIR"]=app.cacheDir.absolutePath; environment()["LD_LIBRARY_PATH"]=native.absolutePath
        }
    }
    /**
     * Right before the server's Java starts (every start and restart): ends this app's own leftover server or agent
     * processes from an earlier run, drops Phoenix's caches and lets the app collect garbage, so Dynamic memory sizes the
     * heap from what is really free. Android doesn't let an app free other apps' memory, so nothing else is touched.
     * Never blocks or fails a start; one console line only when the phone measurably gained free memory.
     */
    private fun reclaimMemory() {
        val began=SystemClock.elapsedRealtime()
        fun free()=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
        val before=runCatching { free() }.getOrDefault(0L)
        val ended=runCatching { endLeftovers() }.onFailure { append("Leftover check skipped: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
        releaseCaches()
        runCatching { Runtime.getRuntime().gc() }
        val after=runCatching { free() }.getOrDefault(0L)
        val took=SystemClock.elapsedRealtime()-began
        if(ended.isNotEmpty()) append("Ended ${ended.joinToString()} left over from an earlier run")
        val gained=(after-before)/MB
        if(gained>=16) append("Freed $gained MB before start (phone free: ${"%.2f".format(before/1073741824.0)} → ${"%.2f".format(after/1073741824.0)} GB)")
        if(BuildConfig.DEBUG) logLine("[Phoenix debug] reclaim took $took ms, phone free ${before/MB} -> ${after/MB} MB, app heap ${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/MB} MB")
    }

    /** Phoenix's own rebuildable caches: decoded player faces, mod details and the console copy. */
    private fun releaseCaches() = runCatching { PlayerSkins.trim(); pinnedMetaCache.clear(); synchronized(consoleLines) { consoleCopy=-1L to emptyList() } }

    /**
     * Ends Phoenix's own server JVM or Playit agent left by a crash or a force-closed app (not a child of this app
     * process). A server gets SIGTERM, so its shutdown hook saves the world, and up to 20 s to exit; an agent is killed.
     */
    private fun endLeftovers():List<String> {
        val me=android.os.Process.myPid()
        val ended=mutableListOf<String>()
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().forEach { dir ->
            val pid=dir.name.toInt()
            if(pid==me) return@forEach
            val argv=runCatching { LeftoverServer.parseCmdline(File(dir,"cmdline").readText()) }.getOrDefault(emptyList())
            val command=argv.firstOrNull() ?: return@forEach
            val ppid=runCatching { File(dir,"stat").readText().substringAfterLast(')').trim().split(' ')[1].toInt() }.getOrNull() ?: return@forEach
            if(!Leftovers.isLeftover(command,ppid,me)) return@forEach
            // Only reached on a new start, after stopped() found no live server in the host (a taken-over one included), so a
            // live server of this install here is an orphan the takeover did not adopt, and it gets the clean stop below.
            val server=Leftovers.isServer(command)
            android.os.Process.sendSignal(pid,if(server) 15 else 9)
            if(server) { val until=SystemClock.elapsedRealtime()+20_000; while(File(dir,"cmdline").exists() && SystemClock.elapsedRealtime()<until) Thread.sleep(200) }
            ended+=(if(server) "an old server process" else "an old Playit agent")+" ($pid)"
        }
        return ended
    }

    fun probe()=launchAction {
        stopped(); ensureRuntime(); runtimeResult="Running actual Java 25 checks"
        val complete=java.util.concurrent.CountDownLatch(1); val lines=java.util.Collections.synchronizedList(mutableListOf<String>())
        probeHost=ProcessRuntimeHost { processBuilder(true) }
        probeHost!!.start(object:RuntimeHost.Listener {
            override fun onLine(line:String) { lines+=line; consume(line) }
            override fun onExit(code:Int) { runtimeResult="Java 25 probe exit $code"; complete.countDown() }
        })
        if(!complete.await(60,TimeUnit.SECONDS)) { probeHost!!.forceStop(); runtimeResult="Java 25 probe timed out" }
        File(evidence,"runtime-probe.log").writeText(lines.joinToString("\n")+"\n$runtimeResult\n")
        prefs.edit().putString("runtime-result",runtimeResult).apply()
    }
    /** [afterCrash]: Phoenix's own restart after an unexpected exit; it skips the automatic backup (the world may be damaged). */
    fun start(afterCrash:Boolean=false)=launchAction {
        stopped(); check(EulaDecisionWriter.isAccepted(server.toPath())) { "Accept the Minecraft EULA during setup" }
        // From the tap, so "Started in" includes Phoenix's own steps (the automatic backup among them).
        val pressed=SystemClock.elapsedRealtime()
        val cardRefusal=ExternalStorage.startRefusal(worldLocation())
        check(cardRefusal==null) { cardRefusal.orEmpty() }
        val pinned=PinnedDistribution.fromJson(app.assets.open("DEPENDENCY_MANIFEST.json").bufferedReader().use { it.readText() })
        for(artifact in pinned.artifacts()) check(RuntimeLayout.hash(File(server,artifact.fileName()).toPath())==artifact.sha256()) { "Installed server artifact hash mismatch: ${artifact.id()}" }
        // Fail early when the local Minecraft port is owned by another process.
        java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("0.0.0.0",javaListenerPort())) }
        cancel.set(false)
        finishAotAssembly()
        tuneForPhone()
        spawnProtectionOffByDefault()
        runCatching { enableManagementApi() }.onFailure { append("Management API not set up: ${it.message}") }
        if(!afterCrash) backUpBeforeStart()
        if(provider()==BaselineProvider.FABRIC) {
            CrossplayInstaller(app).ensure(server,{cancel.get()}) { progress=it }; allowUnsignedChat()
            allowTransfers()
            modManager.setAsideManagedDuplicates().takeIf { it.isNotEmpty() }?.let {
                append("Set aside ${it.joinToString()}: Phoenix already provides that mod (Bedrock crossplay or Simple Voice Chat)")
            }
        }
        progress="Starting Minecraft"
        startPressedAt=pressed
        readiness=Readiness(server=Check.PENDING)
        // A crash card stays through Phoenix's own restart after it, so an owner who was away still sees it.
        worldErrors=null; if(!afterCrash) serverCrash=null; synchronized(recentLines) { recentLines.clear() }
        // The selected route's agent or VPN connects while Minecraft boots instead of after it. Nothing else starts.
        runCatching { startSelectedRoute() }.onFailure { append("Public route not started: ${it.message}") }
        ensureRuntime(); jvmMetrics=JSONObject(); mspt=null; rememberStartMods()
        runCatching { applyVoiceHost() }.onFailure { append("Voice chat address not updated: ${it.message}") }
        launchAt=SystemClock.elapsedRealtime(); bedrockStartedAt=0L
        launchWithModRollback { controller.start().get() }
        reportStartTime()
        connectSavedRelay() // Reconnect only. Initial provisioning requires an explicit user action.
        syncEuVoice()
    }
    fun stop() {
        if(probeHost?.isAlive==true)probeHost!!.forceStop()
        controller.stop().exceptionally { error=it.cause?.message ?: it.message; null }
    }
    fun restart()=launchAction {
        check(host.isAlive) { "Start Minecraft before restarting" }
        rememberStartMods(); startPressedAt=SystemClock.elapsedRealtime()
        launchWithModRollback { controller.restart().get() }
        connectSavedRelay()
    }
    /**
     * Runs [launch] (a start or restart) and waits for "online". If Minecraft exits during startup while the optional mods
     * differ from the last good start, those mods are put back and the server is started once more. A good start makes
     * the current mods the new rollback point.
     */
    private fun launchWithModRollback(launch:()->Unit) {
        runCatching { append("Mod stack: ${modManager.stackIdentity()}") }
        runCatching { modManager.stackWarnings().forEach { append("Mod check: $it") } }
        minecraftLoading=false
        try { launch(); awaitOnline() }
        catch(stopped:StartStopped) { throw stopped } // the user stopped it: no mod rollback, no second start
        catch(failure:Exception) {
            val deadline=SystemClock.elapsedRealtime()+10000
            while((host.isAlive || controller.isActive) && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
            // Java itself refused to start (before Minecraft loaded) with Faster starts on: turn it off and start normally once.
            if(!host.isAlive && !controller.isActive && !minecraftLoading && (prefs.getBoolean(JvmTuning.PREF,false) || aotMode!=AotCache.Mode.NONE)) {
                prefs.edit().putBoolean(JvmTuning.PREF,false).putBoolean(AotCache.PREF,false).apply()
                runCatching { File(app.cacheDir,JvmTuning.CDS_ARCHIVE).delete() }
                deleteAotCache()
                append("Faster starts didn't work on this phone (Java refused its startup cache), so Phoenix turned it off and is starting normally.")
                progress="Starting Minecraft"; jvmMetrics=JSONObject(); mspt=null; error=null
                controller.start().get(); awaitOnline()
                if(host.isAlive) readiness=readiness.copy(server=Check.OK,serverReadyMs=startPressedAt.takeIf { it>0 }?.let { SystemClock.elapsedRealtime()-it })
                return
            }
            if(host.isAlive || controller.isActive || provider()!=BaselineProvider.FABRIC || !modManager.changedSinceGood()) throw failure
            val report=modManager.rollback() ?: throw failure
            val text="The server stopped while starting with the changed mods, so Phoenix put back the mods from the last good start: ${report.summary()}."
            modRollback=text; append(text)
            progress="Starting with the previous mods"; jvmMetrics=JSONObject(); mspt=null; rememberStartMods()
            controller.start().get(); awaitOnline()
            error=text
        }
        if(provider()==BaselineProvider.FABRIC) runCatching { modManager.markGood() }.onFailure { append("Mod journal: ${it.message}") }
        if(host.isAlive) prefs.edit().putLong(PREF_HOSTING_SINCE,System.currentTimeMillis()).apply()
        transfersLoaded=properties().getProperty("accepts-transfers")?.trim()=="true"
        managementLoaded=managementUsable()
        if(host.isAlive) readiness=readiness.copy(server=Check.OK,serverReadyMs=startPressedAt.takeIf { it>0 }?.let { SystemClock.elapsedRealtime()-it })
    }
    /**
     * Bedrock players joining through Geyser/Floodgate have no Mojang chat-signing key, so a server that
     * enforces secure profiles disables their chat ("missing profile public key"). Crossplay servers allow it.
     */
    /**
     * Once per server: vanilla's PC defaults (view and simulation distance 10, an fsync for every chunk write) make a
     * phone fall behind while players explore. Distances are only lowered if they were never changed from 10.
     */
    private fun tuneForPhone() {
        if(prefs.getBoolean("phone-tuned-v1",false) || !File(server,"server.properties").isFile) return
        val props=properties()
        val changes=PhoneTuning.changes(props.getProperty("view-distance"),props.getProperty("simulation-distance"),props.getProperty("sync-chunk-writes"))
        if(changes.isNotEmpty()) {
            writeProperties(changes)
            show(Console.PHOENIX_PREFIX+"Tuned for this phone: "+changes.entries.joinToString(", ") { "${it.key}=${it.value}" }+". Change distances in Settings › Server Settings.")
        }
        prefs.edit().putBoolean("phone-tuned-v1",true).apply()
    }
    /**
     * Before each start: a stored copy of the world in Worlds › Restore when it changed since the newest backup (or that
     * is over a day old), keeping the last few automatic ones. Skipped when storage is short; never fails the start.
     */
    private fun backUpBeforeStart() {
        val keep=prefs.getInt(AutoBackups.PREF,AutoBackups.DEFAULT_KEEP).takeIf { it>0 } ?: return
        if(worldContainerDir().absoluteFile!=server.absoluteFile) return // worlds on a card are backed up from Worlds
        val world=activeWorld()
        runCatching {
            val bytes=worldManager.worldBytes(world)
            if(!AutoBackups.enoughSpace(bytes,StatFs(server.absolutePath).availableBytes)) {
                append("Automatic backup skipped: not enough free storage for a copy of \"$world\" (${bytes/MB} MB)."); return
            }
            val began=SystemClock.elapsedRealtime()
            val made=worldManager.autoBackup(world,keep,System.currentTimeMillis()) { done -> progress="Backing up $world: ${done/MB} MB" } ?: return
            append("Backed up \"$world\" before starting (${made.sizeBytes/MB} MB in ${"%.1f".format((SystemClock.elapsedRealtime()-began)/1000.0)} s). Restore it from Worlds.")
        }.onFailure { append("Automatic backup skipped: ${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * Phoenix's default is no spawn protection: on a friends' server it only stops non-operators (every Bedrock player,
     * for one) from building near spawn. Once per server, Minecraft's default of 16 (or none written yet) becomes 0;
     * a size the owner chose in Settings afterwards is kept.
     */
    private fun spawnProtectionOffByDefault() {
        if(prefs.getBoolean("spawn-protection-off-v1",false)) return
        val current=properties().getProperty("spawn-protection")?.trim()
        if(current==null || current=="16") {
            writeProperties(mapOf("spawn-protection" to "0"))
            append("Spawn protection is off (Phoenix's default), so everyone can build near spawn. Change it in Settings › Server Settings.")
        }
        prefs.edit().putBoolean("spawn-protection-off-v1",true).apply()
    }
    private fun allowUnsignedChat() {
        val file=File(server,"server.properties").takeIf { it.isFile } ?: return
        val lines=file.readLines()
        if(lines.any { it.trim()=="enforce-secure-profile=false" }) return
        val updated=lines.filterNot { it.trimStart().startsWith("enforce-secure-profile=") }+"enforce-secure-profile=false"
        file.writeText(updated.joinToString("\n",postfix="\n"))
        append("Bedrock chat enabled: enforce-secure-profile=false (Bedrock players have no Java chat-signing key)")
    }
    /**
     * With Playit and a custom name, joins through the name arrive at Phoenix's front door and are handed to Playit
     * with Minecraft's Transfer, which the server must accept. Players still sign in with Mojang as usual.
     */
    private fun allowTransfers() {
        if(selectedRoute()!=PublicRoute.PLAYIT || prefs.getString("minecraft-hostname",null)==null) return
        if(properties().getProperty("accepts-transfers")?.trim()=="true") return
        writeProperties(mapOf("accepts-transfers" to "true"))
        append("Transfers enabled (accepts-transfers=true): joins through your custom name are handed to Playit")
    }
    @Volatile private var launchAt=0L
    /** Fabric logged "Loading Minecraft": the JVM itself started, so a later failure isn't about its options. */
    @Volatile private var minecraftLoading=false
    @Volatile private var bedrockStartedAt=0L
    @Volatile private var localMisses=0
    @Volatile private var javaMisses=0
    @Volatile private var bedrockMisses=0
    @Volatile private var doorMisses=0
    @Volatile private var healthTicks=0
    /**
     * While the server is online: a status ping on this phone every 2 minutes, and every 10 minutes the public addresses
     * (and the name's front door) again. Something is reported broken only after two misses in a row and fine again at the
     * first answer, so a single slow reply never turns Home red. Nothing runs while the server is starting or stopped.
     */
    private fun healthCheck() {
        if(controller.snapshot().state()!=ServerController.State.ONLINE || !host.isAlive) { localMisses=0; javaMisses=0; bedrockMisses=0; doorMisses=0; return }
        val answers=(1..2).any { runCatching { MinecraftStatusProbe.query("127.0.0.1",javaListenerPort(),"localhost",4000) }.isSuccess }
        localMisses=if(answers) 0 else localMisses+1
        val server=if(localMisses>=2) Check.FAILED else Check.OK
        if(readiness.server!=server && (server==Check.FAILED || readiness.server==Check.FAILED)) {
            readiness=readiness.copy(server=server)
            append(if(server==Check.FAILED) "The server isn't answering on this phone (two checks, 2 minutes apart). It may be overloaded or stuck; restart it if it doesn't recover."
                else "The server answers again")
        }
        if(++healthTicks%5==0 && answers) recheckPublic()
    }

    /** The periodic public check: quiet while things work, and only a change is written to the console. */
    private fun recheckPublic() = routeWork.execute {
        val route=selectedRoute()?.takeIf { it.isPublic } ?: return@execute
        if(routeSwitch!=null) return@execute
        val endpoints=endpointsFor(route) ?: return@execute
        val java=probeJava(endpoints,attempts=2)
        javaMisses=if(java) 0 else javaMisses+1
        val bedrock=probeBedrock(endpoints)
        bedrockMisses=if(bedrock!=false) 0 else bedrockMisses+1
        val javaCheck=when { java -> Check.OK; javaMisses>=2 -> Check.FAILED; else -> readiness.java }
        val bedrockCheck=when { bedrock==true -> Check.OK; bedrockMisses>=2 -> Check.FAILED; else -> readiness.bedrock }
        if(javaCheck!=readiness.java) append(if(javaCheck==Check.FAILED) "Friends can't reach Java through ${route.title} any more (two checks, 10 minutes apart)" else "Java answers through ${route.title} again")
        if(bedrockCheck!=readiness.bedrock && bedrock!=null) append(if(bedrockCheck==Check.FAILED) "Bedrock stopped answering through ${route.title}" else "Bedrock answers through ${route.title} again")
        readiness=readiness.copy(java=javaCheck,bedrock=bedrockCheck)
        // The name's front door: NetBird, the service and Phoenix's listener, as Java's server list sees them.
        val door=endpoints.nameJava
        val hostname=prefs.getString("minecraft-hostname",null)
        if(route==PublicRoute.PLAYIT && door!=null && hostname!=null) {
            val listed=(1..2).any { runCatching { MinecraftStatusProbe.query(door.host,door.port ?: return@any false,hostname,6000) }.isSuccess }
            doorMisses=if(listed) 0 else doorMisses+1
            if(doorMisses>=2) {
                doorMisses=0
                append("Java's server list can't reach $hostname through NetBird; Phoenix is reconnecting it")
                runCatching { serverPorts() }.getOrNull()?.let { ports -> runCatching { setUpFrontDoor(ports,endpoints) }.onFailure { frontDoorDetail=it.message } }
            }
        } else if(route==PublicRoute.PLAYIT && hostname!=null && frontDoorWanted() && netBird.euNamePort()>0) {
            // The front door isn't in use after a failed check: try the saved one again, so the name moves back by itself
            // once NetBird answers. No sign-in is needed for a service that already exists.
            runCatching { serverPorts() }.getOrNull()?.let { ports -> runCatching { setUpFrontDoor(ports,endpoints,quiet=true) } }
        }
    }

    // ---- Faster starts: Java 25's AOT cache, learned once per app install, runtime and server jar

    @Volatile private var aotMode=AotCache.Mode.NONE
    @Volatile private var aotAssembly:java.lang.Process?=null
    private fun aotFile(name:String)=File(app.cacheDir,name)
    private fun aotKey():String = AotCache.key(BuildConfig.VERSION_CODE.toLong(),
        app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime, File(home,"manifest.json").readText(),
        listOf(File(server,"server.jar"),File(server,"fabric-server-launch.jar")))
    private fun aotModeNow():AotCache.Mode {
        // The older opt-in CDS flags would compete for the same class-data mapping.
        if(prefs.getBoolean(JvmTuning.PREF,false)) return AotCache.Mode.NONE
        val key=aotKey()
        val mode=AotCache.mode(prefs.getBoolean(AotCache.PREF,AotCache.DEFAULT_ENABLED),aotFile(AotCache.CACHE).isFile,
            aotFile(AotCache.KEY).takeIf { it.isFile }?.readText(),key,StatFs(app.cacheDir.absolutePath).availableBytes)
        if(mode==AotCache.Mode.RECORD) { aotFile(AotCache.CACHE).delete(); aotFile(AotCache.KEY).delete(); aotFile(AotCache.CONFIG).delete() }
        return mode
    }
    fun setFasterStarts(enabled:Boolean) { prefs.edit().putBoolean(AotCache.PREF,enabled).apply(); if(!enabled) deleteAotCache() }
    // Phoenium's tile engine (Settings > Performance). Applies at the next start, like the mods.
    fun setPhoeniumAcceleration(enabled:Boolean) { prefs.edit().putBoolean(PhoeniumTuning.PREF_ACCELERATION,enabled).apply(); if(host.isAlive) pendingRestart=true }
    fun setPhoeniumWorkers(workers:Int) { prefs.edit().putInt(PhoeniumTuning.PREF_WORKERS,PhoeniumTuning.clampWorkers(workers,Runtime.getRuntime().availableProcessors())).apply(); if(host.isAlive) pendingRestart=true }
    /** Acceleration needs Fabric, an enabled Phoenium and its tile engine; Vanilla and older Phoenium builds get none. */
    private fun phoeniumEngineUsable():Boolean=runCatching { provider()==BaselineProvider.FABRIC && modManager.enabledPhoeniumEngine() }.getOrDefault(false)
    private fun phoeniumWorkers():Int=PhoeniumTuning.clampWorkers(prefs.getInt(PhoeniumTuning.PREF_WORKERS,0).takeIf { it>0 },Runtime.getRuntime().availableProcessors())
    private fun deleteAotCache() { aotAssembly?.destroy(); listOf(AotCache.CACHE,AotCache.CONFIG,AotCache.KEY,"${AotCache.CACHE}.part").forEach { aotFile(it).delete() } }

    /**
     * Builds the cache from the learning start's recording with Phoenix's own launcher and the same module options, while
     * the server is stopped (about 12 s of CPU). The recording is deleted afterwards; the cache is kept with its key.
     */
    private fun assembleAotCache() {
        val config=aotFile(AotCache.CONFIG).takeIf { it.isFile && it.length()>0 } ?: return
        val key=runCatching { aotKey() }.getOrNull() ?: return
        val part=aotFile("${AotCache.CACHE}.part")
        val command=listOf(File(native,"libphoenixjvm.so").absolutePath,native.absolutePath,home.absolutePath,"-Xmx512m",
            "-javaagent:${File(app.filesDir,"phoenix-metrics.jar")}","--enable-native-access=ALL-UNNAMED",
            "-XX:AOTMode=create","-XX:AOTConfiguration=${config.absolutePath}","-XX:AOTCache=${part.absolutePath}",
            "-Djava.io.tmpdir=${app.cacheDir.absolutePath}","-Djava.class.path=${File(app.filesDir,"phoenix-runtime.jar")}","--","org.phoenix.runtime.JarMain")
        Thread({
            val began=SystemClock.elapsedRealtime()
            try {
                val process=ProcessBuilder(command).directory(server).redirectErrorStream(true).apply {
                    environment()["JAVA_HOME"]=home.absolutePath; environment()["LD_LIBRARY_PATH"]=native.absolutePath
                    environment()["TMPDIR"]=app.cacheDir.absolutePath
                }.start()
                aotAssembly=process
                val output=process.inputStream.bufferedReader().readText()
                val done=process.waitFor(180,TimeUnit.SECONDS) && process.exitValue()==0 && part.isFile && part.length()>0
                if(done && part.renameTo(aotFile(AotCache.CACHE))) {
                    aotFile(AotCache.KEY).writeText(key)
                    append("Faster starts are ready (${aotFile(AotCache.CACHE).length()/MB} MB, built in ${(SystemClock.elapsedRealtime()-began)/1000} s): the next start skips Java's start-up work.")
                } else if(aotAssembly!=null) {
                    logLine("[Phoenix debug] AOT assembly failed: ${output.takeLast(600)}")
                    append("Faster starts couldn't be prepared this time; Phoenix learns again at the next start.")
                }
            } catch(e:Exception) { append("Faster starts couldn't be prepared: ${e.message ?: e.javaClass.simpleName}") }
            finally { aotAssembly=null; part.delete(); config.delete() }
        },"phoenix-aot-assembly").apply { isDaemon=true }.start()
    }

    /** A start waits up to 30 s for an assembly still running; after that the assembly is abandoned and learnt again later. */
    private fun finishAotAssembly() {
        val process=aotAssembly ?: return
        progress="Finishing faster starts"
        if(!process.waitFor(30,TimeUnit.SECONDS)) { aotAssembly=null; process.destroyForcibly() }
    }

    // ---- Crash watchdog: unexpected exits restart the server, world damage waits for the owner

    private val crashLoop=CrashLoop()
    @Volatile private var restartScheduled=false
    /** The current run's newest server lines, read after a crash for world damage. */
    private val recentLines=ArrayDeque<String>()
    @Volatile private var worldErrors:WorldErrors?=null
    /** The last crash the server's log reported (Minecraft's own, out of memory, or Java's); shown on Home until dismissed. */
    @Volatile private var serverCrash:ServerCrash?=null
    /** Android killed the server for the first time; Home explains how to prevent it until dismissed. */
    @Volatile private var killNotice=false
    /** An automatic restart is due; the hosting service stays in the foreground meanwhile. */
    fun restartPending()=restartScheduled

    /**
     * After the server ended without being asked to. Chunk or region errors in its log are shown on Home with the last
     * backup, and the server is then left stopped: it is never restored automatically, and restarting could save over
     * the damaged chunks. Otherwise a server that was online is started again after 5 s, at most 3 times in 15 minutes.
     */
    private fun afterUnexpectedExit(code:Int,wasOnline:Boolean) {
        val lines=synchronized(recentLines) { recentLines.toList() }
        val damage=ChunkErrors.find(lines)
        // Minecraft exits with 0 after its own crash, so the log, not the exit code, tells a crash from a quiet exit.
        val crash=ServerCrashes.find(lines)
        val how=crash?.let { "it crashed: ${it.summary}" } ?: ServerExit.describe(code)
        if(crash!=null) {
            serverCrash=crash
            append("The server crashed: ${crash.summary}." + (crash.report?.let { " Crash report: $it." } ?: "") +
                if(crash.savedAtStop) "" else " Its final save didn't run, so the world is as of its last autosave.")
        }
        if(damage.isNotEmpty()) {
            val world=activeWorld()
            val first=Console.parse(damage.first()).text.take(200)
            worldErrors=WorldErrors(world,damage.size,first,runCatching { worldManager.backups().firstOrNull { it.world==world } }.getOrNull())
            append("The server stopped ($how) after reporting ${damage.size} world error${if(damage.size==1) "" else "s"}, " +
                "first: $first. Phoenix didn't restart it. Restore the last backup from Home, or press Start to continue with the world as it is.")
            return
        }
        if(!wasOnline) return
        if(ServerExit.killed(code) && !prefs.getBoolean(PREF_KILL_NOTICE,false)) { prefs.edit().putBoolean(PREF_KILL_NOTICE,true).apply(); killNotice=true }
        val attempt=crashLoop.allow(SystemClock.elapsedRealtime()) ?: run {
            append("The server stopped unexpectedly again ($how): 3 times in 15 minutes, so Phoenix won't restart it by itself. Check the console, then press Start.")
            return
        }
        append("The server stopped unexpectedly ($how). Starting it again in 5 seconds (automatic restart $attempt of 3 in 15 minutes).")
        restartScheduled=true
        sampler.schedule({
            try { if(!host.isAlive && !controller.isActive) start(afterCrash=true) } finally { restartScheduled=false }
        },5,TimeUnit.SECONDS)
    }
    /** The server jar as the launch passes it to Java: the identity that a reconnect and endLeftovers check for. */
    private fun serverJarPath() = File(server,provider().serverArtifactName()).absolutePath
    /** Saves the server's pid and start time when it starts, and marks hosting, so a later launch knows to look for it. */
    private fun rememberRuntime(pid:Int) {
        val start=LeftoverServer.readProcess(pid).startTicks ?: return
        prefs.edit().putInt(PREF_RUNTIME_PID,pid).putLong(PREF_RUNTIME_START,start).putLong(PREF_HOSTING_SINCE,System.currentTimeMillis()).apply()
    }
    private fun forgetRuntime() { prefs.edit().remove(PREF_RUNTIME_PID).remove(PREF_RUNTIME_START).apply() }
    /**
     * Runs once, a second after a launch that follows a hosting session: takes over the Minecraft process that the earlier
     * run left running, or shows the closed notice when there is none. Nothing is started here.
     */
    private fun reconnectAtLaunch(since:Long) {
        try {
            val adopted=runCatching { reconnect() }.getOrElse { append("Phoenix could not reconnect to the Minecraft server: ${it.javaClass.simpleName}"); false }
            if(!adopted) {
                forgetRuntime()
                error=closedWhileRunning(since)
                prefs.edit().remove(PREF_HOSTING_SINCE).apply()
            }
        } finally { busy=false; progress=null }
    }
    private fun closedWhileRunning(since:Long):String {
        val at=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(since))
        return "Phoenix was closed unexpectedly while the server was running (started $at). Android may have stopped it, or the app crashed. " +
            "The world is saved up to its last autosave; start the server again."
    }
    /**
     * Hands a server that outlived the app to the controller as RUNNING, STARTING or STOPPING. A process whose pid now
     * belongs to something else is STALE and left alone, and nothing is signalled here. Returns whether one was taken over.
     */
    private fun reconnect():Boolean {
        val me=android.os.Process.myPid()
        val savedPid=prefs.getInt(PREF_RUNTIME_PID,0).takeIf { it>0 }
        val savedStart=prefs.getLong(PREF_RUNTIME_START,-1L).takeIf { it>=0L }
        val jar=serverJarPath()
        // Without a saved pid (an older build, or a start that never printed one) the server is looked for by its command line.
        val pid=savedPid ?: LeftoverServer.scanForServer(jar,me)
        val facts=pid?.let { LeftoverServer.readProcess(it) }
        val log=LeftoverServer.scanLog(runCatching { File(server,"logs/latest.log").readLines() }.getOrDefault(emptyList()))
        val outcome=LeftoverServer.classify(pid?.let { LeftoverServer.Candidate(it,savedStart) },facts,log,
            answering=MinecraftStatusProbe.available("127.0.0.1",javaListenerPort()),serverJar=jar)
        if(outcome==LeftoverServer.Outcome.STALE) { forgetRuntime(); append("The saved pid $pid now belongs to another process. Phoenix left it alone and did not reconnect."); return false }
        val target=when(outcome) {
            LeftoverServer.Outcome.RUNNING -> ServerController.State.ONLINE
            LeftoverServer.Outcome.STARTING -> ServerController.State.STARTING
            LeftoverServer.Outcome.STOPPING -> ServerController.State.STOPPING
            else -> return false
        }
        val id=pid ?: return false
        val startTicks=facts?.startTicks ?: return false
        val process=AdoptedProcess(id,startTicks,jar,File(server,"logs/latest.log"),stopping=outcome==LeftoverServer.Outcome.STOPPING)
        attaching=true
        try {
            host.attach(process)
            controller.adopt(target,LeftoverServer.uptimeMs(startTicks,LeftoverServer.systemUptimeSeconds())).get()
        } finally { attaching=false; host.attach(null) }
        reconnected=true; error=null
        managementLoaded=managementUsable()
        append("Reconnected to the Minecraft server that was still running (pid $id). Phoenix did not start a new one. Console input is off; Stop still works.")
        // Keeps the hosting notification up while the adopted server runs. HostingService has no handler for this action.
        runCatching { app.startForegroundService(Intent(app,HostingService::class.java).setAction("RECONNECTED")) }
        return true
    }
    /** Home's "Restore last backup" after world errors: the newest backup of that world, through the normal restore. */
    fun restoreLastBackup() { worldErrors?.backup?.let { restoreBackup(it.fileName) }; worldErrors=null }
    fun dismissWorldErrors() { worldErrors=null }
    fun dismissKillNotice() { killNotice=false }
    fun dismissServerCrash() { serverCrash=null }

    // ---- Heat: ease off before Android throttles the CPU

    /** The running server was started with Phoenix's local management API. */
    @Volatile private var managementLoaded=false
    /** The live simulation distance Phoenix lowered from while the phone is hot; null when nothing is eased off. */
    @Volatile private var easedFrom:Int?=null
    @Volatile private var coolSince:Long?=null
    private fun thermalEaseOff()=prefs.getBoolean(ThermalPolicy.PREF,true)

    /**
     * Once per server: Minecraft's management API on 127.0.0.1 only, without TLS, behind the random secret Minecraft
     * keeps in server.properties. Nothing outside the phone can reach it; Playit, NetBird and the front door forward
     * only the game ports. An API the owner set up themselves is left as it is. Its port is moved if another app took it.
     */
    private fun enableManagementApi() {
        val props=properties()
        val managed=prefs.getBoolean("management-api-v1",false)
        if(!managed && props.getProperty("management-server-enabled")?.trim()=="true") return
        val port=props.getProperty("management-server-port")?.trim()?.toIntOrNull()?.takeIf { managed && it in 1024..65535 && portFree(it) }
            ?: (25592..25599).firstOrNull(::portFree) ?: return
        val wanted=mapOf("management-server-enabled" to "true","management-server-host" to "127.0.0.1",
            "management-server-port" to "$port","management-server-tls-enabled" to "false")
        if(wanted.any { (k,v) -> props.getProperty(k)?.trim()!=v }) writeProperties(wanted)
        if(!managed) {
            prefs.edit().putBoolean("management-api-v1",true).apply()
            append("Turned on Minecraft's management API for this phone only (127.0.0.1:$port), so Phoenix can ease off when the phone gets hot.")
        }
    }
    private fun portFree(port:Int)=runCatching { java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1",port)) } }.isSuccess
    private fun managementUsable():Boolean { val p=properties(); return p.getProperty("management-server-enabled")?.trim()=="true" &&
        p.getProperty("management-server-tls-enabled")?.trim()=="false" && !p.getProperty("management-server-secret").isNullOrBlank() }
    private fun management():ManagementClient? {
        if(!managementLoaded) return null
        val p=properties()
        val port=p.getProperty("management-server-port")?.trim()?.toIntOrNull() ?: return null
        val secret=p.getProperty("management-server-secret")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ManagementClient(port,secret)
    }

    /**
     * Every 30 s while online: Android's thermal status and its 10-second headroom forecast. Approaching throttling,
     * the live simulation distance goes down one step (never written to server.properties, so a restart always brings
     * the owner's own value back); after two cool minutes it goes back up. Each change is one console line.
     */
    private fun thermalCheck() {
        if(controller.snapshot().state()!=ServerController.State.ONLINE || !host.isAlive) { easedFrom=null; coolSince=null; return }
        val pm=app.getSystemService(PowerManager::class.java)
        val status=pm.currentThermalStatus
        val headroom=if(Build.VERSION.SDK_INT>=30) runCatching { pm.getThermalHeadroom(10) }.getOrNull() else null
        val now=SystemClock.elapsedRealtime()
        coolSince=if(ThermalPolicy.isCool(status,headroom)) coolSince ?: now else null
        val eased=easedFrom
        val action=if(!thermalEaseOff()) { if(eased!=null) ThermalPolicy.Action.RESTORE else return }
            else ThermalPolicy.next(status,headroom,eased!=null,coolSince,now)
        if(action==ThermalPolicy.Action.NONE) return
        val api=management() ?: return
        val heat=if(status>=2) "Android's thermal level $status" else headroom?.takeIf { !it.isNaN() }?.let { "thermal headroom %.2f".format(it) } ?: "thermal level $status"
        if(action==ThermalPolicy.Action.EASE_OFF) {
            val live=(api.call(ManagementClient.SIMULATION_DISTANCE) as? Number)?.toInt() ?: return
            if(live<=ThermalPolicy.MIN_DISTANCE) return
            api.call(ManagementClient.SET_SIMULATION_DISTANCE,JSONObject().put("distance",live-1))
            easedFrom=live
            append("The phone is getting hot ($heat): simulation distance $live → ${live-1} until it cools down, so Android doesn't slow the server.")
        } else {
            val back=eased ?: return
            api.call(ManagementClient.SET_SIMULATION_DISTANCE,JSONObject().put("distance",back))
            easedFrom=null
            append(if(thermalEaseOff()) "The phone cooled down: simulation distance back to $back." else "Simulation distance back to $back.")
        }
    }
    fun setThermalEaseOff(enabled:Boolean) { prefs.edit().putBoolean(ThermalPolicy.PREF,enabled).apply() }

    /** One console line on where the start time went: Phoenix's checks, Minecraft until it answers, then Geyser. */
    private fun reportStartTime() {
        val pressed=startPressedAt.takeIf { it>0 } ?: return
        val now=SystemClock.elapsedRealtime()
        val parts=mutableListOf("Phoenix checks ${(launchAt-pressed)/1000.0} s","Minecraft ${(now-launchAt)/1000.0} s")
        val bedrock=bedrockStartedAt
        if(bedrock>launchAt) parts+="Bedrock ready ${(bedrock-pressed)/1000} s after Start"
        append("Started in ${(now-pressed)/1000} s (" + parts.joinToString(" · ") + ")" +
            if(prefs.getBoolean(JvmTuning.PREF,false)) " with JVM tuning" else "")
    }
    private fun connectSavedRelay() {
        progress="Connecting the public relay"
        val udpPort=bedrockListenerPort()
        val udpReady=provider()==BaselineProvider.FABRIC && runCatching { org.phoenix.core.network.BedrockStatusProbe.query("127.0.0.1",udpPort,2000) }.isSuccess
        bedrockState=if(udpReady)"Bedrock (Geyser) is answering on UDP $udpPort" else "Bedrock (Geyser) is not answering; check the console"
        // Idempotent: the selected route was started with the server; this restarts it only if it dropped.
        runCatching { startSelectedRoute() }.onFailure { append("Public route: ${it.message}") }
        checkPublicReachability()
    }
    private fun onServerExit() {
        readiness=Readiness()
        // Playit runs only while the server does (NetBird keeps its VPN as before). A restart brings the server back within
        // the grace period, so the agent isn't torn down and reconnected for it.
        if(selectedRoute()==PublicRoute.PLAYIT) sampler.schedule({
            routeWork.execute { if(!host.isAlive && !controller.isActive && selectedRoute()==PublicRoute.PLAYIT) { playit.stopAgent(); stopFrontDoor() } }
        },30,TimeUnit.SECONDS)
    }
    /** Installs from before explicit routes keep what they had: NetBird (EU or the regional proxy) when it has ports. */
    private fun migrateRoute() {
        if(prefs.contains(PREF_ROUTE)) return
        // A real store read: the status snapshot is only "checking" this early and would make NetBird installs look new.
        val hasPorts=netBird.hasSavedPorts()
        PublicRoute.migrate(null,hasPorts,prefs.getString(PREF_PUBLIC_ROUTE,null))?.let { prefs.edit().putString(PREF_ROUTE,it.key).apply() }
    }
    private fun javaListenerPort():Int=properties().getProperty("server-port","25565").trim().toInt().also { require(it in 1..65535) }
    @Volatile private var bedrockPortCache:Pair<Long,Int>?=null
    /** Geyser's UDP port, re-read only when its config or server.properties changes (the dashboard asks every second). */
    private fun bedrockListenerPort():Int {
        val config=File(server,"config/Geyser-Fabric/config.yml")
        val stamp=config.lastModified()*31+config.length()+File(server,"server.properties").lastModified()
        bedrockPortCache?.takeIf { it.first==stamp }?.let { return it.second }
        return readBedrockListenerPort(config).also { bedrockPortCache=stamp to it }
    }
    private fun readBedrockListenerPort(config:File):Int {
        if(!config.isFile) return 19132
        val block=config.readText().substringAfter("bedrock:").lineSequence().takeWhile { it.isBlank() || it.first().isWhitespace() }.toList()
        if(block.any { it.trim()=="clone-remote-port: true" }) return javaListenerPort()
        return block.firstOrNull { it.trim().startsWith("port:") }?.substringAfter(':')?.trim()?.toInt()?.also { require(it in 1..65535) } ?: 19132
    }
    private fun awaitOnline() {
        progress="Waiting for Minecraft to accept connections"
        var deadline=SystemClock.elapsedRealtime()+300000L
        var late=false
        while(true) {
            val s=controller.snapshot()
            // Spawn preparation logs every couple of seconds; as long as it does, the start is working, not stuck.
            spawnPrepLastLineAt.takeIf { it>0 }?.let { deadline=maxOf(deadline,it+300000L) }
            // The controller names a failed start (Minecraft's own line) or the exit code; say that rather than a guess.
            when(val step=StartWait.next(s.state(),s.detail(),host.isAlive,controller.startMayStillFinish(),SystemClock.elapsedRealtime()>=deadline)) {
                StartWait.Online -> {
                    if(late) append("Phoenix: Minecraft finished starting after the usual time; the server is online.")
                    return
                }
                StartWait.Stopped -> throw StartStopped()
                is StartWait.Failed -> throw IllegalStateException(step.reason)
                // Slow, not failed: keep waiting so a start that finishes still gets the relay, voice and mod journal.
                StartWait.Late -> if(!late) {
                    late=true
                    progress="Minecraft is taking longer than usual to start; Phoenix keeps waiting (Stop ends it)"
                    append("Phoenix: Minecraft has not finished starting after the usual time. Phoenix keeps waiting while Java runs; Stop ends it.")
                }
                StartWait.Waiting -> {}
            }
            Thread.sleep(100)
        }
    }
    fun command(value:String) { controller.command(value).exceptionally { error=it.cause?.message ?: it.message; null } }
    /** What the user typed in the Console tab: chat by default, a command when it starts with "/". */
    fun consoleInput(text:String) {
        val line=Console.toServerLine(text) ?: return
        if(text.trim().startsWith("/")) { userCommandUntil=SystemClock.elapsedRealtime()+4000; show(Console.SENT_PREFIX+"/"+line) }
        controller.command(line).exceptionally { error=it.cause?.message ?: it.message; null }
    }
    fun clearError() { error=null }
    fun forceStop() { controller.forceStop().exceptionally { error=it.cause?.message ?: it.message; null } }
    fun measureTicks() {
        controller.command("debug start").thenRun { sampler.schedule({ controller.command("debug stop") },10,TimeUnit.SECONDS) }
            .exceptionally { error=it.cause?.message ?: it.message; null }
    }
    fun runtimeActive()=host.isAlive || probeHost?.isAlive==true || controller.isActive
    private fun consume(line:String) {
        // The agent's own lines (measurements, idle-heap switch) go to the log only, never to the console.
        val metrics=line.contains("@@PHOENIX_")
        if(metrics) { if(line.contains("@@PHOENIX_METRICS ")) runCatching { jvmMetrics=JSONObject(line.substringAfter("@@PHOENIX_METRICS ")) } }
        else {
            val now=SystemClock.elapsedRealtime()
            val chunkyLine=line.contains("[Chunky]")
            if(chunkyLine) ChunkyLog.update(chunky,line)?.let { chunky=it }
            // The spawn preparation mod: one cheap substring test per line, and nothing more when the mod is off or absent.
            val prepLine=line.contains(SpawnPrepLog.TAG)
            if(prepLine) onSpawnPrepLine(line)
            // Chunky reports progress every few seconds; the Worlds tab shows it instead of the console.
            // Java's AOT cache reports (hundreds of "Skipping" lines once per learning stop) stay in the log; Phoenix sums them up.
            val aotReport=line.startsWith("[") && line.contains("][aot]")
            if(!(chunkyLine && ChunkyLog.isProgress(line)) && !(prepLine && SpawnPrepLog.isProgress(line)) && !(now<pollRepliesUntil && now>=userCommandUntil && Console.isStatusPollReply(line)) && !aotReport) show(line)
            if(busy) startupStep(line)?.let { progress=it }
            trackPlayers(line)
            if(line.contains("Saved the game")) saveFlushed?.countDown()
        }
        if(line.contains("PHOENIX_RUNTIME_START")) RUNTIME_PID.find(line)?.let { val pid=it.groupValues[1].toInt(); processId=pid; rememberRuntime(pid) }
        if(!minecraftLoading && line.contains("Loading Minecraft ")) minecraftLoading=true
        if(!metrics) synchronized(recentLines) { recentLines.addLast(line); if(recentLines.size>600) recentLines.removeFirst() }
        if(line.contains("Started Geyser on UDP port") && launchAt>0 && bedrockStartedAt==0L) {
            bedrockStartedAt=SystemClock.elapsedRealtime()
            if(controller.snapshot().state()==ServerController.State.ONLINE && startPressedAt>0)
                append("Bedrock (Geyser) ready ${(bedrockStartedAt-startPressedAt)/1000} s after Start")
        }
        // Minecraft tick query gives an average, never a p95/p99 or actual TPS measurement.
        if(line.contains("Average time per tick")) AVERAGE_TICK.find(line)?.let { mspt=it.groupValues[1].toDouble() }
        if(line.contains("Percentiles: P50")) TICK_PERCENTILES.find(line)?.let {
            tickMetrics=JSONObject().put("timestampMs",System.currentTimeMillis()).put("medianMspt",it.groupValues[1].toDouble()).put("p95Mspt",it.groupValues[2].toDouble()).put("p99Mspt",it.groupValues[3].toDouble()).put("sampleCount",it.groupValues[4].toInt()).put("method","Minecraft 26.2 tick query; recent sample window")
        }
        if(line.contains("players online: ")) PLAYER_LIST.find(line)?.let { listedPlayers=it.groupValues[1].split(',').map(String::trim).filter(String::isNotEmpty) }
        if(line.contains("Seed: ")) WORLD_SEED.find(line)?.let { actualWorldSeed=it.groupValues[1]; prefs.edit().putString("actual-world-seed",actualWorldSeed).apply() }
        if(line.contains("Stopped tick profiling")) TICK_PROFILE.find(line)?.let {
            profilerMetrics=JSONObject().put("timestampMs",System.currentTimeMillis()).put("durationSeconds",it.groupValues[1].toDouble()).put("ticks",it.groupValues[2].toLong()).put("tps",it.groupValues[3].toDouble()).put("method","Minecraft debug start/stop actual tick count and elapsed time; profiler adds observation overhead")
        }
        logLine(line)
    }
    private fun logLine(line:String) = synchronized(logLock) {
        runCatching {
            val writer=serverLog ?: File(evidence,"server.log").let { file ->
                if(file.length()>SERVER_LOG_LIMIT) file.writeText("")
                serverLogBytes=file.length()
                java.io.BufferedWriter(java.io.FileWriter(file,true),16384).also { serverLog=it }
            }
            val text="${System.currentTimeMillis()} $line\n"
            writer.write(text); serverLogBytes+=text.length
            if(serverLogBytes>SERVER_LOG_LIMIT) { writer.close(); serverLog=null }
        }
    }
    private fun flushServerLog() = synchronized(logLock) { runCatching { serverLog?.flush() } }
    private fun closeServerLog() = synchronized(logLock) { runCatching { serverLog?.close() }; serverLog=null }
    /**
     * Parsed here, on the thread reading the server, so the Console tab never parses in a frame. A stack trace line folds
     * into the entry above it (see Console.foldInto) instead of becoming a row of its own; each line is looked at once.
     */
    private fun show(line:String) {
        val raw=line.take(4096)
        synchronized(consoleLines) {
            val last=consoleLines.lastOrNull()
            val folded=Console.foldInto(last?.line,raw)
            if(last!=null && folded!=null) consoleLines[consoleLines.size-1]=ConsoleEntry(last.id,folded)
            else consoleLines.addLast(ConsoleEntry(++consoleSeq,Console.parse(raw)))
            while(consoleLines.size>400)consoleLines.removeFirst()
            consoleVersion++
        }
    }
    /** A Phoenix note: shown in the console and kept in evidence/server.log beside the server's own lines. */
    private fun append(line:String) { val text=line.removePrefix("Phoenix: "); show(Console.PHOENIX_PREFIX+text); logLine("[Phoenix] $text") }
    /** Plain-language startup progress read from Minecraft's own log, so the user can see it is working. */
    private fun startupStep(line:String):String?=when {
        line.contains("Starting minecraft server version") -> "Starting Minecraft"
        line.contains("Loading Floodgate") || line.contains("to boot Floodgate") -> "Loading Bedrock support"
        line.contains("Preparing level") -> "Loading the world"
        line.contains("Preparing spawn area: ") -> "Preparing the spawn area ${line.substringAfter("Preparing spawn area: ").trim()}"
        line.contains("Loading Geyser") -> "Starting Geyser for Bedrock players"
        line.contains("Started Geyser on UDP port") -> "Bedrock listener started"
        line.contains("Done (") -> "World loaded; finishing startup"
        else -> null
    }?.takeIf { controller.snapshot().state().let { it==ServerController.State.STARTING || it==ServerController.State.RESTARTING } }
    private fun sample() {
        flushServerLog()
        val now=SystemClock.elapsedRealtime()
        val watching=now-lastDashboardRead<10000
        val active=runtimeActive() || busy
        // Record a measurement at most every 10 s, and read the device only when that or the UI needs it.
        val record=active && now-lastExportSample>=EXPORT_EVERY_MS
        if(!watching && !record) return
        val snap=controller.snapshot()
        val info=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm=app.getSystemService(PowerManager::class.java)
        val stat=StatFs(server.absolutePath)
        val thermal=pm.currentThermalStatus
        val batteryPercent=battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL,0)*100/it.getIntExtra(BatteryManager.EXTRA_SCALE,100).coerceAtLeast(1) }
        val charging=battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?.let { it!=0 }
        val batteryTemp=battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)?.div(10.0)
        val rss=processId?.let { pid -> runCatching { VM_RSS.find(File("/proc/$pid/status").readText())?.groupValues?.get(1)?.toLong()?.times(1024) }.getOrNull() }
        stats=DeviceStats(info.availMem,info.totalMem,stat.availableBytes,stat.totalBytes,batteryPercent,charging ?: false,batteryTemp,thermal,rss)
        if(record) { lastExportSample=now; recordSample(snap,info,pm,stat,thermal,batteryPercent,charging,batteryTemp,rss) }
        // Tick and player queries feed the UI only; skip them while nobody is looking.
        if(watching && snap.state()==ServerController.State.ONLINE && now-lastTickRequest>=10000 && now>=userCommandUntil) {
            lastTickRequest=now; pollRepliesUntil=now+3000
            controller.command("tick query"); controller.command("list"); if(actualWorldSeed==null)controller.command("seed")
        }
    }
    private fun recordSample(snap:ServerController.Snapshot,info:ActivityManager.MemoryInfo,pm:PowerManager,stat:StatFs,thermal:Int,
                             batteryPercent:Int?,charging:Boolean?,batteryTemp:Double?,rss:Long?) {
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val online=snap.state()==ServerController.State.ONLINE
        val sample=JSONObject().put("schemaVersion",1).put("timestampMs",System.currentTimeMillis()).put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
            .put("deviceModel",Build.MODEL).put("androidVersion",Build.VERSION.RELEASE).put("androidApi",Build.VERSION.SDK_INT).put("abi",abi)
            .put("minecraftVersion",provider().minecraftVersion()).put("serverProvider",provider().id()).put("instrumentationAdapter",provider().instrumentationAdapter()).put("configuredWorldSeed",prefs.getString("world-seed",null) ?: JSONObject.NULL)
            .put("worldSeed",actualWorldSeed ?: prefs.getString("actual-world-seed",null) ?: JSONObject.NULL)
            .put("viewDistance",properties().getProperty("view-distance","10").trim().toIntOrNull() ?: JSONObject.NULL)
            .put("simulationDistance",properties().getProperty("simulation-distance","10").trim().toIntOrNull() ?: JSONObject.NULL).put("screenOn",pm.isInteractive).put("powerSaveMode",pm.isPowerSaveMode)
            .put("thermalStatus",thermal).put("deviceTotalMemoryBytes",info.totalMem).put("deviceAvailableMemoryBytes",info.availMem).put("deviceLowMemory",info.lowMemory)
            .put("storageFreeBytes",stat.availableBytes).put("storageTotalBytes",stat.totalBytes).put("batteryTemperatureC",batteryTemp ?: JSONObject.NULL)
            .put("batteryPercent",batteryPercent ?: JSONObject.NULL)
            .put("charging",charging ?: JSONObject.NULL)
            .put("networkConnected",cm.activeNetwork!=null).put("cpuOnline",runCatching { File("/sys/devices/system/cpu/online").readText().trim() }.getOrNull() ?: JSONObject.NULL)
            .put("jvm",if(host.isAlive) jvmMetrics else JSONObject.NULL).put("serverState",snap.state().name)
            .put("uptimeMs",snap.uptimeMillis()).put("startupTimeMs",snap.startupMillis() ?: JSONObject.NULL)
            .put("heapMaxMbSelected",prefs.getLong("heap-max-mb",0).takeIf { it>0 } ?: JSONObject.NULL)
            .put("playerCount",if(online) (listedPlayers+joinedAt.keys).distinct().size else JSONObject.NULL)
            .put("averageMspt",if(host.isAlive)mspt ?: JSONObject.NULL else JSONObject.NULL).put("tps",if(host.isAlive && profilerMetrics.has("tps"))profilerMetrics.getDouble("tps") else JSONObject.NULL)
            .put("tickRateMeasurement",if(host.isAlive)profilerMetrics else JSONObject.NULL)
            .put("tickQuery",if(host.isAlive)tickMetrics else JSONObject.NULL)
            .put("tpsUnavailableReason",if(host.isAlive && profilerMetrics.has("tps"))JSONObject.NULL else "Use Measure tick rate or debug start/stop; no inferred TPS")
            .put("serverPssBytes",JSONObject.NULL).put("serverPssUnavailableReason","Separate native child: in-app PSS access not yet validated; adb diagnostics provide physical evidence")
            .put("method","Android ActivityManager/PowerManager/BatteryManager, JVM MXBeans, server log, optional tick query")
        sample.put("serverRssBytes",rss ?: JSONObject.NULL)
        val cpuNow=if(host.isAlive && !jvmMetrics.isNull("jvmProcessCpuTimeNs"))jvmMetrics.optLong("jvmProcessCpuTimeNs") else null
        val sampleNow=System.nanoTime()
        val percent=cpuNow?.let { cpu -> previousJvmCpuTime?.let { previous -> previousJvmSampleNanos?.let { time -> (cpu-previous).coerceAtLeast(0).toDouble()/(sampleNow-time)*100 } } }
        sample.put("serverCpuPercentOneCoreScale",percent ?: JSONObject.NULL)
        previousJvmCpuTime=cpuNow; previousJvmSampleNanos=if(cpuNow!=null)sampleNow else null
        val frequencies=org.json.JSONArray()
        for(core in 0 until Runtime.getRuntime().availableProcessors()) {
            val frequency=runCatching { File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq").readText().trim().toLong() }.getOrNull()
            frequencies.put(JSONObject().put("logicalCpu",core).put("frequencyKhz",frequency ?: JSONObject.NULL))
        }
        sample.put("cpuFrequencies",frequencies)
        sample.put("cpuFrequencyMethod","Read-only sysfs; inaccessible entries are null; unavailable does not mean a core disappeared")
        synchronized(logLock) {
            // Keep the newest ~16 MB plus one older file instead of stopping at a cap.
            if(exportFile.length()>EXPORT_LIMIT) exportFile.renameTo(File(evidence,"samples.1.jsonl"))
            exportFile.appendText("$sample\n")
        }
    }
    @Volatile private var lanCache:Pair<Long,String?>?=null
    /** The phone's Wi-Fi/Ethernet IPv4. The active network is the NetBird VPN while online play is on, so skip VPNs. */
    @Suppress("DEPRECATION")
    private fun lanAddress():String? {
        val now=SystemClock.elapsedRealtime()
        lanCache?.let { (at,value) -> if(now-at<10_000) return value }
        val value=runCatching {
            val cm=app.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.asSequence().filter { n ->
                val caps=cm.getNetworkCapabilities(n) ?: return@filter false
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
            }.mapNotNull { n ->
                cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { !it.address.isLoopbackAddress && it.address is java.net.Inet4Address }?.address?.hostAddress
            }.firstOrNull()
        }.getOrNull()
        lanCache=now to value
        return value
    }

    fun dashboard():Dashboard {
        lastDashboardRead=SystemClock.elapsedRealtime()
        val s=controller.snapshot()
        val online=s.state()==ServerController.State.ONLINE
        val lan=lanAddress()
        val address=if(!online) null else lan
        val network=netBird.snapshot()
        val props=properties()
        val jvm=jvmMetrics.takeIf { host.isAlive }
        fun jvmBytes(key:String)=jvm?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key)
        val device=stats
        val now=SystemClock.elapsedRealtime()
        val ops=if(online) ops() else emptySet()
        val names=if(online) (listedPlayers+joinedAt.keys).distinct() else emptyList()
        val uuids=if(names.isNotEmpty()) playerUuids() else emptyMap()
        val regionalProxy=netBird.regionalProxy()
        val onUs=regionalRouteActive() && regionalProxy.ip!=null
        val voicePort=runCatching { voiceChatPort() }.getOrDefault(0)
        val hostname=prefs.getString("minecraft-hostname",null)
        return Dashboard(
            phase=s.state(), detail=error ?: progress ?: s.detail(), installed=File(server,"server.jar").isFile,
            busy=busy, uptimeMs=s.uptimeMillis()/60_000*60_000, processAlive=host.isAlive,
            consoleNote=if(reconnected) AdoptedProcess.INPUT_NOTE else null,
            errorMessage=error ?: s.detail().takeIf { s.state()==ServerController.State.ERROR },
            netBirdSignedIn=NetBirdOAuthSession.accountState(app).let { it==NetBirdOAuthSession.AccountState.VERIFIED || it==NetBirdOAuthSession.AccountState.SAVED },
            netBirdLinkUp=network.linkUp, netBirdAuthorizing=network.authorizing, netBirdLive=network.live, netBirdEnrolled=network.enrolled,
            runtimeResult=runtimeResult,
            localAddress=address?.let { "$it:${javaListenerPort()}" }, localBedrockAddress=lan?.let { "$it:${bedrockListenerPort()}" }, lanIp=lan, lanJavaAddress=lan?.let { "$it:${javaListenerPort()}" },
            serverNotResponding=online && readiness.server==Check.FAILED,
            playerCount=if(online) names.size else null,
            players=names.map { PlayerInfo(it,joinedAt[it]?.let { t -> (now-t)/60_000*60_000 },it.lowercase() in ops,uuids[it.lowercase()]) },
            mspt=if(online)mspt else null, progress=progress,
            publicAddress=if(onUs) "${regionalProxy.ip}:${javaListenerPort()}" else network.publicAddress,
            tps=if(online && profilerMetrics.has("tps"))profilerMetrics.getDouble("tps") else null,
            providerDisplayName=if(provider()==BaselineProvider.VANILLA)"Vanilla" else "Fabric 0.19.5",
            serverName=prefs.getString("server-name","Phoenix Server") ?: "Phoenix Server",
            networkState=network.detail, dnsState=dnsState, networkPhase=network.phase,
            networkBusy=network.busy, networkCanProvision=network.canProvision, networkCanReconnect=network.canReconnect,
            networkHasIdentity=network.hasIdentity, netBirdAccountDetail=autoNetworkSetupDetail.ifBlank { netBirdAccountDetail },
            netBirdPeerIp=network.peerIp,
            netBirdLink=network.link, netBirdAccount=network.account, netBirdServices=network.services,
            networkActivity=network.activity ?: if(autoNetworkSetupState=="PENDING" || autoNetworkSetupState=="RUNNING")
                (progress ?: "Checking your Minecraft.How hostname") else null,
            autoSetupState=autoNetworkSetupState,
            bedrockAddress=if(onUs) "${hostname ?: regionalProxy.ip}:${bedrockListenerPort()}"
                else network.bedrockAddress?.let { endpoint -> hostname?.let { "$it:${endpoint.substringAfterLast(':')}" } ?: endpoint },
            minecraftHostname=hostname,
            regionalProxyStage=regionalProxy.stage, regionalProxyDetail=regionalProxy.detail, regionalProxyIp=regionalProxy.ip,
            regionalProxyRegion=regionalProxy.region, regionalProxyShape=regionalProxy.shape, regionalPairingExpiresAt=regionalProxy.pairingExpiresAt,
            regionalRouteReport=regionalRouteReport, regionalPath=regionalPath, playersOnRegional=onUs, voiceChatPort=voicePort,
            euVoicePort=netBird.euVoicePort(), networkMode=networkMode(network.hasIdentity),
            regionalArea=prefs.getString(PREF_REGIONAL_AREA,null), regionalTrafficBytes=proxyTrafficThisMonth(),
            hostnameCheckName=hostnameCheckName, hostnameCheckDetail=hostnameCheckDetail,
            bedrockState=bedrockState,
            minecraftHowLinked=minecraftHowLinked, minecraftHowState=minecraftHowState, minecraftHowZones=minecraftHowZones,
            minecraftHowHasSession=minecraftHowHasSession, minecraftHowChecking=minecraftHowChecking,
            net=networkView(network,online),
            // Experimental: shown only after the owner turned it on here and confirmed its warning.
            regionalEnabled=prefs.getBoolean(PREF_REGIONAL_ENABLED,false) && prefs.getBoolean(PREF_EXPERIMENTAL_ACK,false),
            regionalWarningAcknowledged=prefs.getBoolean(PREF_EXPERIMENTAL_ACK,false),
            requestedRoute=PublicRoute.of(prefs.getString(PREF_REQUESTED_ROUTE,null)),
            playitClaimUrl=playit.claim().url, playitAccount=playit.agent.state.accountStatus,
            playitJavaNamesOpen=playit.customNamesListed() || frontDoorEndpoint()!=null, nameListing=nameListing(), spawnProtection=props.getProperty("spawn-protection","0").trim().toIntOrNull() ?: 0,
            regionCompression=props.getProperty("region-file-compression","deflate").trim(),
            playitProblems=playitProblems.values.toList(), routeError=routeError,
            nameRecords=endpointsFor(selectedRoute())?.let(::nameTarget)?.let { NameRecords(it.apex,it.srvHost,it.javaPort,it.bedrockPort) },
            heapUsedBytes=jvmBytes("jvmHeapUsedBytes")?.let { it/MB*MB }, heapMaxBytes=jvmBytes("jvmHeapMaxBytes"), serverRssBytes=device.rss.takeIf { host.isAlive }?.let { it/MB*MB },
            phoneMemAvailableBytes=device.memAvailable?.let { it/MB*MB }, phoneMemTotalBytes=device.memTotal,
            storageFreeBytes=device.storageFree?.let { it/(16*MB)*(16*MB) }, storageTotalBytes=device.storageTotal,
            batteryPercent=device.battery, charging=device.charging, batteryTempC=device.batteryTemp, thermalStatus=device.thermal,
            maxPlayers=props.getProperty("max-players","20").trim().toIntOrNull() ?: 20,
            gamemode=props.getProperty("gamemode","survival").trim(), difficulty=props.getProperty("difficulty","easy").trim(),
            pvp=props.getProperty("pvp","true").trim()!="false",
            viewDistance=props.getProperty("view-distance","10").trim().toIntOrNull() ?: 10,
            simulationDistance=props.getProperty("simulation-distance","10").trim().toIntOrNull() ?: 10,
            levelName=props.getProperty("level-name","world").trim().ifEmpty { "world" },
            worldSeed=actualWorldSeed ?: prefs.getString("actual-world-seed",null),
            pendingRestart=pendingRestart && host.isAlive,
            chunky=chunky, chunkyLoaded=host.isAlive && "chunky" in modIdsAtStart, memorySettingMb=prefs.getInt("server-memory-mb",0),
            spawnPrep=spawnPrep, spawnPrepEnabled=spawnPrepEnabled(), spawnPrepRadius=spawnPrepRadius(), spawnPrepLast=spawnPrepLast(),
            worldErrors=worldErrors, serverCrash=serverCrash, systemKillNotice=killNotice, fasterStarts=prefs.getBoolean(AotCache.PREF,AotCache.DEFAULT_ENABLED),
            phoeniumEngineAvailable=phoeniumEngineUsable(), phoeniumAcceleration=prefs.getBoolean(PhoeniumTuning.PREF_ACCELERATION,PhoeniumTuning.DEFAULT_ACCELERATION),
            phoeniumWorkers=phoeniumWorkers(), phoeniumMaxWorkers=PhoeniumTuning.maxWorkers(Runtime.getRuntime().availableProcessors()), thermalEaseOff=thermalEaseOff(), easedSimulation=easedFrom?.let { it-1 }, autoBackupsKeep=prefs.getInt(AutoBackups.PREF,AutoBackups.DEFAULT_KEEP),
        )
    }
    /** Every provider and the custom name in one view; Home and Settings both read only this. Local reads only. */
    private fun networkView(network:NetBirdNetworkManager.Snapshot,serverOnline:Boolean):NetworkView {
        val selected=selectedRoute()
        val switching=routeSwitch
        val providers=mapOf(
            PublicRoute.PLAYIT to playitState(selected,switching,serverOnline),
            PublicRoute.NETBIRD_EU to netBirdState(network,selected,switching,serverOnline),
            PublicRoute.NETBIRD_REGIONAL to regionalState(network,selected,switching),
            PublicRoute.LOCAL to ProviderState(PublicRoute.LOCAL,selected==PublicRoute.LOCAL,true,
                if(selected==PublicRoute.LOCAL) LinkHealth.CONNECTED else LinkHealth.OFF,"Local only"),
        )
        val endpoints=providers[selected]?.endpoints
        val hostname=prefs.getString("minecraft-hostname",null)
        val verifiedFor=prefs.getString(PREF_NAME_VERIFIED,null)
        val health=when {
            hostname==null -> NameHealth.NOT_SET_UP
            !customNameEnabled() -> NameHealth.OFF
            selected==null || !selected.isPublic -> NameHealth.OFF
            nameHealth!=null -> nameHealth!!
            endpoints!=null && verifiedFor==endpoints.fingerprint -> NameHealth.ACTIVE
            else -> NameHealth.PROPAGATING
        }
        val detail=when(health) {
            NameHealth.NOT_SET_UP -> if(minecraftHowLinked) "Pick a free name" else null
            NameHealth.OFF -> when {
                hostname!=null && !customNameEnabled() -> "Turned off; its records are kept"
                selected==null -> "Used once Playit.gg or NetBird is connected"
                else -> "Not used while public access is off"
            }
            NameHealth.ACTIVE -> nameDetail ?: "Points at ${selected?.title}"
            NameHealth.PROPAGATING -> nameDetail ?: "Not checked against ${selected?.title ?: "the route"} yet"
            else -> nameDetail
        }
        return NetworkView(selected,providers,
            CustomName(hostname!=null && customNameEnabled(),hostname,health,detail,verifiedFor,prefs.getBoolean(PREF_NAME_BEDROCK,false),
                recordsLive=endpoints!=null && recordsLiveFor==endpoints.fingerprint),
            readiness,switching,routeError,prefs.getBoolean(PREF_EXPERIMENTAL_ACK,false))
    }

    private fun playitState(selected:PublicRoute?,switching:RouteSwitch?,serverOnline:Boolean):ProviderState {
        val claim=playit.claim()
        val agent=playit.agent.state
        val endpoints=playitEndpoints()
        val isSelected=selected==PublicRoute.PLAYIT
        val working=switching?.to==PublicRoute.PLAYIT || playit.working!=null ||
            claim.phase==PlayitManager.ClaimPhase.WAITING_FOR_VISIT || claim.phase==PlayitManager.ClaimPhase.WAITING_FOR_APPROVAL
        fun state(health:LinkHealth,status:String,detail:String?=null)=ProviderState(PublicRoute.PLAYIT,isSelected,playit.hasKey(),health,status,detail,endpoints,working)
        val problems=playitProblems.values.joinToString(" ").ifBlank { null }
        return when {
            claim.phase==PlayitManager.ClaimPhase.WAITING_FOR_VISIT || claim.phase==PlayitManager.ClaimPhase.WAITING_FOR_APPROVAL ->
                state(LinkHealth.CONNECTING,"Waiting for approval",claim.detail)
            !playit.hasKey() && claim.phase!=PlayitManager.ClaimPhase.NONE -> state(LinkHealth.ERROR,"Not connected",claim.detail)
            !playit.hasKey() -> state(LinkHealth.NOT_SET_UP,"Not connected","Sign in with your free Playit account")
            playit.authFailed() -> state(LinkHealth.ERROR,"Authentication required","Playit no longer accepts this phone's sign-in. Connect again.")
            switching?.to==PublicRoute.PLAYIT -> state(LinkHealth.CONNECTING,switching.step)
            // Not "Account connected": Playit refused this agent until the owner frees a slot on playit.gg.
            playit.overAgentLimit() -> state(LinkHealth.ERROR,"Agent limit reached","Your Playit plan's agent limit is full. Delete an agent you " +
                "no longer use on playit.gg (keep the newest, this phone), then try again.")
            !isSelected -> state(LinkHealth.OFF,"Off","Account connected. Turn on to use Playit.")
            !playit.agent.installed -> state(LinkHealth.ERROR,"Agent missing","This build doesn't include the Playit agent.")
            agent.phase==PlayitAgent.Phase.RUNNING && endpoints?.java!=null -> state(LinkHealth.CONNECTED,"Connected",problems)
            agent.phase==PlayitAgent.Phase.RUNNING -> state(LinkHealth.ERROR,"Tunnel unavailable",playit.lastError ?: "Playit hasn't assigned the Java address.")
            agent.phase==PlayitAgent.Phase.STARTING -> state(LinkHealth.CONNECTING,"Connecting…",agent.detail)
            agent.phase==PlayitAgent.Phase.INVALID_KEY -> state(LinkHealth.ERROR,"Authentication required",agent.detail)
            agent.phase==PlayitAgent.Phase.OVER_LIMIT || agent.phase==PlayitAgent.Phase.ERROR || agent.phase==PlayitAgent.Phase.CRASHED ->
                state(LinkHealth.ERROR,"Agent disconnected",agent.detail)
            routeError!=null && serverOnline -> state(LinkHealth.ERROR,"Unable to reach Playit",routeError)
            serverOnline -> state(LinkHealth.CONNECTING,"Connecting…")
            else -> state(LinkHealth.OFF,"Idle","Ready to start the server")
        }
    }

    private fun netBirdState(network:NetBirdNetworkManager.Snapshot,selected:PublicRoute?,switching:RouteSwitch?,serverOnline:Boolean):ProviderState {
        val isSelected=selected==PublicRoute.NETBIRD_EU
        val endpoints=netBirdEndpoints()
        fun state(health:LinkHealth,status:String,detail:String?=null)=ProviderState(PublicRoute.NETBIRD_EU,isSelected,network.hasIdentity,health,status,detail,endpoints,
            switching?.to==PublicRoute.NETBIRD_EU || network.busy || network.authorizing)
        val stopped=!network.live && !network.linkUp && (network.link.startsWith("NetBird stopped") || network.link.startsWith("NetBird couldn't start"))
        return when {
            network.authorizing -> state(LinkHealth.CONNECTING,"Waiting for sign-in","Finish signing in to NetBird in your browser")
            switching?.to==PublicRoute.NETBIRD_EU -> state(LinkHealth.CONNECTING,switching.step)
            !network.hasIdentity -> if(network.busy) state(LinkHealth.CONNECTING,"Setting up…",network.activity) else state(LinkHealth.NOT_SET_UP,"Not connected")
            !isSelected -> state(LinkHealth.OFF,"Off","Ports kept. Turn on to use NetBird.")
            network.linkUp -> state(LinkHealth.CONNECTED,"Connected")
            network.busy || network.live -> state(LinkHealth.CONNECTING,"Connecting…",network.activity)
            stopped -> state(LinkHealth.ERROR,"Stopped unexpectedly",network.link)
            serverOnline -> state(LinkHealth.ERROR,"Not connected","Turn NetBird on so friends can join.")
            else -> state(LinkHealth.OFF,"Idle","Ready to start the server")
        }
    }

    private fun regionalState(network:NetBirdNetworkManager.Snapshot,selected:PublicRoute?,switching:RouteSwitch?):ProviderState {
        val proxy=netBird.regionalProxy()
        val isSelected=selected==PublicRoute.NETBIRD_REGIONAL
        fun state(health:LinkHealth,status:String,detail:String?=null)=ProviderState(PublicRoute.NETBIRD_REGIONAL,isSelected,proxy.ready,health,status,detail,
            regionalEndpoints(),switching?.to==PublicRoute.NETBIRD_REGIONAL)
        return when {
            switching?.to==PublicRoute.NETBIRD_REGIONAL -> state(LinkHealth.CONNECTING,switching.step)
            proxy.stage=="NOT_SET_UP" -> state(LinkHealth.NOT_SET_UP,"Not set up")
            proxy.stage=="PAIRING" -> state(LinkHealth.CONNECTING,"Waiting for your Oracle proxy")
            proxy.stage=="WAITING_FOR_PROXY" || proxy.stage=="PROXY_ONLINE" -> state(LinkHealth.CONNECTING,"Proxy starting",proxy.detail)
            proxy.stage=="PROXY_OFFLINE" -> state(LinkHealth.ERROR,"Proxy offline",proxy.detail)
            !isSelected -> state(LinkHealth.OFF,"Ready, not in use")
            network.linkUp -> state(LinkHealth.CONNECTED,"Connected")
            network.busy || network.live -> state(LinkHealth.CONNECTING,"Connecting…")
            else -> state(LinkHealth.OFF,"Ready","Connects when the server starts")
        }
    }

    /** Operator names from ops.json, re-read only when the file changes. */
    private fun ops():Set<String> {
        val file=File(server,"ops.json"); val stamp=if(file.isFile) file.lastModified()*31+file.length() else 0L
        opsCache?.takeIf { it.first==stamp }?.let { return it.second }
        val names=runCatching {
            val array=org.json.JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.lowercase()?.takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
        opsCache=stamp to names
        return names
    }
    /** Player UUIDs by lower-case name from usercache.json (Java and Floodgate players), re-read only when it changes. */
    private fun playerUuids():Map<String,String> {
        val file=File(server,"usercache.json"); val stamp=if(file.isFile) file.lastModified()*31+file.length() else 0L
        userCache?.takeIf { it.first==stamp }?.let { return it.second }
        val map=runCatching {
            val array=org.json.JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { it.optString("name").lowercase() to it.optString("uuid") } }
                .filter { (name,uuid) -> name.isNotBlank() && uuid.isNotBlank() }.toMap()
        }.getOrDefault(emptyMap())
        userCache=stamp to map
        return map
    }
    private fun trackPlayers(line:String) {
        val joined=line.endsWith(" joined the game"); val left=line.endsWith(" left the game")
        if(!joined && !left) return
        val text=Console.parse(line).takeIf { it.kind==Console.Kind.JOIN || it.kind==Console.Kind.LEAVE }?.text ?: return
        val name=text.substringBefore(' ').takeIf { it.isNotBlank() && !it.startsWith("<") && !it.startsWith("[") } ?: return
        if(joined) joinedAt[name]=SystemClock.elapsedRealtime()
        else { joinedAt.remove(name); listedPlayers=listedPlayers-name }
    }
    fun provisionNetBird()=launchAction {
        netBird.provision(javaListenerPort(),bedrockListenerPort())
        if(prefs.getString(PREF_NETWORK_MODE,null)==null) prefs.edit().putString(PREF_NETWORK_MODE,"easy").apply()
        // NetBird set up while it was requested (or while nothing is selected yet) becomes the route; otherwise it waits.
        val requested=PublicRoute.of(prefs.getString(PREF_REQUESTED_ROUTE,null))
        if(requested==PublicRoute.NETBIRD_EU || (requested==null && selectedRoute()==null)) selectRoute(PublicRoute.NETBIRD_EU)
        voiceSynced.set(false); syncEuVoice()
    }
    /** Turning NetBird on: switch now when its ports exist, otherwise remember the request until sign-in finishes. */
    fun requestNetBird():Boolean {
        if(netBirdEndpoints()!=null) { selectRoute(PublicRoute.NETBIRD_EU); return true }
        prefs.edit().putString(PREF_REQUESTED_ROUTE,PublicRoute.NETBIRD_EU.key).apply()
        return false
    }
    fun retryTunnelSetup()=launchAction {
        check(host.isAlive) { "Start Minecraft before reconnecting NetBird" }
        connectSavedRelay()
    }


    // ------------------------------------------------------------ Public routes: exactly one at a time

    private val playit=PlayitManager(app) { append(it) }.also { it.onLinked={ onPlayitLinked() } }
    /** Route changes, provider setup and the after-start reachability checks, one at a time, off the server's own worker. */
    private val routeExecutor=Executors.newSingleThreadExecutor { Thread(it,"phoenix-route").apply { isDaemon=true } }
    private val routeTasks=java.util.concurrent.atomic.AtomicInteger()
    /** Counts queued and running route work, so the hosting service keeps Phoenix from being frozen meanwhile. */
    private val routeWork=java.util.concurrent.Executor { task ->
        routeTasks.incrementAndGet()
        try { routeExecutor.execute { try { task.run() } finally { routeTasks.decrementAndGet() } } }
        catch(e:Exception) { routeTasks.decrementAndGet(); throw e }
    }
    /**
     * Network setup is in progress: a Playit approval is open, or a route change or check is running. Android freezes
     * background apps, which would stop the approval poll while the owner is in the browser, so the hosting service
     * stays in the foreground while this is true.
     */
    fun networkSetupActive():Boolean {
        val claim=playit.claim().phase
        return claim==PlayitManager.ClaimPhase.WAITING_FOR_VISIT || claim==PlayitManager.ClaimPhase.WAITING_FOR_APPROVAL ||
            routeTasks.get()>0 || routeSwitch!=null
    }
    private fun networkSetupLine():String? = routeSwitch?.step ?: when(playit.claim().phase) {
        PlayitManager.ClaimPhase.WAITING_FOR_VISIT, PlayitManager.ClaimPhase.WAITING_FOR_APPROVAL -> "Waiting for your Playit approval in the browser"
        else -> null
    }
    @Volatile private var routeSwitch:RouteSwitch?=null
    @Volatile private var routeError:String?=null
    @Volatile private var playitProblems:Map<PlayitRole,String> = emptyMap()
    @Volatile private var readiness=Readiness()
    @Volatile private var startPressedAt=0L
    @Volatile private var nameHealth:NameHealth?=null
    @Volatile private var nameDetail:String?=null
    /** RouteEndpoints.fingerprint Minecraft.How's own name servers were last seen publishing correct records for. */
    @Volatile private var recordsLiveFor:String?=null
    /** When the check after the network's cached "no record" expires is due (elapsed realtime), so it is scheduled once. */
    @Volatile private var cacheCheckAt=0L
    /** Serializes every Minecraft.How record write, whoever starts it. */
    private val dnsLock=Any()

    /** The route friends use. Null on a fresh install until the owner connects one (Playit is recommended). */
    fun selectedRoute():PublicRoute?=PublicRoute.of(prefs.getString(PREF_ROUTE,null))
    private fun serverPorts()=ServerPorts(javaListenerPort(),bedrockListenerPort(),runCatching { voiceChatPort() }.getOrDefault(0))
    /** NetBird's saved EU ports, from the cached store view (the dashboard asks every second; nothing is decrypted per call). */
    private fun netBirdEndpoints():RouteEndpoints? = runCatching {
        val saved=netBird.publicPorts() ?: return@runCatching null
        val cluster=saved.cluster!!
        val eu=netBird.euVoicePort()
        RouteEndpoints(Endpoint(cluster,saved.tcp),Endpoint(cluster,saved.udp),if(eu>0) Endpoint(cluster,eu) else null)
    }.getOrNull()
    private fun regionalEndpoints():RouteEndpoints? {
        val state=netBird.regionalProxy().takeIf { it.ready } ?: return null
        val ip=state.ip ?: return null
        val ports=runCatching { serverPorts() }.getOrNull() ?: return null
        return RouteEndpoints(Endpoint(ip,ports.java),Endpoint(ip,ports.bedrock),if(ports.voice>0) Endpoint(ip,ports.voice) else null)
    }
    private fun endpointsFor(route:PublicRoute?):RouteEndpoints? = when(route) {
        PublicRoute.PLAYIT -> playitEndpoints()
        PublicRoute.NETBIRD_EU -> netBirdEndpoints()
        PublicRoute.NETBIRD_REGIONAL -> regionalEndpoints()
        else -> null
    }

    private val playitDriver=object:RouteDriver {
        override fun prepare(ports:ServerPorts,progress:(String)->Unit):RouteEndpoints {
            if(!playit.hasKey()) throw RouteException("Connect your Playit account first.")
            if(!playit.agent.installed) throw RouteException("This build doesn't include the Playit agent.")
            if(playit.authFailed()) throw RouteException("Playit no longer accepts this phone's agent (it was deleted or revoked on playit.gg). Tap Connect Playit again.")
            // The agent connects first: Playit only creates tunnels for an agent it has seen online with its real
            // version (before that it answers AgentVersionTooOld), as Playit's own clients do.
            progress("Starting the Playit agent")
            playit.startAgent()
            awaitAgentRunning(progress)
            val (endpoints,problems)=playit.ensureTunnels(ports,progress)
            playitProblems=problems
            // The agent picks new tunnels up from Playit within a few seconds.
            progress("Loading the tunnels into the agent")
            val loaded=SystemClock.elapsedRealtime()+30_000
            while(playit.agent.state.tunnels.none { it.destination.endsWith(":${ports.java}") } && SystemClock.elapsedRealtime()<loaded) Thread.sleep(500)
            return endpoints
        }
        override fun start(ports:ServerPorts) { playit.startAgent() }
        override fun stop() { playit.stopAgent() }
        override fun running()=playit.agent.running
    }
    /**
     * Waits for the agent to run. Over Playit's agent limit (old agents from earlier installs or tests count too), it keeps
     * waiting up to [OVER_LIMIT_WAIT_MS] while the owner deletes an unused agent on playit.gg, retrying every minute, and
     * goes on by itself once a slot is free. Deleting this phone's own agent instead ends the wait with a clear message.
     */
    private fun awaitAgentRunning(progress:(String)->Unit={}) {
        var deadline=SystemClock.elapsedRealtime()+45_000
        var overLimitSince=0L
        var lastRetry=0L
        while(playit.agent.state.phase!=PlayitAgent.Phase.RUNNING) {
            val state=playit.agent.state
            val now=SystemClock.elapsedRealtime()
            when(state.phase) {
                PlayitAgent.Phase.INVALID_KEY -> throw RouteException(if(overLimitSince>0)
                    "That removed this phone's own agent, so Playit no longer accepts it. Tap Connect Playit again; the new agent fits now."
                    else state.detail ?: "Playit no longer accepts this phone's sign-in. Connect Playit again.")
                PlayitAgent.Phase.CRASHED -> throw RouteException(state.detail ?: "The Playit agent couldn't connect.")
                PlayitAgent.Phase.OVER_LIMIT -> {
                    if(overLimitSince==0L) {
                        overLimitSince=now; lastRetry=now; deadline=now+OVER_LIMIT_WAIT_MS
                        append("Playit: this account already has as many agents as its plan allows. Delete one you no longer use on playit.gg " +
                            "(Agents); keep the newest, which is this phone. Phoenix continues by itself.")
                    }
                    progress("Over Playit's agent limit: delete an unused agent on playit.gg (keep the newest, this phone). Waiting…")
                    // Turning Playit off meanwhile (it clears the request) ends the wait.
                    if(prefs.getString(PREF_REQUESTED_ROUTE,null)!=PublicRoute.PLAYIT.key) throw RouteException("Stopped waiting for a free Playit agent slot.")
                    if(now-lastRetry>60_000) { lastRetry=now; playit.agent.restart() }
                }
                else -> Unit
            }
            if(now>deadline) throw RouteException(if(overLimitSince>0)
                "Your Playit account is still over its agent limit. Delete an agent you no longer use on playit.gg, then turn Playit on again."
                else "The Playit agent didn't connect within 45 seconds. Check the internet connection and try again.")
            Thread.sleep(500)
        }
    }
    private val netBirdDriver=object:RouteDriver {
        override fun prepare(ports:ServerPorts,progress:(String)->Unit):RouteEndpoints {
            val endpoints=netBirdEndpoints() ?: throw RouteException("Set up NetBird first: sign in and create its public ports.")
            progress("Connecting NetBird")
            val snapshot=try { netBird.reconnect(ports.java,ports.bedrock) } catch(e:Exception) { throw RouteException(e.message ?: "NetBird couldn't reconnect.") }
            if(!snapshot.live && !snapshot.linkUp && snapshot.detail.contains("permission",ignoreCase=true)) throw RouteException(snapshot.detail)
            val deadline=SystemClock.elapsedRealtime()+45_000
            while(!netBird.snapshot().linkUp) {
                if(SystemClock.elapsedRealtime()>deadline) throw RouteException("NetBird didn't connect within 45 seconds. Open the NetBird card and connect it, then try again.")
                Thread.sleep(500)
            }
            return endpoints
        }
        override fun start(ports:ServerPorts) { runCatching { netBird.reconnect(ports.java,ports.bedrock) }.onFailure { append("NetBird reconnect: ${it.message}") } }
        override fun stop() { NetBirdTransport(app).stop() }
        override fun running()=netBird.snapshot().live
    }
    private val regionalDriver=object:RouteDriver {
        override fun prepare(ports:ServerPorts,progress:(String)->Unit):RouteEndpoints {
            netBirdDriver.prepare(ports,progress)
            progress("Checking your regional proxy")
            checkRegionalProxyAction()
            val state=netBird.regionalProxy()
            if(!state.ready) throw RouteException("The regional proxy is not ready yet: ${state.detail}")
            if(controller.snapshot().state()==ServerController.State.ONLINE && regionalRouteReport?.startsWith("Java answers")!=true)
                throw RouteException("The regional proxy did not pass the route test; players were not moved.")
            return regionalEndpoints() ?: throw RouteException("The regional proxy has no public address yet.")
        }
        override fun start(ports:ServerPorts)=netBirdDriver.start(ports)
        override fun stop()=netBirdDriver.stop()
        override fun running()=netBirdDriver.running()
    }

    private val routes=RouteSwitcher(mapOf(PublicRoute.PLAYIT to playitDriver,PublicRoute.NETBIRD_EU to netBirdDriver,
        PublicRoute.NETBIRD_REGIONAL to regionalDriver),object:RouteHooks {
        override fun selected()=selectedRoute()
        override fun saveSelected(route:PublicRoute)=prefs.edit().putString(PREF_ROUTE,route.key)
            .putString(PREF_PUBLIC_ROUTE,if(route==PublicRoute.NETBIRD_REGIONAL) "us" else "eu").commit()
        override fun verify(route:PublicRoute,endpoints:RouteEndpoints):String? {
            if(controller.snapshot().state()!=ServerController.State.ONLINE) return null
            return if(probeJava(endpoints,attempts=4)) null
                else "Friends couldn't reach the server through ${route.title}: its public Java address didn't answer. Your previous route was kept."
        }
        override fun committed(previous:PublicRoute?,route:PublicRoute,endpoints:RouteEndpoints?) {
            routeError=null
            readiness=Readiness(server=if(host.isAlive) Check.OK else Check.UNKNOWN)
            if(route==PublicRoute.PLAYIT && !host.isAlive) playit.stopAgent() // It starts with the server.
            // The front door closes but stays chosen: it only counts while Playit is the route, and back on Playit the name
            // keeps leading to it. Forgetting it here sent Java's SRV to Playit and back on every off-and-on, so the name read
            // "Waiting for DNS" for ten minutes each time while caches caught up (2026-10-12).
            if(route!=PublicRoute.PLAYIT) { playitProblems=emptyMap(); stopFrontDoor() }
            if(applyVoiceHost() && host.isAlive) pendingRestart=true
            append(when(route) {
                PublicRoute.LOCAL -> "Public access is off. Friends on the same Wi-Fi can still join."
                else -> "Friends now connect through ${route.title}" + if(previous!=null && previous.isPublic) ". Players on the old address need the new one or must reconnect." else "."
            })
            // Back on Playit, the name's Java SRV keeps leading to the front door it already used (checked again at start).
            val named=if(route==PublicRoute.PLAYIT) playitEndpoints() ?: endpoints else endpoints
            runCatching { pointNameAtRoute(route,named) }.onFailure { nameHealth=NameHealth.ERROR; nameDetail=it.message }
            if(host.isAlive && route.isPublic) checkPublicReachability()
        }
        override fun progress(change:RouteSwitch?) { routeSwitch=change }
    })

    /**
     * Makes [route] the one public route. A route that still needs an account (Playit claim, NetBird sign-in) is
     * remembered as requested and switched to as soon as its setup finishes; the current route keeps working meanwhile.
     */
    fun selectRoute(route:PublicRoute) {
        routeError=null
        prefs.edit().putString(PREF_REQUESTED_ROUTE,route.key).apply()
        routeWork.execute {
            val ports=runCatching { serverPorts() }.getOrElse { routeError="Install the server first."; return@execute }
            when(val result=routes.switchTo(route,ports)) {
                is RouteSwitcher.Result.Switched -> prefs.edit().remove(PREF_REQUESTED_ROUTE).apply()
                is RouteSwitcher.Result.Kept -> { routeError=result.reason; prefs.edit().remove(PREF_REQUESTED_ROUTE).apply(); append(result.reason) }
                RouteSwitcher.Result.Busy -> routeError="Another change is still running. Try again when it finishes."
            }
        }
    }
    /** Turns public access off: the selected route's agent or VPN stops; accounts, tunnels and names are kept. */
    fun turnOffPublicAccess()=selectRoute(PublicRoute.LOCAL)

    /**
     * Starts Playit's browser approval and returns the page to open. With [andUse] the route switches to Playit once it
     * is approved (the owner already confirmed that); without it only the account is linked.
     */
    fun connectPlayit(andUse:Boolean):String? {
        if(andUse) prefs.edit().putString(PREF_REQUESTED_ROUTE,PublicRoute.PLAYIT.key).apply()
        if(playit.hasKey() && !playit.authFailed()) { if(andUse) selectRoute(PublicRoute.PLAYIT); return null }
        return runCatching { playit.beginClaim() }.getOrElse { routeError="Couldn't start the Playit sign-in: ${it.message}"; null }
    }
    fun cancelPlayitSignIn() { playit.cancelClaim(); if(prefs.getString(PREF_REQUESTED_ROUTE,null)==PublicRoute.PLAYIT.key) prefs.edit().remove(PREF_REQUESTED_ROUTE).apply() }
    /** Forgets Playit on this phone. Refused while Playit is the route players use. */
    fun forgetPlayit()=routeWork.execute {
        if(selectedRoute()==PublicRoute.PLAYIT) { routeError="Turn Playit off or switch to another route before disconnecting the account."; return@execute }
        playit.forget(); playitProblems=emptyMap()
        append("Playit disconnected from this phone. To remove the agent from your account, delete it on playit.gg.")
    }
    private fun onPlayitLinked() {
        if(prefs.getString(PREF_REQUESTED_ROUTE,null)==PublicRoute.PLAYIT.key) selectRoute(PublicRoute.PLAYIT)
    }

    /** Starts the selected route's data plane with the server; never signs in, creates tunnels or touches other routes. */
    private fun startSelectedRoute() {
        val ports=serverPorts()
        when(selectedRoute()) {
            PublicRoute.PLAYIT -> { readiness=readiness.copy(relay=Check.PENDING); playitDriver.start(ports); openFrontDoor(ports) }
            PublicRoute.NETBIRD_EU, PublicRoute.NETBIRD_REGIONAL -> { readiness=readiness.copy(relay=Check.PENDING); netBirdDriver.start(ports) }
            else -> readiness=readiness.copy(relay=Check.NOT_APPLICABLE,java=Check.NOT_APPLICABLE,bedrock=Check.NOT_APPLICABLE)
        }
    }

    /** One status ping through the public Java address, as a client joining sends it. */
    private fun probeJava(endpoints:RouteEndpoints,attempts:Int=3,handshakeHost:String?=null,at:Endpoint?=null):Boolean {
        val target=at ?: endpoints.javaTarget ?: endpoints.java?.let { Endpoint(it.host,25565) } ?: return false
        repeat(attempts) { attempt ->
            if(runCatching { MinecraftStatusProbe.query(target.host,target.port ?: 25565,handshakeHost ?: target.host,4000) }.isSuccess) return true
            if(attempt<attempts-1) Thread.sleep(3000)
        }
        return false
    }
    private fun probeBedrock(endpoints:RouteEndpoints):Boolean? {
        val bedrock=endpoints.bedrock?.takeIf { it.port!=null } ?: return null
        return (1..3).any { runCatching { org.phoenix.core.network.BedrockStatusProbe.query(bedrock.host,bedrock.port!!,2500) }.isSuccess }
    }

    /**
     * After the server is online: confirms through the public addresses that friends can actually reach it, separately
     * for Java and Bedrock, and records how long that took from Start. Playit tunnels whose local port changed (for
     * example a new Geyser port) are updated first. Runs off the startup path and never blocks hosting.
     */
    private fun checkPublicReachability() = routeWork.execute {
        val route=selectedRoute()?.takeIf { it.isPublic } ?: return@execute
        if(controller.snapshot().state()!=ServerController.State.ONLINE) return@execute
        val ports=runCatching { serverPorts() }.getOrNull() ?: return@execute
        var endpoints=endpointsFor(route)
        if(route==PublicRoute.PLAYIT) {
            if(!playit.hasKey()) { readiness=readiness.copy(relay=Check.FAILED); return@execute }
            val deadline=SystemClock.elapsedRealtime()+45_000
            while(playit.agent.state.phase!=PlayitAgent.Phase.RUNNING && SystemClock.elapsedRealtime()<deadline && playit.agent.running) Thread.sleep(500)
            // Tunnels made for other ports (a changed Java, Geyser or voice port) are updated in place; otherwise no API call.
            // The name's A record pins Playit's Bedrock IPv4; if Playit's own name no longer resolves to it, read the tunnels again.
            val saved=endpoints
            val ipMoved=saved?.bedrockIp!=null && saved.bedrock!=null && runCatching {
                java.net.InetAddress.getAllByName(saved.bedrock.host).filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }
            }.getOrNull()?.let { it.isNotEmpty() && saved.bedrockIp !in it }==true
            if(ipMoved) append("Playit's Bedrock IP changed; updating the addresses and the name")
            if(endpoints==null || playit.endpointsStale() || needsTunnelUpdate(ports) || ipMoved) runCatching { playit.ensureTunnels(ports) { } }.onSuccess { endpoints=playitEndpoints(); playitProblems=it.second }
                .onFailure { routeError=it.message }
            readiness=readiness.copy(relay=if(playit.agent.state.phase==PlayitAgent.Phase.RUNNING) Check.OK else Check.FAILED)
            if(applyVoiceHost() && host.isAlive) pendingRestart=true
        } else {
            val deadline=SystemClock.elapsedRealtime()+45_000
            while(!netBird.snapshot().linkUp && SystemClock.elapsedRealtime()<deadline) Thread.sleep(500)
            readiness=readiness.copy(relay=if(netBird.snapshot().linkUp) Check.OK else Check.FAILED)
        }
        val current=endpoints ?: return@execute
        readiness=readiness.copy(java=Check.PENDING,bedrock=if(current.bedrock!=null) Check.PENDING else Check.FAILED)
        val java=probeJava(current)
        val bedrock=probeBedrock(current)
        val voice=when { ports.voice==0 -> Check.NOT_APPLICABLE; current.voice!=null -> Check.OK; else -> Check.FAILED }
        val ready=if(java && startPressedAt>0) SystemClock.elapsedRealtime()-startPressedAt else null
        readiness=readiness.copy(java=if(java) Check.OK else Check.FAILED,
            bedrock=when(bedrock) { true -> Check.OK; false -> Check.FAILED; null -> Check.FAILED },
            voice=voice,publicReadyMs=ready ?: readiness.publicReadyMs)
        append("Public check through ${route.title}: Java ${if(java) "answers" else "did not answer"} · Bedrock ${when(bedrock) { true -> "answers"; false -> "did not answer"; null -> "not forwarded" }}" +
            (if(ports.voice>0) " · voice ${if(current.voice!=null) "forwarded (players confirm it in game)" else "not forwarded"}" else "") +
            (ready?.let { " · friends could join ${it/1000} s after Start" } ?: ""))
        if(route==PublicRoute.PLAYIT) runCatching { setUpFrontDoor(ports,current) }.onFailure { frontDoorDetail=it.message }
        val named=endpointsFor(route) ?: current
        if(prefs.getString("minecraft-hostname",null)!=null && customNameEnabled()) {
            val result=runCatching { validateCustomName(named) }.getOrNull()
            // Records made for older addresses (a new Playit address, Bedrock's IPv4, a moved front door) are rewritten in
            // place; Minecraft.How is only written when the records really differ.
            // Not when Minecraft.How already publishes the right records and only a DNS cache lags: rewriting changes nothing.
            if(result!=null && result.state!=MinecraftHowDns.State.VERIFIED && recordsLiveFor!=named.fingerprint &&
                prefs.getString(PREF_NAME_VERIFIED,null)!=named.fingerprint)
                runCatching { pointNameAtRoute(route,named) }.onFailure { nameHealth=NameHealth.ERROR; nameDetail=it.message }
        }
    }
    /** Playit tunnels still point at the ports they were made for; true when Java, Bedrock or voice moved. */
    private fun needsTunnelUpdate(ports:ServerPorts):Boolean {
        val tunnels=playit.agent.state.tunnels.takeIf { it.isNotEmpty() } ?: return false
        val destinations=tunnels.map { it.destination.substringAfterLast(':') }.toSet()
        return PlayitTunnels.wanted(ports).values.any { "$it" !in destinations }
    }

    // ---- Playit + NetBird + custom name: the name in Java's server list on Playit Free

    private val frontDoor=NameFrontDoor { append(it) }
    @Volatile private var frontDoorDetail:String?=null
    /** The running server was started with accepts-transfers=true. */
    @Volatile private var transfersLoaded=false
    /** Phoenix started NetBird only for the front door (Playit is the route), so it also stops it. */
    @Volatile private var frontDoorLink=false
    /** Creating the front door's NetBird service needs a NetBird sign-in the owner must renew; setup resumes after it. */
    @Volatile private var frontDoorNeedsSignIn=false
    @Volatile private var signInWatch:java.util.concurrent.ScheduledFuture<*>?=null
    private fun frontDoorEnabled()=prefs.getBoolean(PREF_FRONT_DOOR,true)
    /** Everything the front door needs: Playit as the route, a custom name in use, and NetBird's free ports. */
    private fun frontDoorWanted()=frontDoorEnabled() && selectedRoute()==PublicRoute.PLAYIT && customNameEnabled() &&
        prefs.getString("minecraft-hostname",null)!=null && netBird.publicPorts()!=null &&
        NetworkIntent.netBirdMayAutoStart(NetBirdTransport.ownerTurnedOff(app))
    /** NetBird's public address of the front door, once it passed its check; the name's Java SRV leads here. */
    private fun frontDoorEndpoint():Endpoint? {
        if(!prefs.getBoolean(PREF_FRONT_DOOR_ACTIVE,false) || selectedRoute()!=PublicRoute.PLAYIT) return null
        val cluster=netBird.publicPorts()?.cluster ?: return null
        return netBird.euNamePort().takeIf { it>0 }?.let { Endpoint(cluster,it) }
    }
    /** Playit's addresses, with the name's Java SRV leading to the front door while it is in use. */
    private fun playitEndpoints():RouteEndpoints?=playit.savedEndpoints()?.let { saved -> frontDoorEndpoint()?.let { saved.copy(nameJava=it) } ?: saved }

    private fun nameListing():NameListing? {
        if(selectedRoute()!=PublicRoute.PLAYIT || prefs.getString("minecraft-hostname",null)==null || !customNameEnabled()) return null
        val ready=netBird.publicPorts()!=null
        val active=frontDoorEndpoint()!=null
        val detail=when {
            !frontDoorEnabled() -> "Off. Java's server list shows the name as \"Can't connect\"; joining still works."
            !ready -> frontDoorDetail ?: "Needs NetBird's free ports (Playit stays your route). Set up NetBird once; Phoenix does the rest."
            active && frontDoor.listening -> frontDoorDetail ?: "On: the list shows your server's name, note and players. Games run through Playit."
            active -> "On. Starts with the server."
            else -> frontDoorDetail ?: "Turns on at the next server start."
        }
        return NameListing(frontDoorEnabled(),active,ready,
            if(frontDoorNeedsSignIn) "Sign in to NetBird once so Phoenix can create the front door's free port. Playit stays your route." else detail,
            frontDoorNeedsSignIn)
    }

    /**
     * "Set up NetBird (free)" in the add-on. Before, it only connected NetBird: nothing made the free ports while Playit is
     * the route, so the add-on stayed on "Needs NetBird's free ports" however often it was tapped (2026-10-12). Now the
     * ports are made as soon as NetBird is connected and signed in: right away when it already is (returns true), or
     * after its connect and sign-in. Playit stays the route; the front door then turns on with the running server.
     */
    fun requestFrontDoorSetup():Boolean {
        prefs.edit().putBoolean(PREF_FRONT_DOOR_SETUP,true).apply()
        frontDoorNeedsSignIn=false
        val ready=netBird.snapshot().linkUp && !needsNetBirdSignIn()
        frontDoorDetail=if(ready) "Creating NetBird's free ports…" else "Connecting NetBird…"
        watchFrontDoorSetup()
        return ready
    }

    @Volatile private var frontDoorSetupWatch:java.util.concurrent.ScheduledFuture<*>?=null
    private val frontDoorCreating=AtomicBoolean(false)
    /**
     * Follows "Set up NetBird (free)" to the end: every 3 s it reads NetBird's local state, says which step is open (VPN,
     * browser sign-in, connecting) and makes the ports once NetBird is connected and signed in. A refusal right after the
     * connect (NetBird Cloud not seeing the new peer yet, a rate limit) is retried. It gives up, and the button works again,
     * after 15 minutes or when NetBird isn't running at all. Before, setup waited for a single connect or sign-in event from
     * the VPN service; when that event was missed, only turning things off and on again moved it on (2026-10-12).
     */
    private fun watchFrontDoorSetup() {
        frontDoorSetupWatch?.cancel(false)
        val started=SystemClock.elapsedRealtime()
        var attempts=0
        var nextTry=0L
        var idleSince=0L
        frontDoorSetupWatch=sampler.scheduleWithFixedDelay(watch@{
            fun finish(detail:String) { prefs.edit().remove(PREF_FRONT_DOOR_SETUP).apply(); frontDoorDetail=detail; frontDoorSetupWatch?.cancel(false) }
            if(!prefs.getBoolean(PREF_FRONT_DOOR_SETUP,false)) { frontDoorSetupWatch?.cancel(false); return@watch }
            if(frontDoorCreating.get()) return@watch
            val now=SystemClock.elapsedRealtime()
            val link=netBird.snapshot()
            idleSince=if(link.live || link.authorizing) 0L else if(idleSince==0L) now else idleSince
            when {
                attempts>=FRONT_DOOR_SETUP_TRIES -> frontDoorSetupWatch?.cancel(false)
                now-started>FRONT_DOOR_SETUP_MS -> finish("NetBird didn't finish connecting within 15 minutes. Tap Set up NetBird to try again.")
                idleSince>0 && now-idleSince>90_000 -> finish("NetBird isn't running, so nothing was made. Tap Set up NetBird and allow the VPN request.")
                link.authorizing -> { frontDoorNeedsSignIn=false; frontDoorDetail="Finish the NetBird sign-in in your browser…" }
                !link.linkUp -> frontDoorDetail="Connecting NetBird…"
                needsNetBirdSignIn() -> { frontDoorNeedsSignIn=true; frontDoorDetail=null }
                now>=nextTry -> {
                    nextTry=now+20_000; attempts++
                    val last=attempts>=FRONT_DOOR_SETUP_TRIES
                    if(frontDoorCreating.compareAndSet(false,true)) routeWork.execute {
                        try { createFrontDoorPorts(lastTry=last) } finally { frontDoorCreating.set(false) }
                    }
                }
            }
        },3,3,TimeUnit.SECONDS)
    }

    /** Makes NetBird's free ports for the add-on; with [lastTry] false, a refusal is retried by [watchFrontDoorSetup]. */
    private fun createFrontDoorPorts(lastTry:Boolean=true) {
        if(!prefs.getBoolean(PREF_FRONT_DOOR_SETUP,false)) return
        try {
            if(netBird.publicPorts()==null) {
                frontDoorDetail="Creating NetBird's free ports…"
                netBird.provision(javaListenerPort(),bedrockListenerPort())
                append("NetBird's free ports are ready for the server-list add-on. Playit stays the route friends use.")
            }
            prefs.edit().remove(PREF_FRONT_DOOR_SETUP).apply()
            frontDoorSetupWatch?.cancel(false)
            frontDoorDetail=null; frontDoorNeedsSignIn=false
            val ports=serverPorts()
            if(controller.snapshot().state()==ServerController.State.ONLINE) playitEndpoints()?.let { setUpFrontDoor(ports,it) }
        } catch(e:Exception) {
            val why=e.message ?: e.javaClass.simpleName
            // An expired NetBird sign-in: the add-on shows "Sign in to NetBird", and the request resumes after it.
            if(needsNetBirdSignIn()) { frontDoorNeedsSignIn=true; frontDoorDetail=null; return }
            if(!lastTry) { frontDoorDetail="NetBird isn't ready yet ($why). Trying again…"; return }
            prefs.edit().remove(PREF_FRONT_DOOR_SETUP).apply()
            frontDoorSetupWatch?.cancel(false)
            frontDoorDetail="NetBird's free ports couldn't be made: $why"
            append("Server-list add-on: $frontDoorDetail")
        }
    }

    fun setNameListing(enabled:Boolean) {
        prefs.edit().putBoolean(PREF_FRONT_DOOR,enabled).apply()
        frontDoorDetail=null
        routeWork.execute {
            if(!enabled) { leaveFrontDoor(repoint=true); return@execute }
            if(controller.snapshot().state()!=ServerController.State.ONLINE) return@execute
            val ports=runCatching { serverPorts() }.getOrNull() ?: return@execute
            playitEndpoints()?.let { runCatching { setUpFrontDoor(ports,it) }.onFailure { e -> frontDoorDetail=e.message } }
        }
    }

    /** At server start: opens the front door and NetBird's link right away when the name already leads there. */
    private fun openFrontDoor(ports:ServerPorts) {
        if(!prefs.getBoolean(PREF_FRONT_DOOR_ACTIVE,false) || !frontDoorWanted()) return
        frontDoor.transferTo=playit.savedEndpoints()?.javaTarget ?: return
        frontDoor.serverPort=ports.java
        runCatching { frontDoor.start(FRONT_DOOR_PORT) }.onFailure { append("Front door couldn't open port $FRONT_DOOR_PORT: ${it.message}") }
        if(!netBird.snapshot().live) { frontDoorLink=true; runCatching { netBird.reconnect(ports.java,ports.bedrock) }.onFailure { append("NetBird for the front door: ${it.message}") } }
    }

    /**
     * Playit + NetBird + custom name. Playit Free answers Java only under its own name and Java's server list pings with
     * the name the player typed, so the name's Java SRV leads to Phoenix's front door through a NetBird TCP service
     * (NetBird doesn't check names). Server-list pings are passed to the server, so its name, note and players show; a
     * join is handed to Playit with Minecraft's own Transfer, so the game runs through Playit. The name is pointed there
     * only after a full check from outside through NetBird; a failed check points it back at Playit, where joins work.
     */
    private fun setUpFrontDoor(ports:ServerPorts,endpoints:RouteEndpoints,quiet:Boolean=false) {
        val active=prefs.getBoolean(PREF_FRONT_DOOR_ACTIVE,false)
        if(!frontDoorWanted()) { leaveFrontDoor(repoint=active); return }
        val target=playit.savedEndpoints()?.javaTarget ?: endpoints.javaTarget ?: return
        val hostname=prefs.getString("minecraft-hostname",null) ?: return
        if(!transfersLoaded) {
            allowTransfers()
            if(host.isAlive) pendingRestart=true
            frontDoorDetail="Restart the server once to finish: Minecraft must accept the hand-over to Playit."
            return
        }
        try {
            frontDoorDetail="Checking the front door through NetBird"
            frontDoor.transferTo=target; frontDoor.serverPort=ports.java
            frontDoor.start(FRONT_DOOR_PORT)
            if(!netBird.snapshot().linkUp) {
                if(!netBird.snapshot().live) frontDoorLink=true
                netBird.reconnect(ports.java,ports.bedrock)
                val deadline=SystemClock.elapsedRealtime()+45_000
                while(!netBird.snapshot().linkUp) {
                    check(SystemClock.elapsedRealtime()<deadline) { "NetBird didn't connect within 45 seconds" }
                    Thread.sleep(500)
                }
            }
            val cluster=netBird.publicPorts()?.cluster ?: error("NetBird has no public address")
            fun answers(port:Int,attempts:Int):Boolean {
                for(attempt in 1..attempts) {
                    if(runCatching { MinecraftStatusProbe.query(cluster,port,hostname,5000) }.isSuccess) return true
                    if(attempt<attempts) Thread.sleep(4000)
                }
                return false
            }
            // The service is made once (that needs a NetBird sign-in) and reused at every start; only a missing one, or one
            // still silent after FRONT_DOOR_TRIES pings, is created again. NetBird's proxy needs a few seconds to pick up a new service.
            var port=netBird.euNamePort()
            if(port<=0 || !answers(port,FRONT_DOOR_TRIES)) {
                port=try { netBird.ensureEuName(FRONT_DOOR_PORT) } catch(e:Exception) { if(needsNetBirdSignIn()) awaitNetBirdSignIn(); throw e }
                check(port>0) { "NetBird didn't assign a public port" }
                check(answers(port,8)) { "Java's server list couldn't reach the front door through NetBird" }
            }
            val handedTo=NameFrontDoor.checkTransfer(cluster,port,hostname,8000)
            check(handedTo==target) { "The front door didn't hand a test join to Playit" }
            frontDoorDetail=null
            if(frontDoorNeedsSignIn) { frontDoorNeedsSignIn=false; signInWatch?.cancel(false) }
            if(!active) {
                prefs.edit().putBoolean(PREF_FRONT_DOOR_ACTIVE,true).apply()
                append("$hostname now shows in Java's server list: pings go through NetBird to Phoenix's front door, and joins are handed to Playit")
                pointNameAtRoute(PublicRoute.PLAYIT,playitEndpoints())
            }
        } catch(e:Exception) {
            val why=e.message ?: "unknown error"
            // A quiet retry that fails again changes nothing, so it isn't written to the console every 10 minutes.
            if(!quiet || active) append("Front door: $why. The name stays on Playit, where joining works.")
            leaveFrontDoor(repoint=active)
            frontDoorDetail="Couldn't turn on: $why. Phoenix tries again every 10 minutes while the server runs."
        }
    }

    private fun needsNetBirdSignIn()=NetBirdOAuthSession.accountState(app).let { it==NetBirdOAuthSession.AccountState.EXPIRED || it==NetBirdOAuthSession.AccountState.NONE }

    /** Shows "Sign in to NetBird" and resumes the front door setup once the owner has signed in (checked every 5 s, 20 min). */
    private fun awaitNetBirdSignIn() {
        frontDoorNeedsSignIn=true
        signInWatch?.cancel(false)
        val until=SystemClock.elapsedRealtime()+20*60_000
        signInWatch=sampler.scheduleWithFixedDelay({
            when {
                !needsNetBirdSignIn() -> {
                    frontDoorNeedsSignIn=false; signInWatch?.cancel(false)
                    routeWork.execute {
                        val ports=runCatching { serverPorts() }.getOrNull()
                        val endpoints=playitEndpoints()
                        if(ports!=null && endpoints!=null && controller.snapshot().state()==ServerController.State.ONLINE)
                            runCatching { setUpFrontDoor(ports,endpoints) }.onFailure { frontDoorDetail=it.message }
                    }
                }
                SystemClock.elapsedRealtime()>until || !frontDoorWanted() -> { frontDoorNeedsSignIn=false; signInWatch?.cancel(false) }
            }
        },5,5,TimeUnit.SECONDS)
    }

    /** Closes the front door; with [repoint], the name's Java SRV goes back to Playit's own address. */
    private fun leaveFrontDoor(repoint:Boolean) {
        val wasActive=prefs.getBoolean(PREF_FRONT_DOOR_ACTIVE,false)
        prefs.edit().putBoolean(PREF_FRONT_DOOR_ACTIVE,false).apply()
        stopFrontDoor()
        if(repoint && wasActive && selectedRoute()==PublicRoute.PLAYIT) runCatching { pointNameAtRoute(PublicRoute.PLAYIT,playitEndpoints()) }
    }

    private fun stopFrontDoor() {
        frontDoor.stop()
        if(frontDoorLink && selectedRoute()?.usesNetBird!=true) {
            frontDoorLink=false
            runCatching { NetBirdTransport(app).stop() }
            // DNS lookups made while the VPN goes down can fail; give it a moment before the name is checked.
            Thread.sleep(3000)
        }
    }

    // ---- The optional custom name (Minecraft.How): DNS for whichever route is selected, never a relay

    private fun customNameEnabled()=prefs.getBoolean(PREF_NAME_ENABLED,true)
    /** Turning the name off keeps its records and account; addresses simply show the provider's own again. */
    fun setCustomNameEnabled(enabled:Boolean) {
        prefs.edit().putBoolean(PREF_NAME_ENABLED,enabled).apply()
        nameHealth=null; nameDetail=null
        if(enabled) routeWork.execute { runCatching { endpointsFor(selectedRoute())?.let { pointNameAtRoute(selectedRoute()!!,it) } } }
    }

    /** What the name's records must say for [endpoints]: the route record's target, and the Java SRV's target and port. */
    private data class NameTarget(val apex:String,val javaPort:Int,val bedrockPort:Int,val srvHost:String?)
    private fun nameTarget(endpoints:RouteEndpoints):NameTarget? {
        val java=endpoints.nameJava ?: endpoints.javaTarget ?: return null
        val bedrock=endpoints.bedrock
        // Bedrock has no SRV, so the name itself must lead to Bedrock's host; Java follows its SRV wherever it points. Playit's
        // IPv4 address (an A record, no IPv6) keeps Bedrock's server list working: it stays on "Loading ping" for names with IPv6.
        val apex=endpoints.bedrockIp ?: bedrock?.host ?: java.host
        val srvHost=java.host.takeIf { it!=apex && !java.isIpAddress }
        return NameTarget(apex,java.port ?: 25565,bedrock?.port ?: java.port ?: 25565,srvHost)
    }

    /**
     * Points the saved name at the selected route by editing its existing records in place (each write is confirmed by
     * reading it back), then checks public DNS a few times while caches expire. Without a usable Minecraft.How session
     * nothing is written; the owner is told what to change, and addresses fall back to the provider's own meanwhile.
     */
    private fun pointNameAtRoute(route:PublicRoute,endpoints:RouteEndpoints?) {
        val hostname=prefs.getString("minecraft-hostname",null) ?: return
        if(!customNameEnabled()) return
        prefs.edit().remove(PREF_NAME_VERIFIED).apply()
        if(!route.isPublic || endpoints==null) { nameHealth=NameHealth.OFF; nameDetail="$hostname isn't used while public access is off."; return }
        val target=nameTarget(endpoints) ?: run { nameHealth=NameHealth.ERROR; nameDetail="${route.title} has no Java address yet."; return }
        val cookie=MinecraftHowSession.cookieHeader()
        if(prefs.getLong("minecraft-hostname-account",-1L)<=0L || cookie==null) {
            nameHealth=NameHealth.SIGN_IN
            nameDetail="Point $hostname at ${route.title}: sign in to Minecraft.How, or change its records in your browser. Until then friends use the ${route.title} address."
            scheduleNameChecks(endpoints)
            return
        }
        nameHealth=NameHealth.UPDATING; nameDetail="Pointing $hostname at ${route.title}"
        val result=synchronized(dnsLock) {
            val client=MinecraftHowClient()
            val (verified,_)=verifiedDnsSession(client,cookie)
            client.switchRoute(verified,hostname,target.apex,target.javaPort,minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes },
                javaSrvHost=target.srvHost)
        }
        if(result.state!=MinecraftHowClient.EnsureState.SAVED) {
            nameHealth=if(result.state==MinecraftHowClient.EnsureState.NEEDS_SIGN_IN) NameHealth.SIGN_IN else NameHealth.ERROR
            nameDetail=result.detail
            return
        }
        dnsState=result.detail
        nameHealth=NameHealth.PROPAGATING; nameDetail="$hostname now points at ${route.title}. Waiting for public DNS (usually a few minutes)."
        scheduleNameChecks(endpoints)
    }

    /**
     * A bounded series of DNS checks while old answers expire: about 20 s, 1, 2, 5, 10, 20 and 35 minutes. Each asks
     * Minecraft.How's own servers first, so the early ones can't leave "no record" in the network's DNS cache.
     */
    private fun scheduleNameChecks(endpoints:RouteEndpoints) {
        for(delay in listOf(20L,60L,120L,300L,600L,1200L,2100L)) scheduleNameCheck(endpoints,delay)
    }
    private fun scheduleNameCheck(endpoints:RouteEndpoints,delay:Long) {
        val fingerprint=endpoints.fingerprint
        sampler.schedule({
            val current=endpointsFor(selectedRoute())
            if(current?.fingerprint==fingerprint && prefs.getString(PREF_NAME_VERIFIED,null)!=fingerprint)
                routeWork.execute { runCatching { validateCustomName(current) } }
        },delay,TimeUnit.SECONDS)
    }

    /**
     * Public DNS for the name, checked against the selected route's addresses: the route record (Bedrock) and the Java
     * SRV. Only a full match makes the name the primary address. With the server online, a join-style status ping
     * through the SRV target is made as well; DNS and Minecraft reachability stay separate results.
     */
    private fun validateCustomName(endpoints:RouteEndpoints):MinecraftHowDns.Verification? {
        val hostname=prefs.getString("minecraft-hostname",null) ?: return null
        val target=nameTarget(endpoints) ?: return null
        val zones=minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes }
        fun check(lookup:DnsRecordLookup,t:NameTarget=target)=MinecraftHowDns(lookup).validate(hostname,t.apex,t.javaPort,t.bedrockPort,
            zones,javaSrvHost=t.srvHost)
        // What Minecraft.How publishes, from its own name servers; the phone's resolver is asked only once that is right,
        // because asking it earlier makes it remember "no record" for half an hour. Unreachable servers fall back to the old check.
        val source=AuthoritativeDnsLookup.zoneOf(hostname,zones)?.let { zone ->
            val lookup=AuthoritativeDnsLookup(zone,AndroidDnsRecordLookup())
            runCatching { check(lookup) }.getOrNull()?.takeIf { lookup.reached }
        }
        recordsLiveFor=endpoints.fingerprint.takeIf { source?.state==MinecraftHowDns.State.VERIFIED }
        if(source!=null && source.state!=MinecraftHowDns.State.VERIFIED) {
            dnsState=source.detail
            noteNameCheck("source-wrong|${endpoints.fingerprint}","$hostname: Minecraft.How's DNS doesn't publish the right records yet (${source.detail})")
            if(nameHealth!=NameHealth.SIGN_IN) { nameHealth=NameHealth.PROPAGATING; nameDetail="Minecraft.How's DNS: ${source.detail}" }
            return source
        }
        val result=check(AndroidDnsRecordLookup())
        dnsState=result.detail
        // Java's SRV just moved from Playit to the front door: until this network forgets Playit's record, the name still
        // leads to Playit, where joining works. That is the name working (Test DNS said "Waiting for DNS" here, 2026-10-12).
        val playitTarget=endpoints.takeIf { result.state!=MinecraftHowDns.State.VERIFIED && source!=null && it.nameJava!=null }
            ?.let { nameTarget(it.copy(nameJava=null)) }
        if(playitTarget!=null && runCatching { check(AndroidDnsRecordLookup(),playitTarget) }.getOrNull()?.state==MinecraftHowDns.State.VERIFIED) {
            noteNameCheck("old-playit|${endpoints.fingerprint}","$hostname: Minecraft.How publishes the front door; this phone's network " +
                "still has Playit's record, where joining works")
            prefs.edit().putString(PREF_NAME_VERIFIED,endpoints.fingerprint).putBoolean(PREF_NAME_BEDROCK,result.bedrockDnsMatches).apply()
            nameHealth=null
            nameDetail="Joining works. Java's server list shows the name once DNS caches drop Playit's older record (usually within 10 minutes)."
            return result
        }
        noteNameCheck("${source!=null}|${result.state}|${endpoints.fingerprint}",when {
            source==null -> "$hostname: Minecraft.How's name servers didn't answer, so only this phone's network was asked: ${result.detail}"
            result.state==MinecraftHowDns.State.VERIFIED -> "$hostname: Minecraft.How publishes the right records and this phone's network sees them"
            else -> "$hostname: Minecraft.How publishes the right records; this phone's network still has an older answer" +
                (result.negativeTtlSeconds?.let { " (cached for up to $it s more)" } ?: "")
        })
        if(result.state!=MinecraftHowDns.State.VERIFIED && source!=null) {
            // Published and correct, but this phone's network still repeats an older answer: nothing to fix, only to wait.
            val wait=result.negativeTtlSeconds?.takeIf { it>0 }
            nameHealth=NameHealth.PROPAGATING
            nameDetail="The records are right at Minecraft.How. This phone's network still remembers an older answer" +
                (wait?.let { " for about ${(it+59)/60} more min" } ?: "") + "; Phoenix checks again by itself."
            wait?.let {
                val at=SystemClock.elapsedRealtime()+(it+15L)*1000
                if(kotlin.math.abs(at-cacheCheckAt)>30_000) { cacheCheckAt=at; scheduleNameCheck(endpoints,it+15L) }
            }
            return result
        }
        if(result.state==MinecraftHowDns.State.VERIFIED) {
            prefs.edit().putString(PREF_NAME_VERIFIED,endpoints.fingerprint).putBoolean(PREF_NAME_BEDROCK,result.bedrockDnsMatches).apply()
            nameHealth=null
            nameDetail=when {
                endpoints.nameJava!=null -> "DNS points at Playit, with Java through Phoenix's front door on NetBird: the server list shows your server and joins play through Playit."
                selectedRoute()==PublicRoute.PLAYIT && !playit.customNamesListed() -> "DNS points at Playit. Joining works; " +
                    "Playit Free only answers server-list pings for its own name, so Java's list may show this name as unreachable."
                else -> "DNS points at your server."
            }
        } else if(result.javaDnsMatches) {
            nameHealth=NameHealth.PROPAGATING; nameDetail="Java's record is right; Bedrock's isn't yet. ${result.detail}"
        } else {
            nameHealth=if(nameHealth==NameHealth.SIGN_IN) NameHealth.SIGN_IN else NameHealth.PROPAGATING
            if(nameHealth==NameHealth.PROPAGATING) nameDetail=result.detail
        }
        return result
    }

    @Volatile private var lastNameCheck:String?=null
    /** One console line per change of the name check's outcome (checks repeat for half an hour; unchanged ones stay quiet). */
    private fun noteNameCheck(key:String,line:String) { if(lastNameCheck!=key) { lastNameCheck=key; append("Custom name check: $line") } }

    /** Test DNS: public DNS and, with the server online, whether Minecraft answers through the name's target. */
    fun testCustomName()=routeWork.execute {
        val endpoints=endpointsFor(selectedRoute()) ?: run { nameHealth=NameHealth.ERROR; nameDetail="Connect a public route first."; return@execute }
        nameHealth=NameHealth.UPDATING; nameDetail="Checking public DNS"
        val result=runCatching { validateCustomName(endpoints) }.getOrElse { nameHealth=NameHealth.UNREACHABLE; nameDetail="DNS couldn't be checked (no answer from the resolver). Nothing is assumed."; return@execute }
        if(result?.state==MinecraftHowDns.State.VERIFIED && controller.snapshot().state()==ServerController.State.ONLINE) {
            val hostname=prefs.getString("minecraft-hostname",null)!!
            val join=probeJava(endpoints,attempts=2)
            val list=probeJava(endpoints,attempts=1,handshakeHost=hostname,at=endpoints.nameJava)
            nameDetail=when {
                join && list -> "DNS points at your server, and Minecraft answers through $hostname."
                join -> "DNS is right and joining works. The route checks names, so the server list may show $hostname as unreachable; the ${selectedRoute()?.title} address shows it normally."
                else -> "DNS is right, but Minecraft didn't answer through ${selectedRoute()?.title} just now. Check the server and the route."
            }
        }
    }

    // ------------------------------------------------------------ How friends connect, and the regional proxy

    @Volatile private var regionalRouteReport:String?=null
    @Volatile private var regionalPath:String?=null
    @Volatile private var voicePortCache:Pair<Long,Int>?=null
    private fun regionalRouteActive()=selectedRoute()==PublicRoute.NETBIRD_REGIONAL
    private val pairingHost=ProxyPairingHost(netBird,{ append(it) }) { scheduleProxyChecks() }

    /** "easy" or "regional" once chosen; installs that already have public ports count as Easy Setup. */
    private fun networkMode(hasPorts:Boolean):String?=prefs.getString(PREF_NETWORK_MODE,null) ?: if(hasPorts) "easy" else null

    /**
     * Records how friends connect. Choosing Easy Setup while players use the regional proxy moves them back first, so
     * the mode and the live route never disagree. The world and server are never touched.
     */
    fun chooseRegionalArea(area:String) { require(OracleRegions.area(area)!=null); prefs.edit().putString(PREF_REGIONAL_AREA,area).apply() }
    /** Regional Optimized is experimental: it shows its setup only after the owner confirmed its warning once. */
    fun setRegionalEnabled(enabled:Boolean) {
        val edit=prefs.edit().putBoolean(PREF_REGIONAL_ENABLED,enabled)
        if(enabled) edit.putBoolean(PREF_EXPERIMENTAL_ACK,true)
        edit.apply()
    }

    /** Simple Voice Chat's UDP port, or 0 when it is not installed. Its default is 24454; -1 means "the Java port". */
    private fun voiceChatPort():Int {
        val mods=File(server,"mods"); val config=File(server,"config/voicechat/voicechat-server.properties")
        val stamp=mods.lastModified()*31+config.lastModified()
        voicePortCache?.takeIf { it.first==stamp }?.let { return it.second }
        val port=if(modManager.enabledJars().none { it.name.startsWith("voicechat-") }) 0 else {
            val raw=if(config.isFile) Properties().apply { config.inputStream().use { load(it) } }.getProperty("port")?.trim() else null
            when(val value=raw?.toIntOrNull() ?: 24454) { -1 -> javaListenerPort(); in 1..65535 -> value; else -> error("Simple Voice Chat has an invalid port") }
        }
        voicePortCache=stamp to port
        return port
    }
    private fun regionalPorts()=org.phoenix.core.network.netbird.NetBirdByop.Ports(javaListenerPort(),bedrockListenerPort(),voiceChatPort())

    private val voiceSynced=AtomicBoolean(false)
    /** A fresh NetBird sign-in: account work that failed while the session was expired can run now. */
    fun onNetBirdSignedIn() {
        voiceSynced.set(false)
        if(host.isAlive) syncEuVoice()
        runCatching { pairingHost.ensureRunning() }
        // A sign-in while NetBird was already connected brings no new connect edge, so the add-on's setup resumes here too.
        if(prefs.getBoolean(PREF_FRONT_DOOR_SETUP,false)) watchFrontDoorSetup()
    }
    /**
     * Once per app run, after online play is up: keeps NetBird's EU voice service in step with Simple Voice Chat
     * (created when the mod is installed, moved when its port changes, removed when it is gone). Off the startup path.
     */
    private fun syncEuVoice() {
        if(selectedRoute()!=PublicRoute.NETBIRD_EU) return
        if(!NetBirdOAuthSession.hasSession(app) || !voiceSynced.compareAndSet(false,true)) return
        accountWork.execute {
            runCatching {
                val port=voiceChatPort()
                if(port>0 || netBird.euVoicePort()>0) {
                    val public=netBird.ensureEuVoice(port)
                    if(port>0 && public>0) append("Simple Voice Chat is forwarded through NetBird's EU proxy on UDP $public")
                    if(applyVoiceHost() && host.isAlive) pendingRestart=true
                }
            }.onFailure { voiceSynced.set(false); append("Voice chat forwarding not updated: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /**
     * Simple Voice Chat tells each player where to send voice. Points it at the route players use: the regional proxy's
     * address and the real port, or the EU voice service's public port. A value the owner typed in is left alone.
     * Returns true when the file changed (the server reads it at start).
     */
    private fun applyVoiceHost():Boolean {
        val file=File(server,"config/voicechat/voicechat-server.properties").takeIf { it.isFile } ?: return false
        val port=voiceChatPort().takeIf { it>0 } ?: return false
        // The selected route's verified voice endpoint, or empty: Simple Voice Chat then uses the address players joined.
        val wanted=voiceHostFor(selectedRoute(),port)
        val lines=file.readLines()
        val current=lines.firstOrNull { it.trimStart().startsWith("voice_host=") }?.substringAfter('=')?.trim().orEmpty()
        if(current.isNotEmpty() && current!=prefs.getString(PREF_VOICE_HOST,null)) return false
        if(current==wanted) return false
        val updated=if(lines.any { it.trimStart().startsWith("voice_host=") }) lines.map { if(it.trimStart().startsWith("voice_host=")) "voice_host=$wanted" else it }
            else lines+"voice_host=$wanted"
        file.writeText(updated.joinToString("\n",postfix="\n"))
        prefs.edit().putString(PREF_VOICE_HOST,wanted).apply()
        append(if(wanted.isEmpty()) "Simple Voice Chat: voice uses the address players join with" else "Simple Voice Chat: voice goes through $wanted")
        return true
    }

    /** The voice_host value for [route]; never a custom name, whose UDP reachability is not proven. */
    private fun voiceHostFor(route:PublicRoute?,port:Int):String = when(route) {
        PublicRoute.PLAYIT -> playit.savedEndpoints()?.voice?.text.orEmpty()
        PublicRoute.NETBIRD_REGIONAL -> netBird.regionalProxy().takeIf { it.ready }?.ip?.let { "$it:$port" }.orEmpty()
        PublicRoute.NETBIRD_EU -> netBird.euVoicePort().takeIf { it>0 }?.let { eu -> runCatching { "${netBird.endpoints().cluster()}:$eu" }.getOrDefault("") }.orEmpty()
        else -> ""
    }

    /**
     * Step 1 of the regional setup: makes a one-time pairing code and starts waiting for the VM. The owner then pastes
     * the code into Oracle's deployment form; Phoenix does the rest when the VM calls.
     */
    fun startRegionalSetup()=launchAction {
        check(regionalPairingPort>0) { "No free local port for pairing" }
        progress="Preparing your pairing code"
        netBird.startRegionalPairing(regionalPorts(),regionalPairingPort)
        pairingHost.ensureRunning()
        append("Pairing code ready. Paste it into the Oracle form; Phoenix finishes setup when your proxy calls.")
    }
    /** The open pairing code, read when the owner taps Copy. Never part of the dashboard or the log. */
    fun regionalPairingCode():String?=runCatching { netBird.pendingPairing()?.code?.encode() }.getOrNull()
    fun cancelRegionalSetup()=launchAction { netBird.cancelRegionalPairing() }
    /** A free local port for the pairing listener, picked once per run (the pairing service records which). */
    private val regionalPairingPort:Int by lazy {
        val saved=runCatching { netBird.pendingPairing()?.localPort }.getOrNull()
        saved ?: (0 until 20).map { 40000+java.security.SecureRandom().nextInt(9000) }
            .firstOrNull { port -> runCatching { java.net.ServerSocket(port).close() }.isSuccess } ?: 0
    }

    /** After pairing the proxy needs a few minutes to install and register; check a bounded number of times. */
    private fun scheduleProxyChecks() {
        for(delay in listOf(60L,120L,240L,420L,600L)) sampler.schedule({
            val state=netBird.regionalProxy()
            if(!state.ready && state.stage!="NOT_SET_UP" && state.stage!="PAIRING") launchQuietly { checkRegionalProxyAction() }
        },delay,TimeUnit.SECONDS)
    }

    fun checkRegionalProxy()=launchAction { checkRegionalProxyAction() }
    private fun checkRegionalProxyAction() {
        progress="Checking your regional proxy"
        val state=netBird.checkRegionalProxy(regionalPorts())
        if(state.ready) measureRegionalRoute(state.ip!!) else { regionalRouteReport=null; regionalPath=null }
    }

    /** Phone → regional proxy → this phone through the public internet, plus NetBird's own view of the proxy tunnel. */
    private fun measureRegionalRoute(ip:String) {
        if(controller.snapshot().state()!=ServerController.State.ONLINE) { regionalRouteReport="Start the server to test the route"; return }
        progress="Measuring the regional route"
        val bedrock=(1..7).mapNotNull {
            val start=System.nanoTime()
            runCatching { org.phoenix.core.network.BedrockStatusProbe.query(ip,bedrockListenerPort(),2000) }.getOrNull()?.let { (System.nanoTime()-start)/1_000_000 }
        }.sorted()
        val java=runCatching { MinecraftStatusProbe.query(ip,javaListenerPort()) }.isSuccess
        val paths=NetBirdVpnService.peerPaths()
        val direct=paths.firstOrNull { it.connected && !it.relayed && it.remoteEndpoint.substringBeforeLast(':').trim('[',']')==ip }
        direct?.let { prefs.edit().putString(PREF_PROXY_OVERLAY_IP,it.overlayIp).apply() }
        val proxyPeer=direct ?: paths.firstOrNull { it.overlayIp==prefs.getString(PREF_PROXY_OVERLAY_IP,null) }
        regionalPath=when {
            direct!=null -> "Direct WireGuard to the proxy, ${direct.latencyMs} ms"
            proxyPeer?.relayed==true -> "Relayed by NetBird (${proxyPeer.remoteEndpoint.ifBlank { "relay" }}), ${proxyPeer.latencyMs} ms: not direct"
            else -> "Tunnel path unknown: NetBird has not reported a direct connection to the proxy"
        }
        regionalRouteReport=(if(java) "Java answers" else "Java did not answer")+" · "+
            (if(bedrock.isEmpty()) "Bedrock did not answer" else "Bedrock ${bedrock.size}/7, median ${bedrock[bedrock.size/2]} ms")+
            " (phone → proxy → phone)"
        append("Regional route: $regionalRouteReport · $regionalPath")
    }

    /**
     * Sends players through the regional proxy, or back through NetBird's EU proxy. With a Minecraft.How hostname the DNS
     * records move too (A and SRV, each confirmed by readback); without one, the address Phoenix shows changes. Both
     * routes stay configured, so switching back is always possible.
     */
    fun switchPlayersToRegional(regional:Boolean)=selectRoute(if(regional) PublicRoute.NETBIRD_REGIONAL else PublicRoute.NETBIRD_EU)
    /** Deletes the regional services and revokes the proxy token in NetBird. The owner destroys the Oracle stack. */
    fun removeRegionalProxy()=launchAction {
        check(!regionalRouteActive()) { "Move players back to the EU proxy before removing the regional proxy" }
        progress="Removing the regional proxy from NetBird"
        netBird.removeRegionalProxy()
        regionalRouteReport=null; regionalPath=null
    }

    /**
     * Rough traffic through the regional proxy this calendar month, from NetBird's byte counters for the proxy peer
     * (what the phone sends to and receives from it). Oracle's outbound transfer is about the same plus overhead.
     */
    private fun recordProxyTraffic() {
        val overlay=prefs.getString(PREF_PROXY_OVERLAY_IP,null) ?: return
        val peer=NetBirdVpnService.peerPaths().firstOrNull { it.overlayIp==overlay } ?: return
        val month=java.text.SimpleDateFormat("yyyy-MM",java.util.Locale.US).format(java.util.Date())
        val counter=peer.bytesRx+peer.bytesTx
        val last=prefs.getLong(PREF_PROXY_COUNTER,0L)
        val delta=if(counter>=last) counter-last else counter
        val total=(if(prefs.getString(PREF_PROXY_MONTH,null)==month) prefs.getLong(PREF_PROXY_BYTES,0L) else 0L)+delta
        prefs.edit().putLong(PREF_PROXY_COUNTER,counter).putString(PREF_PROXY_MONTH,month).putLong(PREF_PROXY_BYTES,total).apply()
    }
    private fun proxyTrafficThisMonth():Long? {
        val month=java.text.SimpleDateFormat("yyyy-MM",java.util.Locale.US).format(java.util.Date())
        return if(prefs.getString(PREF_PROXY_MONTH,null)==month) prefs.getLong(PREF_PROXY_BYTES,0L) else null
    }

    fun checkMinecraftHostname(hostname:String)=launchAction {
        hostnameCheckName=hostname
        hostnameCheckDetail="Checking the name and domain with Minecraft.How…"
        val result=MinecraftHowClient().checkHostname(MinecraftHowSession.cookieHeader().orEmpty(),hostname,
            minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes })
        hostnameCheckDetail=result.detail
    }
    fun reserveMinecraftHostname(hostname:String)=launchAction {
        hostnameCheckName=hostname
        hostnameCheckDetail="Saving your hostname with Minecraft.How…"
        val prior=prefs.getString("hostname-reservation-pending",null)
        if(prior!=null && prior!=hostname) {
            hostnameCheckDetail="Return to $prior and tap Save hostname to confirm its earlier save before choosing another hostname."
            return@launchAction
        }
        val result=MinecraftHowClient().reserveHostname(
            MinecraftHowSession.cookieHeader().orEmpty(),hostname,
            minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes },
            allowCreate=prior==null,
            pendingAccountId=if(prior!=null)prefs.getLong("hostname-reservation-account",-1L) else null,
            beforeCreate={ id -> prefs.edit().putString("hostname-reservation-pending",hostname)
                .putLong("hostname-reservation-account",id).commit() },
        )
        hostnameCheckDetail=result.detail
        when(result.state) {
            MinecraftHowClient.EnsureState.SAVED -> {
                check(prefs.edit().putString("minecraft-hostname",result.hostname)
                    .putLong("minecraft-hostname-account",result.accountId ?: -1L)
                    .remove("hostname-reservation-pending").remove("hostname-reservation-account").commit())
                dnsState="Hostname saved. DNS is set up when online play connects."
                refreshMinecraftHow()
            }
            MinecraftHowClient.EnsureState.INVALID_INPUT, MinecraftHowClient.EnsureState.CONFLICT,
            MinecraftHowClient.EnsureState.ZONE_UNAVAILABLE, MinecraftHowClient.EnsureState.NEEDS_SIGN_IN -> {
                // These outcomes precede a create or follow a definite provider rejection and readback.
                if(prior==null)prefs.edit().remove("hostname-reservation-pending").remove("hostname-reservation-account").commit()
            }
            else -> Unit
        }
    }
    /** "Point to <route>": the outcome, good or bad, is shown right under the name, where the owner tapped. */
    fun saveMinecraftHostname(hostname:String)=launchAction {
        nameHealth=NameHealth.UPDATING; nameDetail="Saving the records at Minecraft.How…"
        val result=try { saveMinecraftHostnameAction(hostname,allowStopped=false) }
            catch(e:Exception) { nameHealth=if(e is MinecraftHowSignInRequired) NameHealth.SIGN_IN else NameHealth.ERROR; nameDetail=e.message; throw e }
        if(result.state==MinecraftHowClient.EnsureState.SAVED) nameDetail="Records saved at Minecraft.How. Checking that its DNS publishes them…"
        else { nameHealth=if(result.state==MinecraftHowClient.EnsureState.NEEDS_SIGN_IN) NameHealth.SIGN_IN else NameHealth.ERROR; nameDetail=result.detail }
    }

    /**
     * Confirms [cookie] belongs to the Minecraft.How account that owns the saved hostname and carries a DNS write
     * session, refreshing only the DNS security cookie when needed. Returns the cookie to use and the owner's id.
     */
    private fun verifiedDnsSession(client:MinecraftHowClient,cookie:String):Pair<String,Long> {
        val expectedAccountId=prefs.getLong("minecraft-hostname-account",-1L)
        if(expectedAccountId<=0L) throw MinecraftHowSignInRequired("Phoenix does not have a verified owner for the saved hostname. Sign in to its Minecraft.How account and confirm the same hostname.")
        val session=client.verifySession(cookie)
        minecraftHowLinked=session.signedIn
        if(!session.signedIn) {
            minecraftHowState=session.detail
            throw MinecraftHowSignInRequired(session.detail)
        }
        if(session.accountId!=expectedAccountId) {
            minecraftHowLinked=false
            minecraftHowState="Sign in to the Minecraft.How account that owns this saved hostname."
            throw MinecraftHowSignInRequired(minecraftHowState)
        }
        var verified=cookie
        if(!client.hasDnsWriteSession(cookie)) {
            // Like a browser, keep the DNS security cookie Minecraft.How re-issues with every page when
            // only that cookie expired; the same account must verify again before any write.
            val refreshed=client.refreshedSessionCookies(cookie)?.let(MinecraftHowSession::installIssuedCookies)
            val again=refreshed?.let(client::verifySession)
            if(refreshed==null || again?.signedIn!=true || again.accountId!=expectedAccountId || !client.hasDnsWriteSession(refreshed)) {
                minecraftHowState="Reload Minecraft.How sign-in so its DNS security cookie is available."
                throw MinecraftHowSignInRequired(minecraftHowState)
            }
            verified=refreshed
        }
        MinecraftHowSession.rememberVerified(app)
        return verified to expectedAccountId
    }

    /** Shared setup path for the explicit Save action and the one-shot NetBird continuation. */
    private fun saveMinecraftHostnameAction(hostname:String,allowStopped:Boolean):MinecraftHowClient.EnsureResult {
        val client=MinecraftHowClient()
        val clean=MinecraftHowDns.normalizeHostname(hostname,minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes })
        var cookie=MinecraftHowSession.cookieHeader() ?: run {
            minecraftHowLinked=false
            minecraftHowHasSession=false
            minecraftHowState="Not signed in"
            throw MinecraftHowSignInRequired("Sign in to Minecraft.How before setting up the saved hostname.")
        }
        val serverState=controller.snapshot().state()
        // Ports are read from saved configuration, so any settled server state works for both paths.
        check(serverState==ServerController.State.READY || serverState==ServerController.State.STOPPED || serverState==ServerController.State.ONLINE) {
            if(allowStopped) "Install Minecraft first, or wait for it to finish starting or stopping." else "Wait for the server to finish starting or stopping."
        }
        check(prefs.getString("minecraft-hostname",null)==clean) { "Select and save your owned Minecraft.How hostname first" }
        val (verifiedCookie,expectedAccountId)=verifiedDnsSession(client,cookie)
        cookie=verifiedCookie

        val dnsBefore=client.readDnsRecordSnapshot(cookie,clean)
        when(dnsBefore.state) {
            MinecraftHowClient.DnsSnapshotState.NEEDS_SIGN_IN -> {
                minecraftHowLinked=false
                minecraftHowState=dnsBefore.detail
                throw MinecraftHowSignInRequired(dnsBefore.detail)
            }
            MinecraftHowClient.DnsSnapshotState.NOT_FOUND -> {
                minecraftHowLinked=false
                minecraftHowState="Sign in to the Minecraft.How account that owns this saved hostname."
                throw MinecraftHowSignInRequired("The saved hostname is not owned by the current Minecraft.How account. Sign in to its owner; Phoenix will not create a hostname.")
            }
            else -> Unit
        }
        if(dnsBefore.accountId!=expectedAccountId) {
            minecraftHowLinked=false
            minecraftHowState="Sign in to the Minecraft.How account that owns this saved hostname."
            throw MinecraftHowSignInRequired("The saved hostname is not owned by the current Minecraft.How account. Sign in to its owner; Phoenix will not create a hostname.")
        }
        check(dnsBefore.state==MinecraftHowClient.DnsSnapshotState.READ && dnsBefore.hostname==clean && dnsBefore.domainId!=null) { dnsBefore.detail }
        captureDnsBaseline(clean,expectedAccountId,dnsBefore.domainId!!,dnsBefore.records)

        val offered=client.loadZones(cookie)
        if(!offered.signedIn) {
            minecraftHowLinked=false
            minecraftHowState=offered.detail
            throw MinecraftHowSignInRequired(offered.detail)
        }
        check(offered.zones.any { clean.endsWith(".$it") }) { "The saved Minecraft.How domain is unavailable. Refresh the account's domain choices before continuing." }
        minecraftHowZones=offered.zones

        // Port readers intentionally parse persisted configuration, so service targets remain
        // accurate when the server is STOPPED and no Minecraft listener is currently running.
        val route=selectedRoute()?.takeIf { it.isPublic } ?: error("Choose how friends connect first (Playit or NetBird), then link the name.")
        progress="Checking the public addresses"
        if(route.usesNetBird) netBird.provision(javaListenerPort(),bedrockListenerPort())
        val endpoints=endpointsFor(route) ?: error("${route.title} has no public address yet. Connect it first.")
        val target=nameTarget(endpoints) ?: error("${route.title} has no Java address yet.")
        val relay=target.apex
        val tcpPort=target.javaPort
        val udpPort=target.bedrockPort
        if(target.srvHost==null) org.phoenix.core.network.MinecraftDnsPlan.forEndpoints(clean,relay,tcpPort,relay,udpPort)
        // Both protocols target the same recorded cluster. Public DNS and gameplay are checked separately.
        progress="Saving DNS records"
        val prior=prefs.getString("dns-pending-hostname",null)
        check(prior==null || prior==clean) { "Reconcile the previous hostname setup before choosing another name" }
        val uncertain=prior==clean
        check(prefs.edit().putString("dns-pending-hostname",clean).commit())
        val result=synchronized(dnsLock) { client.ensureHostnameAndRecords(cookie,clean,relay,tcpPort,udpPort,
            allowMutations=!uncertain,
            availableZones=minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes },
            allowHostnameCreation=false,javaSrvHost=target.srvHost) }
        dnsState=result.detail
        if(result.state==MinecraftHowClient.EnsureState.SAVED) {
            check(prefs.edit().putString("minecraft-hostname",clean).putLong("minecraft-hostname-account",expectedAccountId)
                .remove("dns-pending-hostname").commit())
            minecraftHowLinked=true
            minecraftHowState="Signed in · DNS saved"
            prefs.edit().putBoolean(PREF_NAME_ENABLED,true).remove(PREF_NAME_VERIFIED).apply()
            nameHealth=NameHealth.PROPAGATING; nameDetail="Records saved. Waiting for public DNS (usually a few minutes)."
            scheduleNameChecks(endpoints)
            if(!autoSetupInFlight.get()) {
                setAutoNetworkSetupState(prefs.getString(PREF_AUTO_SETUP_ID,null),"COMPLETE","DNS records saved for $clean.",
                    targetHostname=clean,targetAccountId=expectedAccountId,replaceTarget=true)
            }
        } else if(result.state!=MinecraftHowClient.EnsureState.RECOVERY_REQUIRED) {
            check(prefs.edit().remove("dns-pending-hostname").commit())
            if(result.state==MinecraftHowClient.EnsureState.NEEDS_SIGN_IN)minecraftHowLinked=false
        }
        return result
    }

    /** Keeps the first authenticated pre-change snapshot and refuses to replace another baseline. */
    private fun captureDnsBaseline(hostname:String,accountId:Long,domainId:Long,records:List<MinecraftHowClient.DnsRecordSnapshot>) {
        val destination=File(app.noBackupFilesDir,"netbird-dns-before.json")
        val atomic=android.util.AtomicFile(destination)
        val backup=File(destination.path+".bak")
        if(destination.exists() || backup.exists()) {
            val existingBytes=atomic.readFully()
            val existing=try { JSONObject(String(existingBytes,Charsets.UTF_8)) } finally { existingBytes.fill(0) }
            check(existing.optString("hostname")==hostname && existing.optLong("accountId",-1L)==accountId &&
                existing.optLong("domainId",-1L)==domainId && existing.optJSONArray("records")!=null) {
                "A different Minecraft.How DNS baseline already exists. It was preserved; no DNS record changes were made."
            }
            return
        }
        val recordArray=org.json.JSONArray()
        records.forEach { record -> recordArray.put(JSONObject().put("id",record.id).put("type",record.type)
            .put("name",record.name ?: JSONObject.NULL).put("content",record.content).put("ttl",record.ttl)
            .put("priority",record.priority ?: JSONObject.NULL).put("port",record.port ?: JSONObject.NULL)) }
        val bytes=JSONObject().put("schema","phoenix-netbird-dns-baseline-v1").put("capturedAt",Instant.now().toString())
            .put("source","Minecraft.How authenticated account session; GET only").put("accountId",accountId)
            .put("domainId",domainId).put("hostname",hostname).put("recordCount",records.size).put("records",recordArray)
            .toString().toByteArray(Charsets.UTF_8)
        val output=atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch(failure:Exception) { atomic.failWrite(output); throw failure }
        finally { bytes.fill(0) }
    }
    /**
     * Read-only account check against Minecraft.How itself. It runs on its own worker so another
     * operation can never silently skip it, and it never throws: every outcome becomes visible state.
     */
    @Volatile private var lastMinecraftHowRefresh=0L
    /** [throttled] is for app resumes: switching apps shouldn't cost two HTTPS calls every time. */
    fun refreshMinecraftHow(throttled:Boolean=false) {
        val now=SystemClock.elapsedRealtime()
        if(throttled && lastMinecraftHowRefresh!=0L && now-lastMinecraftHowRefresh<60_000) return
        if(!accountRefreshing.compareAndSet(false,true)) return
        lastMinecraftHowRefresh=now
        minecraftHowChecking=true
        accountWork.execute {
            try {
                val cookie=MinecraftHowSession.cookieHeader()
                minecraftHowHasSession=cookie!=null
                val verifiedBefore=MinecraftHowSession.verifiedBefore(app)
                if(cookie==null) {
                    minecraftHowLinked=false
                    minecraftHowState=if(verifiedBefore) "Sign-in expired. Sign in again to manage your hostname."
                        else "Not signed in. Sign in to Minecraft.How to choose your public hostname"
                    return@execute
                }
                val client=MinecraftHowClient()
                val session=client.verifySession(cookie)
                minecraftHowLinked=NetworkIntent.linkedAfterCheck(minecraftHowLinked,session.signedIn,session.reachable)
                minecraftHowState=if(!session.signedIn && session.reachable && verifiedBefore) "Sign-in expired. Sign in again to manage your hostname." else session.detail
                if(session.signedIn) {
                    MinecraftHowSession.rememberVerified(app)
                    val zones=client.loadZones(cookie)
                    if(zones.zones.isNotEmpty())minecraftHowZones=zones.zones
                    else minecraftHowState="${session.detail} · ${zones.detail}"
                }
            } catch(e:Exception) {
                minecraftHowState="Could not check the Minecraft.How account. Refresh to retry"
            } finally {
                minecraftHowChecking=false
                accountRefreshing.set(false)
            }
        }
    }
    fun validateMinecraftDns()=testCustomName()
    /**
     * For Minecraft.How accounts Phoenix cannot sign in to (Google refuses sign-in inside apps): the user
     * adds the two records on minecraft.how in their own browser, and Phoenix only reads public DNS. The
     * name is used once both records point at this server's permanent ports; no account session is involved.
     */
    fun useBrowserHostname(hostname:String)=launchAction {
        val clean=MinecraftHowDns.normalizeHostname(hostname,MinecraftHowDns.supportedSuffixes)
        val endpoints=endpointsFor(selectedRoute()) ?: error("Connect Playit or NetBird first; the records point at its address.")
        val target=nameTarget(endpoints) ?: error("The route has no Java address yet.")
        progress="Checking public DNS for $clean"
        val result=MinecraftHowDns().validate(clean,target.apex,target.javaPort,target.bedrockPort,javaSrvHost=target.srvHost)
        dnsState=result.detail
        check(result.state==MinecraftHowDns.State.VERIFIED) { result.detail }
        check(prefs.edit().putString("minecraft-hostname",clean).remove("minecraft-hostname-account").remove("dns-pending-hostname")
            .putBoolean(PREF_NAME_ENABLED,true).putString(PREF_NAME_VERIFIED,endpoints.fingerprint).putBoolean(PREF_NAME_BEDROCK,result.bedrockDnsMatches).commit())
        nameHealth=null
        setAutoNetworkSetupState(UUID.randomUUID().toString(),"COMPLETE","DNS records found for $clean.",
            targetHostname=clean,targetAccountId=null,replaceTarget=true)
    }
    fun export(uri:Uri)=launchAction {
        val output=app.contentResolver.openOutputStream(uri) ?: error("Cannot open export destination")
        output.bufferedWriter().use { writer ->
            writer.write(JSONObject().put("schemaVersion",1).put("recordType","provenance").put("dependencyManifest",JSONObject(app.assets.open("DEPENDENCY_MANIFEST.json").bufferedReader().use { it.readText() }))
                .put("runtimeManifest",if(File(home,"manifest.json").isFile)JSONObject(File(home,"manifest.json").readText()) else JSONObject.NULL).put("optimizationMods",org.json.JSONArray()).toString()); writer.newLine()
            if(exportFile.isFile)exportFile.bufferedReader().useLines { it.forEach { line -> writer.write(line); writer.newLine() } }
        }; append("Telemetry exported through document picker")
    }
    fun renameServer(name:String)=launchAction {
        require(name.isNotBlank() && name.length<=64 && name.none { it=='\n' || it=='\r' || it=='\u0000' }) { "Use a server name of 1–64 characters" }
        val wasRunning=host.isAlive
        if(wasRunning) stopForChange("Saving world before changing the name")
        writeProperties(mapOf("motd" to name))
        check(prefs.edit().putString("server-name",name).commit())
        append("Server renamed to $name; world folder and provider tunnel identity preserved")
        if(wasRunning) startAfterChange("Restarting with the new name")
    }
    private fun rememberStartMods() {
        modIdsAtStart=if(provider()==BaselineProvider.FABRIC) runCatching { modManager.enabledIds() }.getOrDefault(emptySet()) else emptySet()
        pendingRestart=false
    }
    /** Stops a running server for a change that needs it stopped, waiting for a clean save. */
    private fun stopForChange(message:String) {
        progress=message; controller.stop().get()
        val deadline=SystemClock.elapsedRealtime()+75000
        while(host.isAlive && SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
        check(!host.isAlive && controller.snapshot().state()==ServerController.State.STOPPED) { "Server did not stop cleanly; nothing was changed" }
    }
    private fun startAfterChange(message:String) {
        progress=message; jvmMetrics=JSONObject(); mspt=null; rememberStartMods(); startPressedAt=SystemClock.elapsedRealtime()
        launchWithModRollback { controller.start().get() }; connectSavedRelay()
    }
    /** server.properties, re-read only when the file changes (the dashboard asks every second). Do not modify the result. */
    private fun properties():Properties = synchronized(propertiesLock) {
        val file=File(server,"server.properties")
        val stamp=if(file.isFile) file.lastModified()*31+file.length() else 0L
        propertiesCache?.takeIf { it.first==stamp }?.second ?: Properties().also { loaded ->
            if(file.isFile) file.inputStream().use(loaded::load)
            propertiesCache=stamp to loaded
        }
    }
    /** Changes keys in server.properties atomically and keeps every other setting. */
    private fun writeProperties(changes:Map<String,String>) = synchronized(propertiesLock) {
        val file=File(server,"server.properties")
        val updated=Properties(); if(file.isFile) file.inputStream().use(updated::load)
        changes.forEach { (key,value) -> updated.setProperty(key,value) }
        val atomic=android.util.AtomicFile(file); val output=atomic.startWrite()
        try { updated.store(output,"Phoenix server properties"); atomic.finishWrite(output) } catch(e:Exception) { atomic.failWrite(output); throw e }
        propertiesCache=null
    }
    private fun activeWorld()=properties().getProperty("level-name","world").trim().ifEmpty { "world" }
    private fun forgetSeed() { actualWorldSeed=null; prefs.edit().remove("actual-world-seed").apply() }
    private fun copyBounded(input:java.io.InputStream,output:java.io.OutputStream,limit:Long,message:String) {
        val buffer=ByteArray(65536); var total=0L
        while(true) { val n=input.read(buffer); if(n<0)break; total+=n; check(total<=limit && !cancel.get()) { message }; output.write(buffer,0,n) }
    }
    /** Small instant actions: a failure becomes the red error line instead of a crash. */
    private fun quick(action:()->Unit) { try { error=null; action() } catch(e:Exception) { error=e.message ?: e.javaClass.simpleName } }

    /** The console lines, copied only when something new arrived. */
    /** Newest first, copied only when something new arrived. */
    fun consoleLines():List<ConsoleEntry> = synchronized(consoleLines) {
        if(consoleCopy.first!=consoleVersion) consoleCopy=consoleVersion to consoleLines.reversed()
        consoleCopy.second
    }
    fun clearConsole() { synchronized(consoleLines) { consoleLines.clear(); consoleVersion++ } }
    /** Short status for the hosting notification; no NetBird or file reads. */
    fun notificationLine():String = error ?: progress ?: (if(!runtimeActive()) networkSetupLine() else null) ?: controller.snapshot().detail()
    fun isBusy()=busy

    // ---- Players
    fun playerCommand(action:String,name:String) {
        require(name.matches(Regex("\\.?[A-Za-z0-9_]{1,32}"))) { "Unexpected player name" }
        require(action in setOf("op","deop","kick","ban","pardon")) { "Unknown player action" }
        consoleInput("/$action $name")
    }
    /** Who may join: the whitelist switch and names, and banned players, read from the server's own files. */
    fun accessLists():AccessLists {
        fun names(file:String)=runCatching {
            val array=org.json.JSONArray(File(server,file).readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }.sortedBy { it.lowercase() }
        }.getOrDefault(emptyList())
        return AccessLists(properties().getProperty("white-list","false").trim()=="true",names("whitelist.json"),names("banned-players.json"),
            controller.snapshot().state()==ServerController.State.ONLINE)
    }
    /**
     * Whitelist and unban changes, sent as server commands so the server's own lists stay authoritative. Bedrock players
     * (Floodgate's "." prefix) go through Floodgate's /fwhitelist, which looks up their Xbox id.
     */
    fun accessCommand(action:String,name:String?) = quick {
        check(controller.snapshot().state()==ServerController.State.ONLINE) { "Start the server to change who can join" }
        when(action) {
            "whitelist-on" -> consoleInput("/whitelist on")
            "whitelist-off" -> consoleInput("/whitelist off")
            "whitelist-add","whitelist-remove","pardon" -> {
                val player=name?.trim().orEmpty()
                require(player.matches(Regex("\\.?[A-Za-z0-9_ ]{1,32}")) && !player.endsWith(" ")) { "Unexpected player name" }
                val verb=if(action=="whitelist-add") "add" else "remove"
                consoleInput(when {
                    action=="pardon" -> "/pardon $player"
                    player.startsWith(".") -> "/fwhitelist $verb ${player.removePrefix(".")}"
                    else -> "/whitelist $verb $player"
                })
            }
            else -> throw IllegalArgumentException("Unknown access action")
        }
    }

    // ---- Spawn preparation (the optional phoenix-spawnprep mod)
    private fun spawnPrepEnabled()=prefs.getBoolean(PREF_SPAWNPREP,true)
    private fun spawnPrepRadius()=prefs.getInt(PREF_SPAWNPREP_RADIUS,SpawnPrepConfig.DEFAULT_RADIUS).let(SpawnPrepConfig::clampRadius)
    /**
     * Makes the server folder match the Settings: the pinned jar in mods/ and `enabled`/`radiusBlocks` in the mod's config
     * (other keys in that file are kept), or the jar gone. Only while the server is stopped; never changes other mods.
     */
    private fun applySpawnPrep():SpawnPrepInstaller.Outcome? = synchronized(spawnPrepLock) {
        val artifact=spawnPrepArtifact ?: return null
        val enabled=spawnPrepEnabled(); val radius=spawnPrepRadius()
        val outcome=SpawnPrepInstaller(artifact) { app.assets.open(artifact.assetPath) }.apply(server,enabled)
        val config=File(server,"config/${SpawnPrepConfig.FILE_NAME}")
        if(enabled) SpawnPrepConfig.write(config,true,radius) else SpawnPrepConfig.writeIfPresent(config,false,radius)
        outcome
    }
    /** Before every launch (start, restart, change): clears the last run and a stale cancel request, and applies the Settings. */
    private fun prepareSpawnPrepForStart() {
        synchronized(spawnPrepLock) { spawnPrep=SpawnPrepStatus(); spawnPrepLastLineAt=0L }
        runCatching { File(server,SPAWNPREP_CANCEL_FILE).delete() }
        if(provider()!=BaselineProvider.FABRIC) { removeSpawnPrepForOtherServer(); return }
        try {
            when(applySpawnPrep()) {
                SpawnPrepInstaller.Outcome.INSTALLED -> append("Spawn preparation is on: the area around spawn (${spawnPrepRadius()} blocks) is built before the server finishes starting.")
                SpawnPrepInstaller.Outcome.REMOVED -> append("Spawn preparation is off: its mod was removed from the server.")
                SpawnPrepInstaller.Outcome.SET_ASIDE -> append("Spawn preparation is off: a changed copy of its mod was set aside.")
                else -> Unit
            }
        } catch(e:Exception) { append("Spawn preparation is not set up (${e.message ?: e.javaClass.simpleName}); the server starts without it.") }
    }
    /**
     * Vanilla (or any server that is not Fabric) cannot load the mod, so Phoenix takes back the copy it installed (only an
     * identical copy; a changed one is set aside). The setting is kept, and choosing Fabric again installs it again.
     */
    private fun removeSpawnPrepForOtherServer() {
        val artifact=spawnPrepArtifact ?: return
        runCatching { synchronized(spawnPrepLock) { SpawnPrepInstaller(artifact) { app.assets.open(artifact.assetPath) }.apply(server,false) } }
            .onSuccess { if(it==SpawnPrepInstaller.Outcome.REMOVED || it==SpawnPrepInstaller.Outcome.SET_ASIDE)
                append("Spawn preparation needs Fabric: its mod was taken out of the server folder for this start.") }
            .onFailure { append("Spawn preparation mod not removed: ${it.message}") }
    }
    /** One of the mod's console lines: updates the live status and tells the start it is making progress. */
    private fun onSpawnPrepLine(line:String) {
        val next=synchronized(spawnPrepLock) { SpawnPrepLog.update(spawnPrep,line)?.also { spawnPrep=it } } ?: return
        spawnPrepLastLineAt=SystemClock.elapsedRealtime()
        controller.noteStartupProgress()
        if(next.state==SpawnPrepState.DONE) runCatching { File(server,SPAWNPREP_CANCEL_FILE).delete() }
        val starting=controller.snapshot().state().let { it==ServerController.State.STARTING || it==ServerController.State.RESTARTING }
        if(!starting) return
        if(next.active) progress=next.headline()
        else if(next.state==SpawnPrepState.DONE) progress=when(next.result) {
            "CANCELLED" -> "Spawn preparation cancelled; finishing startup"
            "PARTIAL" -> "Spawn preparation finished with some chunks missing; finishing startup"
            else -> "The area around spawn is ready; finishing startup"
        }
    }
    /** The Cancel button: asks the mod to stop (an empty file in the server folder). The mod ends with DONE state=CANCELLED. */
    fun spawnPrepCancel() = quick {
        synchronized(spawnPrepLock) {
            val current=spawnPrep
            check(host.isAlive && current.active) { "Spawn preparation is not running" }
            if(current.cancelling) return@quick
            check(File(server,SPAWNPREP_CANCEL_FILE).let { it.exists() || it.createNewFile() }) { "Could not ask spawn preparation to stop" }
            spawnPrep=current.copy(cancelling=true)
        }
        append("Cancelling spawn preparation. The server finishes starting once it has stopped.")
    }
    fun setSpawnPrepEnabled(enabled:Boolean) = quick {
        prefs.edit().putBoolean(PREF_SPAWNPREP,enabled).apply()
        applySpawnPrepSetting()
    }
    fun setSpawnPrepRadius(radius:Int) = quick {
        require(radius in 0..SpawnPrepConfig.MAX_RADIUS) { "Choose 0 to ${SpawnPrepConfig.MAX_RADIUS} blocks" }
        prefs.edit().putInt(PREF_SPAWNPREP_RADIUS,radius).apply()
        applySpawnPrepSetting()
    }
    /** A running server keeps what it loaded: the change waits for the next start. A stopped one is updated right away. */
    private fun applySpawnPrepSetting() {
        if(host.isAlive || controller.isActive) { pendingRestart=true; return }
        if(provider()==BaselineProvider.FABRIC) applySpawnPrep()
    }
    /** The last run, from the world's marker file; read only while the server is stopped, the setting is on and Fabric is chosen. */
    private fun spawnPrepLast():SpawnPrepLast? {
        if(!spawnPrepEnabled() || host.isAlive || provider()!=BaselineProvider.FABRIC) return null
        val container=spawnPrepContainer?.takeIf { prefs.contains("world-card-uuid") } ?: server
        val file=File(File(container,activeWorld()),SpawnPrepMarker.FILE_NAME)
        val key="${file.path}|${file.lastModified()}|${file.length()}"
        spawnPrepMarkerCache?.takeIf { it.first==key }?.let { return it.second }
        return SpawnPrepMarker.read(file).also { spawnPrepMarkerCache=key to it }
    }

    // ---- Chunky
    fun chunkyStart(dimension:String,centerX:Int,centerZ:Int,radius:Int) = quick {
        require(dimension in ChunkImport.dimensions) { "Unknown dimension" }
        require(radius in 100..30000) { "Choose a radius from 100 to 30,000 blocks" }
        require(centerX in -29_999_000..29_999_000 && centerZ in -29_999_000..29_999_000) { "The center is outside the world" }
        check(host.isAlive && "chunky" in modIdsAtStart) { "Start the server with Chunky installed first" }
        userCommandUntil=SystemClock.elapsedRealtime()+6000
        chunky=ChunkyStatus(state="STARTING",world="minecraft:$dimension",percent=0.0)
        for(command in listOf("chunky world minecraft:$dimension","chunky center $centerX $centerZ","chunky radius $radius","chunky start"))
            controller.command(command).exceptionally { error=it.cause?.message ?: it.message; null }
    }
    fun chunkyCommand(action:String) = quick {
        require(action in setOf("pause","continue","cancel","confirm")) { "Unknown Chunky action" }
        check(host.isAlive) { "Start the server first" }
        userCommandUntil=SystemClock.elapsedRealtime()+4000
        if(action=="confirm") chunky=chunky.copy(needsConfirm=false)
        controller.command("chunky $action").exceptionally { error=it.cause?.message ?: it.message; null }
    }

    // ---- Mods (changes load at the next server start)
    fun mods():List<ModInfo> = modManager.list()
    fun setModEnabled(fileName:String,enabled:Boolean) = quick { modManager.setEnabled(fileName,enabled); if(host.isAlive) pendingRestart=true }
    fun removeMod(fileName:String) = quick { modManager.remove(fileName); if(host.isAlive) pendingRestart=true }
    fun addMod(uri:Uri)=launchAction {
        check(provider()==BaselineProvider.FABRIC) { "Mods need Fabric. Switch the server software in Settings first." }
        progress="Checking the mod"
        val name=runCatching { app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        val temp=File(app.cacheDir,"mod-import.jar"); cancel.set(false)
        try {
            app.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Couldn't open that file" }
                temp.outputStream().use { copyBounded(input,it,256L*1048576,"Mods over 256 MB aren't supported") } }
            val installed=modManager.install(temp,name)
            if(host.isAlive) pendingRestart=true
            append("Mod added: $installed. ${if(host.isAlive) "Restart the server to load it." else "It loads when the server starts."}")
        } finally { temp.delete() }
    }

    // ---- Mod packs (saved mod sets; "Use" swaps the enabled mods, with the last good start as the rollback point)
    fun modPacks():ModPacksSnapshot = ModPacksSnapshot(modPacks.list(modManager.optionalEnabled(),pinnedMetas(),serverTarget),packImport,modRollback)
    fun dismissPackNotices() { packImport=null; modRollback=null }
    /** Phoenix's pinned mods as they are installed now, so packs are checked against what will really load beside them. */
    private fun pinnedMetas():List<ModMeta> = modManager.enabledJars().filter { modManager.isRequired(it.name) }.mapNotNull { jar ->
        val stamp="${jar.length()}:${jar.lastModified()}"
        pinnedMetaCache[jar.name]?.takeIf { it.first==stamp }?.second ?: ModChecks.read(jar).also { pinnedMetaCache[jar.name]=stamp to it }
    }
    private fun displayName(uri:Uri):String? = runCatching { app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } }.getOrNull()
    private fun checkSpace(bytes:Long) {
        val free=StatFs(server.absolutePath).availableBytes
        check(free>bytes+128L*1048576) { "Not enough free storage: ${bytes/1048576} MB needed plus room to spare, ${free/1048576} MB free" }
    }
    fun importModPack(uri:Uri)=launchAction {
        check(provider()==BaselineProvider.FABRIC) { "Mod packs need Fabric. Switch the server software in Settings first." }
        progress="Copying the pack"
        val name=displayName(uri)
        val temp=File(app.cacheDir,"pack-import.zip"); cancel.set(false)
        try {
            app.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Couldn't open that file" }
                temp.outputStream().use { copyBounded(input,it,1024L*1048576,"Packs over 1 GB aren't supported, or the import was cancelled") } }
            checkSpace(temp.length())
            progress="Checking the mods in the pack"
            val result=modPacks.importZip(temp,name) { cancel.get() }
            packImport=result
            append("Mod pack \"${result.name}\" added with ${result.added} mods"+if(result.skipped.isEmpty()) "" else "; left out: "+result.skipped.joinToString("; "))
        } finally { temp.delete() }
    }
    fun saveModsAsPack(name:String)=launchAction {
        val jars=modManager.enabledJars().filter { !modManager.isRequired(it.name) }
        check(jars.isNotEmpty()) { "Turn on at least one mod of your own first; Phoenix's pinned mods are added to every pack automatically" }
        checkSpace(jars.sumOf { it.length() })
        progress="Saving the pack"
        modPacks.saveFrom(jars,name); append("Saved the current mods as the pack \"${ModPackStore.checkName(name)}\"")
    }
    fun createModPack(name:String) = quick { modPacks.create(name) }
    fun renameModPack(id:String,name:String) = quick { modPacks.rename(id,name) }
    fun removeModFromPack(id:String,fileName:String) = quick { modPacks.removeJar(id,fileName) }
    fun deleteModPack(id:String) = quick { modPacks.delete(id) }
    fun addModToPack(id:String,uri:Uri)=launchAction {
        progress="Checking the mod"
        val temp=File(app.cacheDir,"pack-mod-import.jar"); cancel.set(false)
        try {
            app.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Couldn't open that file" }
                temp.outputStream().use { copyBounded(input,it,256L*1048576,"Mods over 256 MB aren't supported") } }
            checkSpace(temp.length())
            append("Added ${modPacks.addJar(id,temp,displayName(uri))} to the pack \"${modPacks.name(id)}\"")
        } finally { temp.delete() }
    }
    /**
     * Makes a pack's mods the server's enabled mods. Current mods that no pack holds are first saved as a pack, so nothing
     * is lost; a running server restarts, and if it fails to start the previous mods come back by themselves.
     */
    fun useModPack(id:String)=launchAction {
        check(provider()==BaselineProvider.FABRIC) { "Mod packs need Fabric. Switch the server software in Settings first." }
        val pack=modPacks.list(modManager.optionalEnabled(),pinnedMetas(),serverTarget).firstOrNull { it.id==id } ?: error("That pack is gone")
        pack.issues.firstOrNull { it.blocking }?.let { error("Fix the pack \"${pack.name}\" first: ${it.message}") }
        check(!pack.inUse) { "\"${pack.name}\" is already in use" }
        val current=modManager.enabledJars().filter { !modManager.isRequired(it.name) }
        val jars=modPacks.jars(id)
        checkSpace(jars.sumOf { it.length() }+(if(modPacks.matching(current.associate { it.name to it.length() })==null) current.sumOf { it.length() } else 0L))
        val wasRunning=host.isAlive
        if(current.isNotEmpty() && modPacks.matching(current.associate { it.name to it.length() })==null) {
            progress="Saving your current mods as a pack"
            val stamp=java.text.SimpleDateFormat("MMM d HH:mm",java.util.Locale.US).format(java.util.Date())
            modPacks.saveFrom(current,"Mods before ${pack.name}".take(28)+" $stamp".take(12))
        }
        if(wasRunning) stopForChange("Saving the world before switching mods") else stopped()
        progress="Switching to ${pack.name}"
        modManager.replaceEnabled(jars)
        pendingRestart=false
        append("Mod pack \"${pack.name}\" is in use (${jars.size} mods). ${if(wasRunning) "Restarting the server." else "It loads when the server starts."}")
        if(wasRunning) startAfterChange("Starting with ${pack.name}")
    }

    // ---- Worlds and backups
    fun worlds():WorldsSnapshot = worldManager.snapshot(activeWorld())
    /** Makes [name] the world to play. A running server restarts into it; a new world is generated on that start. */
    fun createWorld(name:String,seed:String)=launchAction {
        val clean=WorldManager.checkName(name)
        require(seed.length<=128 && seed.none { it=='\n' || it=='\r' || it=='\u0000' }) { "Use a seed of up to 128 characters" }
        check(!File(server,clean).exists()) { "A world named $clean already exists" }
        val wasRunning=host.isAlive
        if(wasRunning) stopForChange("Saving the current world") else stopped()
        writeProperties(mapOf("level-name" to clean,"level-seed" to seed.trim())); forgetSeed()
        prefs.edit().putString("world-seed",seed.trim()).apply()
        append("New world \"$clean\" ${if(wasRunning) "is being created now" else "is created when the server starts"}")
        if(wasRunning) startAfterChange("Creating $clean")
    }
    fun loadWorld(name:String)=launchAction {
        val clean=WorldManager.checkName(name)
        check(File(server,"$clean/level.dat").isFile) { "That world has no level.dat" }
        val wasRunning=host.isAlive
        if(wasRunning) stopForChange("Saving the current world") else stopped()
        writeProperties(mapOf("level-name" to clean)); forgetSeed()
        append("Loaded world \"$clean\"")
        if(wasRunning) startAfterChange("Starting $clean")
    }
    fun deleteWorld(name:String)=launchAction {
        val clean=WorldManager.checkName(name)
        check(clean!=activeWorld()) { "Load another world before deleting this one" }
        worldManager.deleteWorld(clean); append("Deleted world \"$clean\"")
    }
    /** Imports a world ZIP into a new folder; the world in use is never touched, so this works while the server runs. */
    fun importWorld(uri:Uri,name:String)=launchAction {
        val clean=WorldManager.checkName(name)
        check(clean!=activeWorld() && !File(server,clean).exists()) { "A world named $clean already exists" }
        val zip=File(app.cacheDir,"world-import.zip"); cancel.set(false)
        try {
            progress="Copying the ZIP"
            app.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Couldn't open that file" }
                zip.outputStream().use { copyBounded(input,it,2L*1024*1024*1024,"World ZIPs over 2 GB aren't supported, or the import was cancelled") } }
            WorldImporter().importZip(zip.toPath(),File(server,clean).toPath(),{ },{cancel.get()}) { p -> progress="Checking the world: ${p.bytesCopied()/1048576} MB" }
            append("Imported world \"$clean\". Tap Load to play it.")
        } finally { zip.delete() }
    }
    fun backupWorld(name:String)=launchAction {
        val clean=WorldManager.checkName(name)
        val live=clean==activeWorld() && host.isAlive
        val job={ worldManager.backup(clean) { bytes -> progress="Backing up $clean: ${bytes/1048576} MB" }; Unit }
        if(live) withSavingPaused(job) else job()
        append("Backup saved for \"$clean\"")
    }
    fun exportWorld(name:String,uri:Uri)=launchAction {
        val clean=WorldManager.checkName(name)
        val output=app.contentResolver.openOutputStream(uri) ?: error("Couldn't open the export destination")
        output.use { out ->
            val zip={ worldManager.zipWorld(clean,out) { bytes -> progress="Exporting $clean: ${bytes/1048576} MB" } }
            if(clean==activeWorld() && host.isAlive) withSavingPaused(zip) else zip()
        }
        append("World \"$clean\" exported")
    }
    /** Copies the raw server log file as written (each line with its epoch-ms prefix), so nothing the console hid is lost. */
    fun exportServerLog(uri:Uri)=launchAction {
        flushServerLog()
        val source=File(evidence,"server.log")
        check(source.isFile) { "No server log has been written yet" }
        val output=app.contentResolver.openOutputStream(uri) ?: error("Couldn't open the export destination")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
        append("Full server log exported")
    }
    /** Runs [action] with world saving paused and flushed, so a running server's files are consistent. */
    private fun withSavingPaused(action:()->Unit) {
        check(controller.snapshot().state()==ServerController.State.ONLINE) { "Wait for the server to finish starting or stopping" }
        progress="Saving the world"
        val latch=CountDownLatch(1); saveFlushed=latch
        try {
            controller.command("save-off").get(30,TimeUnit.SECONDS)
            controller.command("save-all flush").get(30,TimeUnit.SECONDS)
            check(latch.await(90,TimeUnit.SECONDS) && host.isAlive) { "The server didn't finish saving, so no backup was made" }
            action()
        } finally {
            saveFlushed=null
            if(host.isAlive) runCatching { controller.command("save-on").get(30,TimeUnit.SECONDS) }
        }
    }
    /** Replaces a world with a backup. The replaced copy is kept by the importer; a running server restarts. */
    fun restoreBackup(fileName:String)=launchAction {
        val backup=worldManager.backupFile(fileName)
        val world=WorldManager.parseBackupName(fileName)!!.first
        val restart=world==activeWorld() && host.isAlive
        if(restart) stopForChange("Saving before the restore")
        WorldImporter().importZip(backup.toPath(),File(server,world).toPath(),{ check(!(world==activeWorld() && host.isAlive)) { "Stop the server first" } },{false}) { p ->
            progress="Restoring $world: ${p.bytesCopied()/1048576} MB" }
        append("Restored \"$world\" from the backup")
        if(restart) startAfterChange("Starting $world")
    }
    fun exportBackup(fileName:String,uri:Uri)=launchAction {
        val backup=worldManager.backupFile(fileName)
        val output=app.contentResolver.openOutputStream(uri) ?: error("Couldn't open the export destination")
        output.use { target -> backup.inputStream().use { it.copyTo(target,65536) } }; append("Backup exported")
    }
    fun deleteBackup(fileName:String)=launchAction { worldManager.deleteBackup(fileName); append("Backup deleted") }
    /**
     * Adds chunks generated on a computer for one dimension of the world in use. The world is backed up
     * first, and a running server is stopped for the copy and started again afterwards.
     */
    fun importChunks(uri:Uri,dimension:String)=launchAction {
        require(dimension in ChunkImport.dimensions) { "Unknown dimension" }
        val world=activeWorld()
        val zip=File(app.cacheDir,"chunks-import.zip"); cancel.set(false)
        try {
            progress="Copying the ZIP"
            app.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Couldn't open that file" }
                zip.outputStream().use { copyBounded(input,it,8L*1024*1024*1024,"ZIPs over 8 GB aren't supported, or the import was cancelled") } }
            java.util.zip.ZipFile(zip).use { archive -> ChunkImport.select(archive.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList(),dimension) }
            val wasRunning=host.isAlive
            if(wasRunning) stopForChange("Saving the world before importing") else stopped()
            if(File(server,"$world/level.dat").isFile) worldManager.backup(world) { bytes -> progress="Backing up $world first: ${bytes/1048576} MB" }
            val count=worldManager.importChunks(zip,world,dimension,StatFs(worldContainerDir().absolutePath).availableBytes) { done,total -> progress="Importing ${ChunkImport.label(dimension)} chunks: $done / $total files" }
            append("Imported $count ${ChunkImport.label(dimension)} region files into \"$world\". A backup from before the import is in Worlds › Restore.")
            if(wasRunning) startAfterChange("Starting $world")
        } finally { zip.delete() }
    }

    // ---- Server settings (applied at the next start)
    /** How many automatic backups to keep; 0 turns them off. */
    fun setAutoBackups(keep:Int) = quick { require(keep in 0..10) { "Keep 0 to 10 automatic backups" }; prefs.edit().putInt(AutoBackups.PREF,keep).apply() }
    /** 0 = Dynamic; otherwise one of the fixed sizes this phone offers. Applies at the next start. */
    fun setServerMemory(mb:Int) = quick {
        val total=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
        require(ServerMemory.allowed(mb,total)) { "Choose ${ServerMemory.MIN_CUSTOM} MB to ${ServerMemory.label(ServerMemory.maxCustomMb(total))} on this phone" }
        prefs.edit().putInt("server-memory-mb",mb).apply()
        if(host.isAlive) pendingRestart=true
    }
    fun updateSetting(key:String,value:String)=launchAction {
        val clean=when(key) {
            "max-players" -> value.toIntOrNull()?.takeIf { it in 1..100 }?.toString()
            "gamemode" -> value.takeIf { it in setOf("survival","creative","adventure","spectator") }
            "difficulty" -> value.takeIf { it in setOf("peaceful","easy","normal","hard") }
            "pvp" -> value.takeIf { it=="true" || it=="false" }
            "view-distance","simulation-distance" -> value.toIntOrNull()?.takeIf { it in PhoneTuning.RANGE }?.toString()
            "spawn-protection" -> value.toIntOrNull()?.takeIf { it in 0..256 }?.toString()
            "region-file-compression" -> value.takeIf { it=="deflate" || it=="lz4" }
            else -> null
        } ?: throw IllegalArgumentException("That setting value isn't supported")
        writeProperties(mapOf(key to clean))
        // The owner's own choice is never replaced by Phoenix's one-time "off" default.
        if(key=="spawn-protection") prefs.edit().putBoolean("spawn-protection-off-v1",true).apply()
        if(host.isAlive) pendingRestart=true
    }
}
