package dev.phoenix.server

import org.phoenix.core.network.netbird.ProxyPairing
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Listens for the owner's new Oracle proxy while a pairing is open. The VM reaches this port through the temporary
 * pairing service on NetBird's shared cluster; every connection must prove the pairing code before a token is minted
 * (see [ProxyPairing]). One connection at a time; the thread ends when the pairing closes or expires.
 */
class ProxyPairingHost(
    private val netBird: NetBirdNetworkManager,
    private val log: (String) -> Unit,
    private val onPaired: (ProxyPairing.VmInfo) -> Unit,
) {
    @Volatile private var thread: Thread? = null

    /** Starts listening if a pairing is open and nothing listens yet. Cheap when there is nothing to do. */
    fun ensureRunning() {
        if (thread?.isAlive == true) return
        val pending = runCatching { netBird.pendingPairing() }.getOrNull() ?: return
        synchronized(this) {
            if (thread?.isAlive == true) return
            thread = Thread({ listen(pending) }, "phoenix-pairing").apply { isDaemon = true; start() }
        }
    }

    private fun listen(pending: NetBirdNetworkManager.PendingPairing) {
        val random = SecureRandom()
        val secret = pending.code.secret()
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(pending.localPort), 1)
                server.soTimeout = 30_000
                log("Waiting for your Oracle proxy to pair")
                while (System.currentTimeMillis() < pending.expiresAt) {
                    val current = runCatching { netBird.pendingPairing() }.getOrNull()
                    if (current == null || current.localPort != pending.localPort || current.code.encode() != pending.code.encode()) return
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 30_000
                        try {
                            val vm = ProxyPairing.serve(it.getInputStream(), it.getOutputStream(), secret, { netBird.issueProxyToken() }, random)
                            netBird.finishPairing(vm)
                            log("Paired with your proxy in ${vm.region().ifBlank { "Oracle Cloud" }} (${vm.shape().ifBlank { "free VM" }})")
                            onPaired(vm)
                            return
                        } catch (rejected: ProxyPairing.PairingException) {
                            if (rejected.authentication() && netBird.pairingRejected()) {
                                log("Pairing closed after repeated wrong codes")
                                return
                            }
                        } catch (failure: Exception) {
                            log("Pairing attempt failed: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            log("Pairing listener stopped: ${failure.message ?: failure.javaClass.simpleName}")
        } finally {
            secret.fill(0)
        }
    }
}
