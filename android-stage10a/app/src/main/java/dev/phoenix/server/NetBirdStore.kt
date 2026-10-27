package dev.phoenix.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.phoenix.core.network.netbird.NetBirdProvisioningCoordinator
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/** Credentials and allocation journal share one encrypted atomic, backup-excluded document. */
class NetBirdStore(context: Context, namespace: String = "baseline") : NetBirdProvisioningCoordinator.JournalStore {
    init { require(namespace.matches(NAMESPACE)) }
    private val dir = File(context.noBackupFilesDir, "netbird-$namespace").apply { mkdirs() }
    private val alias = "phoenix.netbird.$namespace.v1"
    private val file = AtomicFile(File(dir, "state.enc"))
    private val lockFile = File(dir, "state.lock")
    private val lock = locks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
    fun <T> locked(action: () -> T): T = lock.withLock {
        val held = heldFileLocks.get()
        if (lockFile.absolutePath in held) return@withLock action()
        RandomAccessFile(lockFile, "rw").use { random -> random.channel.lock().use {
            held.add(lockFile.absolutePath)
            try { action() } finally {
                held.remove(lockFile.absolutePath)
                if (held.isEmpty()) heldFileLocks.remove()
            }
        } }
    }
    override fun load(): NetBirdProvisioningCoordinator.Snapshot = locked { journalOf(read()) }
    private fun journalOf(document: JSONObject) =
        if (!document.has("journal")) NetBirdProvisioningCoordinator.Snapshot.empty()
        else NetBirdProvisioningCoordinator.Snapshot.decode(document.getString("journal"))

    /**
     * Non-secret values computed from the decrypted document and reused until the file changes. The
     * dashboard reads NetBird status every second, and decrypting through Android Keystore each time is slow.
     */
    fun <T : Any> derived(name: String, derive: (NetBirdProvisioningCoordinator.Snapshot, JSONObject) -> T): T {
        val key = "${lockFile.absolutePath}#$name"
        val stamp = stamp()
        cached<T>(key, stamp)?.let { return it }
        return compute(key, stamp, derive)
    }

    /** Like [derived], but never waits for the store: while another thread holds it, the last value (or null) is returned. */
    fun <T : Any> peekDerived(name: String, derive: (NetBirdProvisioningCoordinator.Snapshot, JSONObject) -> T): T? {
        val key = "${lockFile.absolutePath}#$name"
        val stamp = stamp()
        cached<T>(key, stamp)?.let { return it }
        if (!lock.tryLock()) {
            @Suppress("UNCHECKED_CAST") return derivedCache[key]?.second as T?
        }
        return try { compute(key, stamp, derive) } finally { lock.unlock() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, stamp: String): T? = derivedCache[key]?.takeIf { it.first == stamp }?.second as T?

    private fun <T : Any> compute(key: String, stamp: String, derive: (NetBirdProvisioningCoordinator.Snapshot, JSONObject) -> T): T =
        locked { val document = read(); derive(journalOf(document), document.optJSONObject("configuration") ?: JSONObject()) }
            .also { derivedCache[key] = stamp to it }

    /** Changes whenever the document is replaced: this process's write count plus the file's inode, size and change time. */
    private fun stamp(): String {
        val generation = generations[lockFile.absolutePath] ?: 0L
        val stat = runCatching { android.system.Os.stat(file.baseFile.path) }.getOrNull() ?: return "$generation:none"
        return "$generation:${stat.st_ino}:${stat.st_size}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}"
    }
    override fun compareAndSet(expectedRevision: Long, replacement: NetBirdProvisioningCoordinator.Snapshot): Boolean = locked {
        val document = read()
        val current = if (document.has("journal")) NetBirdProvisioningCoordinator.Snapshot.decode(document.getString("journal")) else NetBirdProvisioningCoordinator.Snapshot.empty()
        if (current.revision() != expectedRevision || replacement.revision() != expectedRevision + 1) return@locked false
        write(document.put("journal", replacement.encode()))
        true
    }
    fun configuration(): JSONObject = locked { read().optJSONObject("configuration") ?: JSONObject() }
    /** Status-display read that never waits; null while another thread holds the store (e.g. provisioning). */
    fun peekConfiguration(): JSONObject? {
        if (!lock.tryLock()) return null
        return try { configuration() } finally { lock.unlock() }
    }
    fun saveConfiguration(configuration: JSONObject) = locked {
        val current = read()
        val previous = current.optJSONObject("configuration")
        // An account or peer switch may not silently reuse or abandon allocations.
        if (current.has("journal") && previous != null) {
            check(listOf("peerId", "tcpDomain", "udpDomain", "cluster").all { previous.optString(it) == configuration.optString(it) }) {
                "Existing services belong to the saved peer and domains; reconcile them before changing configuration"
            }
        }
        write(current.put("configuration", configuration))
    }
    private fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        try {
            val packed = file.readFully()
            check(packed.size in 29..(2 * 1024 * 1024))
            val existing = key(false) ?: error("Missing encryption key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            val plain = cipher.doFinal(packed, 12, packed.size - 12)
            try { return JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0); packed.fill(0) }
        } catch (_: Exception) { error("NetBird encrypted state cannot be read. Services are preserved; restore account access and reconcile before allocating.") }
    }
    private fun write(document: JSONObject) {
        val plain = document.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val packed = cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try { stream.write(packed); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        finally { plain.fill(0); packed.fill(0); generations.merge(lockFile.absolutePath, 1L, Long::plus) }
    }
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object {
        private val NAMESPACE = Regex("[A-Za-z0-9_-]{1,80}")
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        private val derivedCache = ConcurrentHashMap<String, Pair<String, Any>>()
        private val generations = ConcurrentHashMap<String, Long>()
        // Separate store instances share a file and may be nested on the backend thread.
        private val heldFileLocks = ThreadLocal.withInitial { mutableSetOf<String>() }
    }
}
