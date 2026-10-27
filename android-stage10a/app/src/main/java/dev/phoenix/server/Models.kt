package dev.phoenix.server
import org.phoenix.core.ServerController
/** The bottom-bar tabs, left to right. */
enum class Screen { HOME, MODS, WORLD, PLAYERS, SETTINGS }
enum class LifecycleOperation { START, STOP, RESTART }
/** An online player. [onlineForMs] is null when Phoenix did not see the join (for example before a restart of the app). */
data class PlayerInfo(val name: String, val onlineForMs: Long?, val op: Boolean, val uuid: String? = null) {
    /** Floodgate marks Bedrock players with a leading "." in their Java name. */
    val bedrock get() = name.startsWith(".")
    val displayName get() = name.trimStart('.')
}
/** Chunky pre-generation as read from Chunky's own console messages. */
data class ChunkyStatus(
    val state: String = "IDLE",
    val world: String? = null,
    val percent: Double? = null,
    val processed: Long? = null,
    val rate: Double? = null,
    val eta: String? = null,
    val lastMessage: String? = null,
    val needsConfirm: Boolean = false,
)
/** A mod .jar in the server's mods folder, described by its fabric.mod.json. */
data class ModInfo(
    val fileName: String,
    val id: String?,
    val name: String,
    val version: String?,
    val description: String?,
    val environment: String?,
    val enabled: Boolean,
    /** Installed and checked by Phoenix (Bedrock crossplay and voice chat); it cannot be turned off or removed. */
    val required: Boolean,
    val sizeBytes: Long,
)
/** Who may join: whether the whitelist is on, its names, and banned players. Changes need the server online. */
data class AccessLists(val whitelistOn: Boolean, val whitelist: List<String>, val banned: List<String>, val online: Boolean)
data class WorldInfo(val name: String, val sizeBytes: Long, val lastPlayedMs: Long?, val active: Boolean, val created: Boolean)
data class BackupInfo(val fileName: String, val world: String, val createdMs: Long, val sizeBytes: Long)
data class WorldsSnapshot(val worlds: List<WorldInfo> = emptyList(), val backups: List<BackupInfo> = emptyList())

