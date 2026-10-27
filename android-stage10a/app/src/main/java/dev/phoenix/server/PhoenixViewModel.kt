package dev.phoenix.server
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** Screen state. Each stream polls the backend only while a screen shows it, and never on the main thread. */
class PhoenixViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = PhoenixBackend.get(application)
    private val _screen = MutableStateFlow(Screen.HOME); val screen = _screen.asStateFlow()
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private fun <T : Any> poll(periodMs: Long, read: () -> T): StateFlow<T?> =
        merge(flow { while (true) { emit(Unit); delay(periodMs) } }, refresh)
            .mapNotNull { runCatching(read).getOrNull() }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)
    /** Null until the first read, so the first frame never shows a made-up state. */
    val dashboard: StateFlow<Dashboard?> = poll(1000, backend::dashboard)
    val console: StateFlow<List<ConsoleEntry>?> = poll(500, backend::consoleLines)
    val mods: StateFlow<List<ModInfo>?> = poll(4000, backend::mods)
    val worlds: StateFlow<WorldsSnapshot?> = poll(8000, backend::worlds)
    /** Phone and microSD space and where worlds live; StatFs runs on the IO poll, never the main thread. */
    val storage: StateFlow<StorageSummary?> = poll(15000, backend::storageSummary)
    fun navigate(screen: Screen) { _screen.value = screen }
    /** Re-reads mods and worlds now, for example after an action finished. */
    fun refreshLists() { refresh.tryEmit(Unit) }
    private fun io(action: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { action(); refresh.tryEmit(Unit) } }
    private fun service(action: String) { ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), HostingService::class.java).setAction(action)) }
    /** Keeps Phoenix running (not frozen by Android) while a sign-in or route change continues in the background. */
    private fun keepAliveForNetwork() = runCatching { service("NETWORK") }
    init { if (backend.networkSetupActive()) keepAliveForNetwork() }
    fun install(accepted: Boolean, name: String, seed: String) = backend.install(accepted, name, seed)
    fun cancelInstall() = backend.cancelInstall()
    fun probe() = service("PROBE")
    fun lifecycle(op: LifecycleOperation) = service(op.name)
    fun sendConsoleInput(text: String) = backend.consoleInput(text)
    fun clearConsole() = io { backend.clearConsole() }
    fun clearError() = backend.clearError()
    fun forceStop() = service("FORCE_STOP")
    fun measureTicks() = backend.measureTicks()
    fun requestAutoNetworkSetup() = backend.requestAutoNetworkSetup()
    fun provisionPorts() = service("PROVISION_NETBIRD")
    fun retryTunnelSetup() = service("RECONNECT_NETBIRD")
    fun checkMinecraftHostname(hostname: String) = backend.checkMinecraftHostname(hostname)
    fun reserveMinecraftHostname(hostname: String) = backend.reserveMinecraftHostname(hostname)
    fun validateMinecraftDns() = backend.validateMinecraftDns()
    fun useBrowserHostname(hostname: String) = backend.useBrowserHostname(hostname)
    fun refreshMinecraftHow(throttled: Boolean = false) = backend.refreshMinecraftHow(throttled)
    // The one public route friends use (Playit.gg, NetBird, or the owner's regional proxy), and the optional custom name
    fun selectRoute(route: PublicRoute) { keepAliveForNetwork(); io { backend.selectRoute(route) } }
    fun turnOffPublicAccess() { keepAliveForNetwork(); io { backend.turnOffPublicAccess() } }
    /** Starts Playit's browser approval off the main thread; [open] gets the page to show, or null when none is needed. */
    fun connectPlayit(andUse: Boolean, open: (String?) -> Unit) {
        keepAliveForNetwork()
        viewModelScope.launch(Dispatchers.IO) { val url = backend.connectPlayit(andUse); withContext(Dispatchers.Main) { open(url) } }
    }
    fun cancelPlayitSignIn() = io { backend.cancelPlayitSignIn() }
    fun forgetPlayit() = io { backend.forgetPlayit() }
    /** [then] gets true when NetBird became the route now, false when its sign-in has to run first. */
    fun requestNetBird(then: (Boolean) -> Unit) {
        keepAliveForNetwork()
        viewModelScope.launch(Dispatchers.IO) { val switched = backend.requestNetBird(); withContext(Dispatchers.Main) { then(switched) } }
    }
    fun setCustomNameEnabled(enabled: Boolean) = io { backend.setCustomNameEnabled(enabled) }
    fun testCustomName() = io { backend.testCustomName() }
    /** Playit + NetBird + custom name: the name in Java's server list through Phoenix's front door. */
    fun setNameListing(enabled: Boolean) = io { backend.setNameListing(enabled) }
    /** The add-on's NetBird ports; [then] gets true when NetBird is already connected and signed in, so they're made now. */
    fun requestFrontDoorSetup(then: (Boolean) -> Unit) {
        keepAliveForNetwork()
        viewModelScope.launch(Dispatchers.IO) { val ready = backend.requestFrontDoorSetup(); withContext(Dispatchers.Main) { then(ready) } }
    }
    /** Points the saved name at the selected route (Minecraft.How records, each confirmed by readback). */
    fun linkCustomName(hostname: String) = backend.saveMinecraftHostname(hostname)
    // The owner's regional proxy (NetBird Bring Your Own Proxy on Oracle Cloud): experimental
    fun setRegionalEnabled(enabled: Boolean) = io { backend.setRegionalEnabled(enabled) }
    fun chooseRegionalArea(area: String) = io { backend.chooseRegionalArea(area) }
    fun startRegionalSetup() = backend.startRegionalSetup()
    fun cancelRegionalSetup() = backend.cancelRegionalSetup()
    /** The one-time pairing code, read only when the owner copies it into Oracle's form. */
    fun regionalPairingCode(): String? = backend.regionalPairingCode()
    fun checkRegionalProxy() = backend.checkRegionalProxy()
    fun switchPlayersToRegional(regional: Boolean) = backend.switchPlayersToRegional(regional)
    fun removeRegionalProxy() = backend.removeRegionalProxy()
    fun export(uri: Uri) = backend.export(uri)
    fun selectProvider(vanilla: Boolean) = backend.selectProvider(vanilla)
    fun setServerMemory(mb: Int) = backend.setServerMemory(mb)
    fun renameServer(name: String) {
        ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), HostingService::class.java).setAction("RENAME").putExtra("server-name", name))
    }
    fun updateSetting(key: String, value: String) = backend.updateSetting(key, value)
    fun playerCommand(action: String, name: String) = backend.playerCommand(action, name)
    /** Polled only while the Access dialog is open. */
    val access: StateFlow<AccessLists?> = poll(3000, backend::accessLists)
    fun accessCommand(action: String, name: String? = null) = io { backend.accessCommand(action, name) }
    // Mods
    fun setModEnabled(fileName: String, enabled: Boolean) = io { backend.setModEnabled(fileName, enabled) }
    fun removeMod(fileName: String) = io { backend.removeMod(fileName) }
    fun addMod(uri: Uri) = backend.addMod(uri)
    // Mod packs
    val packs: StateFlow<ModPacksSnapshot?> = poll(4000, backend::modPacks)
    fun importModPack(uri: Uri) = backend.importModPack(uri)
    fun saveModsAsPack(name: String) = backend.saveModsAsPack(name)
    fun createModPack(name: String) = io { backend.createModPack(name) }
    fun renameModPack(id: String, name: String) = io { backend.renameModPack(id, name) }
    fun addModToPack(id: String, uri: Uri) = backend.addModToPack(id, uri)
    fun removeModFromPack(id: String, fileName: String) = io { backend.removeModFromPack(id, fileName) }
    fun deleteModPack(id: String) = io { backend.deleteModPack(id) }
    fun useModPack(id: String) = backend.useModPack(id)
    fun dismissPackNotices() = io { backend.dismissPackNotices() }
    // Worlds, backups and Chunky
    fun createWorld(name: String, seed: String) = backend.createWorld(name, seed)
    fun loadWorld(name: String) = backend.loadWorld(name)
    fun deleteWorld(name: String) = backend.deleteWorld(name)
    fun importWorld(uri: Uri, name: String) = backend.importWorld(uri, name)
    fun backupWorld(name: String) = backend.backupWorld(name)
    fun exportWorld(name: String, uri: Uri) = backend.exportWorld(name, uri)
    fun exportServerLog(uri: Uri) = backend.exportServerLog(uri)
    fun restoreBackup(fileName: String) = backend.restoreBackup(fileName)
    fun restoreLastBackup() = backend.restoreLastBackup()
    fun setAutoBackups(keep: Int) = backend.setAutoBackups(keep)
    fun setThermalEaseOff(enabled: Boolean) = backend.setThermalEaseOff(enabled)
    fun setFasterStarts(enabled: Boolean) = io { backend.setFasterStarts(enabled) }
    fun setPhoeniumAcceleration(enabled: Boolean) = backend.setPhoeniumAcceleration(enabled)
    fun setPhoeniumWorkers(workers: Int) = backend.setPhoeniumWorkers(workers)
    fun dismissWorldErrors() = backend.dismissWorldErrors()
    fun dismissKillNotice() = backend.dismissKillNotice()
    fun dismissServerCrash() = backend.dismissServerCrash()
    fun exportBackup(fileName: String, uri: Uri) = backend.exportBackup(fileName, uri)
    fun deleteBackup(fileName: String) = backend.deleteBackup(fileName)
    fun importChunks(uri: Uri, dimension: String) = backend.importChunks(uri, dimension)
    fun chunkyStart(dimension: String, centerX: Int, centerZ: Int, radius: Int) = backend.chunkyStart(dimension, centerX, centerZ, radius)
    fun chunkyCommand(action: String) = backend.chunkyCommand(action)
    // Spawn preparation
    fun setSpawnPrepEnabled(enabled: Boolean) = io { backend.setSpawnPrepEnabled(enabled) }
    fun setSpawnPrepRadius(radius: Int) = io { backend.setSpawnPrepRadius(radius) }
    fun spawnPrepCancel() = io { backend.spawnPrepCancel() }
}