/** Phone and microSD space and where worlds live. [cardLabel] is null when no usable card is present. */
data class StorageSummary(
    val phoneTotalBytes: Long = 0, val phoneFreeBytes: Long = 0,
    val cardLabel: String? = null, val cardTotalBytes: Long = 0, val cardFreeBytes: Long = 0, val cardState: String? = null,
    val worldsOnCard: Boolean = false, val worldsWhere: String = "on phone storage", val warning: String? = null,
)
data class Dashboard(
    val phase: ServerController.State = ServerController.State.NOT_INSTALLED,
    val detail: String = "Install the pinned Minecraft 26.2 baseline", val installed: Boolean = false,
    val busy: Boolean = false, val uptimeMs: Long = 0,
    /** Whether the server's Java process is running, in any phase: an ERROR can leave it running, and then Stop applies. */
    val processAlive: Boolean = false,
    /** Set after Phoenix reconnected to a server that outlived the app: the console input is then off, with this explanation. */
    val consoleNote: String? = null,
    val runtimeResult: String = "Java 25 has not been tested on this device",
    val localAddress: String? = null, val playerCount: Int? = null, val players: List<PlayerInfo> = emptyList(),
    val mspt: Double? = null, val progress: String? = null,
    val publicAddress:String?=null,
    val tps:Double?=null,
    val providerDisplayName:String="Fabric 0.19.5",
    val serverName:String="Phoenix Server",
    val publicTransport:String="NetBird Cloud",
    val networkState:String="Not linked",
    val dnsState:String="Set up your hostname through Minecraft.How",
    val networkPhase:String="NOT_CONFIGURED",
    val networkBusy:Boolean=false,
    val networkCanProvision:Boolean=false,
    val networkCanReconnect:Boolean=false,
    val networkHasIdentity:Boolean=false,
    val netBirdAccountDetail:String="",
    val netBirdPeerIp:String?=null,
    val netBirdLink:String="Not connected",
    val netBirdAccount:String="Not signed in",
    val netBirdServices:String="Not created yet",
    /** Non-null exactly while a NetBird step is in progress; names what the progress bar waits for. */
    val networkActivity:String?=null,
    val autoSetupState:String="IDLE",
    val bedrockAddress:String?=null,
    val minecraftHostname:String?=null,
    val hostnameCheckName:String?=null,
    val hostnameCheckDetail:String="Choose a hostname. You can check and save it while the server is stopped.",
    val bedrockState:String="Bedrock listener has not been checked",
    val minecraftHowLinked:Boolean=false,
    val minecraftHowState:String="Sign in once to set up your public hostname",
    val minecraftHowZones:List<String> = emptyList(),
    /** Phoenix's private profile holds a Minecraft.How cookie (verified or not), so Sign out is meaningful. */
    val minecraftHowHasSession:Boolean=false,
    val minecraftHowChecking:Boolean=false,
    /** The current failure, if any: a failed action or the server's own error state. Shown in red. */
    val errorMessage:String?=null,
    val netBirdSignedIn:Boolean=false,
    val netBirdLinkUp:Boolean=false,
    val netBirdAuthorizing:Boolean=false,
    /** The embedded NetBird client is running (connected or still connecting). */
    val netBirdLive:Boolean=false,
    /** This phone has a NetBird device identity, so it can sign in again without a new peer. */
    val netBirdEnrolled:Boolean=false,
    // How friends connect: "easy" (NetBird's hosted EU proxy) or "regional" (the owner's Oracle proxy); null until chosen.
    val networkMode:String?=null,
    /** Where most players are ("us", "ca" or "eu"), chosen for Regional Optimized; guidance only. */
    val regionalArea:String?=null,
    // The owner's regional proxy (NetBird Bring Your Own Proxy on a free Oracle VM).
    val regionalProxyStage:String="NOT_SET_UP",
    val regionalProxyDetail:String="Not set up",
    val regionalProxyIp:String?=null,
    /** The Oracle region and VM shape the proxy reported when it paired. */
    val regionalProxyRegion:String?=null,
    val regionalProxyShape:String?=null,
    /** When the open pairing code stops working; 0 when no pairing is open. */
    val regionalPairingExpiresAt:Long=0L,
    val regionalRouteReport:String?=null,
    val regionalPath:String?=null,
    /** Players are sent through the regional proxy instead of NetBird's shared EU proxy. */
    val playersOnRegional:Boolean=false,
    /** Approximate bytes through the regional proxy this month, or null when not measured. */
    val regionalTrafficBytes:Long?=null,
    /** Simple Voice Chat's UDP port, or 0 when it is not installed. */
    val voiceChatPort:Int=0,
    /** Public UDP port of the voice service on NetBird's EU proxy, or 0. */
    val euVoicePort:Int=0,
    // Device and server measurements, read by the backend's sampler.
    val heapUsedBytes:Long?=null,
    val heapMaxBytes:Long?=null,
    val serverRssBytes:Long?=null,
    val phoneMemAvailableBytes:Long?=null,
    val phoneMemTotalBytes:Long?=null,
    val storageFreeBytes:Long?=null,
    val storageTotalBytes:Long?=null,
    val batteryPercent:Int?=null,
    val charging:Boolean=false,
    val batteryTempC:Double?=null,
    val thermalStatus:Int=0,
    // server.properties
    val maxPlayers:Int=20,
    val gamemode:String="survival",
    val difficulty:String="easy",
    val pvp:Boolean=true,
    val viewDistance:Int=10,
    val simulationDistance:Int=10,
    val levelName:String="world",
    val worldSeed:String?=null,
    /** Mods or settings changed while the server was running; they apply after a restart. */
    val pendingRestart:Boolean=false,
    val chunky:ChunkyStatus=ChunkyStatus(),
    /** Chunky was enabled when the running server started, so its commands work now. */
    val chunkyLoaded:Boolean=false,
    /** The spawn preparation mod's live run while the server starts; idle when the mod is off, absent or silent. */
    val spawnPrep:SpawnPrepStatus=SpawnPrepStatus(),
    /** Settings: prepare the area around spawn before the server finishes starting (on by default). */
    val spawnPrepEnabled:Boolean=true,
    /** Settings: how far from spawn, in blocks (0 to 4096). */
    val spawnPrepRadius:Int=SpawnPrepConfig.DEFAULT_RADIUS,
    /** The last run from the world's marker file; read only while the server is stopped and the setting is on. */
    val spawnPrepLast:SpawnPrepLast?=null,
    /** The server's Java memory: 0 picks it from free memory at each start, otherwise a fixed size in MB. */
    val memorySettingMb:Int=0,
    /** Every public route and the optional custom name, from the backend's single source of truth. */
    val net:NetworkView=NetworkView(),
    /** The owner turned on the experimental Regional Optimized setup, and once confirmed its warning. */
    val regionalEnabled:Boolean=false,
    val regionalWarningAcknowledged:Boolean=false,
    /** A route the owner turned on that still waits for its account setup. */
    val requestedRoute:PublicRoute?=null,
    /** The open Playit approval page, to reopen it; null when no approval is pending. */
    val playitClaimUrl:String?=null,
    /** Playit's account status for this agent: "guest", "email_not_verified" or "verified". */
    val playitAccount:String?=null,
    /** A custom name's Java SRV leads to Playit's raw TCP tunnel, so the name also answers in Java's server list. */
    val playitJavaNamesOpen:Boolean=false,
    /** server.properties spawn-protection: blocks around world spawn only operators may change. */
    val spawnProtection:Int=16,
    /** Bedrock or voice tunnels Playit couldn't set up, with why. */
    val playitProblems:List<String> = emptyList(),
    /** The last route change failed and the previous route was kept; why. */
    val routeError:String?=null,
    /** What a custom name's records must say for the selected route, for owners who edit them in a browser. */
    val nameRecords:NameRecords?=null,
    /** This phone's Wi-Fi (or Ethernet) IPv4 address whether or not the server runs; null without Wi-Fi. */
    val lanIp:String?=null,
    /** Same Wi-Fi Java address whether or not the server runs. */
    val lanJavaAddress:String?=null,
    /** Same Wi-Fi Bedrock address (LAN IP and Geyser's port); null without Wi-Fi. */
    val localBedrockAddress:String?=null,
    /** The last regular health check found the server not answering on this phone (two misses in a row). */
    val serverNotResponding:Boolean=false,
    /** Playit + NetBird + custom name: the name in Java's server list; null unless Playit is the route and a name is set. */
    val nameListing:NameListing?=null,
    /** The server stopped after reporting damaged chunks or region files; Home offers the last backup. */
    val worldErrors:WorldErrors?=null,
    /** The server's log reported a crash: what crashed, its report, and whether the final save ran. */
    val serverCrash:ServerCrash?=null,
    /** Android killed the server for the first time; Home explains how to prevent it until dismissed. */
    val systemKillNotice:Boolean=false,
    /** Lower the live simulation distance one step while the phone runs hot (on by default). */
    val thermalEaseOff:Boolean=true,
    /** The live simulation distance while Phoenix has eased off for heat; null when it hasn't. */
    val easedSimulation:Int?=null,
    /** Java's AOT cache for the server (off by default; on only when the owner turns it on). */
    val fasterStarts:Boolean=AotCache.DEFAULT_ENABLED,
    /** Phoenium is installed, on, and has its tile engine (Fabric only); its controls show only then. */
    val phoeniumEngineAvailable:Boolean=false,
    /** Phoenium's tile engine for the next start; false is Standard, Phoenium's own default mode. */
    val phoeniumAcceleration:Boolean=PhoeniumTuning.DEFAULT_ACCELERATION,
    val phoeniumWorkers:Int=PhoeniumTuning.DEFAULT_WORKERS,
    val phoeniumMaxWorkers:Int=PhoeniumTuning.MIN_WORKERS,
    /** server.properties region-file-compression: "deflate" (Minecraft's default) or "lz4". */
    val regionCompression:String="deflate",
    /** Automatic backups kept before starts; 0 when they are off. */
    val autoBackupsKeep:Int=AutoBackups.DEFAULT_KEEP,
)

/** World damage found in the log of a server that stopped unexpectedly: how many errors, the first, and the newest backup. */
data class WorldErrors(val world:String, val count:Int, val first:String, val backup:BackupInfo?)

/**
 * Whether a custom name shows in Java's server list on Playit Free, through Phoenix's front door on NetBird.
 * [active]: the name's Java record leads to the front door now. [netBirdReady]: NetBird's free ports exist.
 */
data class NameListing(val enabled:Boolean, val active:Boolean, val netBirdReady:Boolean, val detail:String?, val needsSignIn:Boolean=false)

/** The DNS records a custom name needs: the name's own A/CNAME target, and its Java SRV target and port. */
data class NameRecords(val apex:String, val srvTarget:String?, val javaPort:Int, val bedrockPort:Int)

/** World settings that keep a phone server from falling behind. */
object PhoneTuning {
    const val VIEW = 8
    const val SIMULATION = 6
    val RANGE = 3..12
    /** What to change on a server still using vanilla's PC defaults; distances someone already changed are left alone. */
    fun changes(view: String?, simulation: String?, syncWrites: String?): Map<String, String> = buildMap {
        if (view?.trim() == null || view.trim() == "10") put("view-distance", "$VIEW")
        if (simulation?.trim() == null || simulation.trim() == "10") put("simulation-distance", "$SIMULATION")
        if (syncWrites?.trim() != "false") put("sync-chunk-writes", "false")
    }
}

/** How much memory the Minecraft server's Java gets. */
object ServerMemory {
    private val presets = listOf(512, 768, 1024, 1536, 2048)
    const val MIN_CUSTOM = 256
    /** Preset sizes offered on a phone with this much RAM: never more than 60% of it, so Android and the app keep room. */
    fun choices(totalBytes: Long?): List<Int> = presets.filter { totalBytes == null || it.toLong() * 1_048_576 <= totalBytes * 6 / 10 }
    /** The largest custom size: 75% of the phone's RAM (about 12 GB on a 16 GB phone). */
    fun maxCustomMb(totalBytes: Long?): Int = if (totalBytes == null) 2048 else (totalBytes * 3 / 4 / 1_048_576).toInt().coerceAtLeast(MIN_CUSTOM)
    fun allowed(mb: Int, totalBytes: Long?): Boolean = mb == 0 || mb in choices(totalBytes) || mb in MIN_CUSTOM..maxCustomMb(totalBytes)
    /** What Dynamic picks at start: 45% of the memory free right then, between 256 MB and 1.5 GB. */
    fun dynamicMb(availableBytes: Long): Long = (availableBytes / 1_048_576 * 45 / 100).coerceIn(256, 1536)
    fun label(mb: Int): String = when {
        mb <= 0 -> "Dynamic"
        mb % 1024 == 0 -> "${mb / 1024} GB"
        mb > 1024 && mb % 256 == 0 -> "${(mb / 1024.0).toString().trimEnd('0')} GB"
        else -> "$mb MB"
    }
}
fun elapsed(ms: Long): String = "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m ${(ms % 60_000) / 1000}s"
