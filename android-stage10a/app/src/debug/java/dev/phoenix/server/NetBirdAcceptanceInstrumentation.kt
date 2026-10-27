package dev.phoenix.server

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.phoenix.core.network.netbird.NetBirdHttpTransport
import org.phoenix.core.network.netbird.NetBirdProvisioningCoordinator
import org.phoenix.core.network.netbird.NetBirdServicesClient
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only acceptance actions. `dnsSnapshot=1` is GET-only and writes the existing
 * phone-session Minecraft.How records to the app's no-backup directory. The storage
 * fixture uses an isolated temporary namespace and an injected fake NetBird API.
 */
class NetBirdAcceptanceInstrumentation : Instrumentation() {
    private var runArguments = Bundle()
    private val started = AtomicBoolean(false)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runArguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        if (!started.compareAndSet(false, true)) return
        val result = Bundle()
        try {
            val message = when {
                runArguments.getString("dnsSnapshot") == "1" -> runDnsSnapshot()
                runArguments.getString("storePersistence") == "1" -> runEncryptedStoreFixture()
                runArguments.getString("oauthState") == "1" -> runOAuthStateDiagnostic()
                runArguments.getString("servicesReadback") == "1" -> runServicesReadback()
                else -> error("Select dnsSnapshot=1, storePersistence=1, oauthState=1, or servicesReadback=1")
            }
            result.putString("stream", message)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            val mode = when {
                runArguments.getString("dnsSnapshot") == "1" -> "dnsSnapshot"
                runArguments.getString("oauthState") == "1" -> "oauthState"
                runArguments.getString("servicesReadback") == "1" -> "servicesReadback"
                else -> "storePersistence"
            }
            result.putString(
                "stream",
                "NETBIRD_ACCEPTANCE FAIL mode=$mode type=${failure.javaClass.simpleName}; raw cookies and provider response bodies omitted",
            )
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /**
     * GET-only NetBird Cloud readback of the saved permanent services and the hosting peer. Prints
     * ids, ports, cluster and flags only; tokens, keys and response bodies are never emitted.
     */
    private fun runServicesReadback(): String {
        val context = targetContext.applicationContext
        val saved = NetBirdStore(context).load().savedResult()
        val credential = NetBirdOAuthSession.credential(context)
        val services = NetBirdServicesClient(UrlConnectionNetBirdTransport(), credential.token, credential.scheme)
            .listServices().associateBy { it.id() }
        val peer = NetBirdPeerClient(UrlConnectionNetBirdTransport(), credential.token, credential.scheme).getPeer(saved.peerId())
        fun describe(label: String, allocation: NetBirdProvisioningCoordinator.Allocation): String {
            val live = services[allocation.serviceId()]
            return "$label id=${allocation.serviceId()} savedPort=${allocation.publicPort()} listed=${live != null} " +
                "mode=${live?.mode()?.wireValue()} listenPort=${live?.listenPort()} enabled=${live?.enabled()} " +
                "status=${live?.status()} proxyCluster=${live?.proxyCluster()}"
        }
        return "NETBIRD_SERVICES_READBACK mode=GET-only savedCluster=${saved.cluster()} | " +
            describe("tcp", saved.tcp()) + " | " + describe("udp", saved.udp()) + " | " +
            "peerIdMatches=${peer.getString("id") == saved.peerId()} peerConnected=${peer.optBoolean("connected")} " +
            "loginExpirationEnabled=${peer.opt("login_expiration_enabled")} " +
            "inactivityExpirationEnabled=${peer.opt("inactivity_expiration_enabled")} " +
            "servicesInAccount=${services.size} credentialValuesPrinted=false responseBodiesPrinted=false"
    }

    /** Reports OAuth/session presence only. Never emits token, key, or config values. */
    private fun runOAuthStateDiagnostic(): String {
        val context = targetContext.applicationContext
        val store = NetBirdStore(context)
        val config = store.configuration()
        val now = System.currentTimeMillis()
        val expiresAt = config.optLong("expiresAt", 0L)
        val endpointHost = runCatching {
            URI(config.optString("tokenEndpoint")).host?.lowercase().orEmpty()
        }.getOrDefault("")
        val apiTokenKind = config.optString("apiTokenKind").takeIf {
            it in setOf("ACCESS", "SELECTED", "UNVALIDATED")
        } ?: "OTHER_OR_MISSING"

        // NetBird writes this file through the official client PlatformFiles API.
        // Parse it in memory and retain only the non-secret identity-presence bit.
        val sdkConfig = File(context.noBackupFilesDir, "netbird-client/config.json")
        val identityPresent = if (sdkConfig.isFile && sdkConfig.length() in 2..(1024 * 1024)) {
            val bytes = sdkConfig.readBytes()
            try {
                JSONObject(String(bytes, Charsets.UTF_8)).optString("PrivateKey").isNotBlank()
            } finally {
                bytes.fill(0)
            }
        } else false

        return "NETBIRD_OAUTH_STATE mode=read-only " +
            "hasToken=${config.optString("token").isNotBlank()} " +
            "hasAccessToken=${config.optString("accessToken").isNotBlank()} " +
            "hasSelectedToken=${config.optString("selectedToken").isNotBlank()} " +
            "hasRefreshToken=${config.optString("refreshToken").isNotBlank()} " +
            "tokenEndpointHost=${endpointHost.ifBlank { "MISSING" }} " +
            "apiTokenKind=$apiTokenKind " +
            "expiresAtEpochMs=$expiresAt " +
            "expiresInSeconds=${if (expiresAt > 0) (expiresAt - now) / 1000 else "UNKNOWN"} " +
            "useIdToken=${config.optBoolean("useIdToken")} " +
            "identityEnrolled=${config.optBoolean("identityEnrolled")} " +
            "sdkConfigIdentityPresent=$identityPresent " +
            "credentialValuesPrinted=false configBodyPrinted=false"
    }

    private fun runDnsSnapshot(): String {
        // The owner's hostname and Minecraft.How account id come from the run arguments, never from the source.
        val chosenHostname = runArguments.getString("hostname")?.trim()?.lowercase()
            ?: error("Pass -e hostname <your Minecraft.How name>")
        val expectedAccountId = runArguments.getString("accountId")?.toLongOrNull()
            ?: error("Pass -e accountId <your Minecraft.How account id>")
        check(MinecraftHowSession.prepareWebViewDataDirectory()) { "Minecraft.How private session profile is unavailable" }
        val destination = File(targetContext.noBackupFilesDir, DNS_SNAPSHOT_NAME)
        val backup = File(destination.path + ".bak")
        check(!destination.exists() && !backup.exists()) { "An earlier DNS baseline already exists; it was preserved" }

        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "phoenix-dns-snapshot") }
        val snapshot = try {
            executor.submit<MinecraftHowClient.DnsSnapshotResult> {
                val cookieHeader = MinecraftHowSession.cookieHeader()
                    ?: error("The phone's existing Minecraft.How session is unavailable")
                MinecraftHowClient().readDnsRecordSnapshot(cookieHeader, chosenHostname)
            }.get(45, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        check(snapshot.state == MinecraftHowClient.DnsSnapshotState.READ) { "Authenticated DNS snapshot state was ${snapshot.state}" }
        check(snapshot.accountId == expectedAccountId) { "The phone session resolved to a different Minecraft.How account" }
        check(snapshot.hostname == chosenHostname && snapshot.domainId != null) { "The chosen hostname ownership did not verify" }

        val records = JSONArray()
        snapshot.records.forEach { record ->
            records.put(JSONObject()
                .put("id", record.id)
                .put("type", record.type)
                .put("name", record.name ?: JSONObject.NULL)
                .put("content", record.content)
                .put("ttl", record.ttl)
                .put("priority", record.priority ?: JSONObject.NULL)
                .put("port", record.port ?: JSONObject.NULL))
        }
        val document = JSONObject()
            .put("schema", "phoenix-netbird-dns-baseline-v1")
            .put("capturedAt", Instant.now().toString())
            .put("source", "Minecraft.How authenticated phone WebView; GET only")
            .put("accountId", snapshot.accountId)
            .put("domainId", snapshot.domainId)
            .put("hostname", snapshot.hostname)
            .put("recordCount", snapshot.records.size)
            .put("records", records)
        writeOnce(AtomicFile(destination), document.toString().toByteArray(Charsets.UTF_8))

        return "NETBIRD_DNS_SNAPSHOT PASS accountId=${snapshot.accountId} domainId=${snapshot.domainId} recordCount=${snapshot.records.size} method=GET cookieOutput=false snapshot=no_backup/$DNS_SNAPSHOT_NAME"
    }

    private fun runEncryptedStoreFixture(): String {
        val context = targetContext.applicationContext
        val namespace = "fixture-${UUID.randomUUID().toString().replace("-", "")}"
        val directory = File(context.noBackupFilesDir, "netbird-$namespace").canonicalFile
        val expectedParent = context.noBackupFilesDir.canonicalFile
        val alias = "phoenix.netbird.$namespace.v1"
        try {
            val store = NetBirdStore(context, namespace)
            val fixtureToken = "synthetic-fixture-token-only"
            val plan = NetBirdProvisioningCoordinator.Plan(
                "phoenix-minecraft-fixture", "fixture-peer",
                "phoenix-java-fixture", "java.fixture.example.net", 25565,
                "phoenix-bedrock-fixture", "bedrock.fixture.example.net", 19132,
                "shared-fixture",
            )
            store.saveConfiguration(JSONObject()
                .put("peerId", plan.peerId())
                .put("tcpDomain", plan.tcpServiceDomain())
                .put("udpDomain", plan.udpServiceDomain())
                .put("cluster", plan.expectedCluster())
                .put("accessToken", fixtureToken))

            val tcpService = serviceJson(
                id = "fixture-tcp-id", name = plan.tcpServiceName(), domain = plan.tcpServiceDomain(),
                mode = "tcp", localPort = plan.tcpLocalPort(), publicPort = 34001, peerId = plan.peerId(),
            )
            val udpService = serviceJson(
                id = "fixture-udp-id", name = plan.udpServiceName(), domain = plan.udpServiceDomain(),
                mode = "udp", localPort = plan.udpLocalPort(), publicPort = 34002, peerId = plan.peerId(),
            )
            val paths = mutableListOf<String>()
            val fakeTransport = NetBirdHttpTransport { request ->
                check(request.uri().scheme == "https") { "Fake client did not use its HTTPS base URI" }
                check(request.headers()["Authorization"] == "Token $fixtureToken") { "Fake API authorization was malformed" }
                paths += "${request.method()} ${request.uri().path}"
                when {
                    request.method() == "GET" && request.uri().path == "/api/reverse-proxies/services" ->
                        NetBirdHttpTransport.Response(200, "[]")
                    request.method() == "POST" && request.body().contains(plan.tcpServiceName()) ->
                        NetBirdHttpTransport.Response(201, tcpService)
                    request.method() == "POST" && request.body().contains(plan.udpServiceName()) ->
                        NetBirdHttpTransport.Response(201, udpService)
                    request.method() == "GET" && request.uri().path.endsWith("/fixture-tcp-id") ->
                        NetBirdHttpTransport.Response(200, tcpService)
                    request.method() == "GET" && request.uri().path.endsWith("/fixture-udp-id") ->
                        NetBirdHttpTransport.Response(200, udpService)
                    else -> error("Unexpected fake NetBird request")
                }
            }
            val client = NetBirdServicesClient(
                fakeTransport, URI.create("https://api.netbird.io"), fixtureToken,
                NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN,
            )
            val provisioner = NetBirdProvisioningCoordinator(client, store) { 1_000_000L }
            val provisioned = provisioner.provision(plan)
            check(provisioned.bothActive()) { "Fake TCP/UDP services did not verify active" }
            check(provisioned.tcp().serviceId() == "fixture-tcp-id" && provisioned.udp().serviceId() == "fixture-udp-id") {
                "Fake service IDs did not persist"
            }

            val stateFile = File(directory, "state.enc")
            check(stateFile.isFile) { "Encrypted state file was not written" }
            val protectedFiles = listOf(stateFile, File(directory, "state.enc.bak")).filter(File::isFile)
            val forbiddenPlaintext = listOf(
                fixtureToken, "fixture-peer", "fixture-tcp-id", "fixture-udp-id",
                plan.tcpServiceDomain(), plan.udpServiceDomain(),
            )
            protectedFiles.forEach { file ->
                val raw = file.readBytes()
                try {
                    val bytesAsText = String(raw, Charsets.ISO_8859_1)
                    forbiddenPlaintext.forEach { marker -> check(!bytesAsText.contains(marker)) { "State file exposed plaintext configuration" } }
                } finally {
                    raw.fill(0)
                }
            }

            val reopened = NetBirdStore(context, namespace)
            val restored = reopened.load()
            check(restored.phase() == NetBirdProvisioningCoordinator.Phase.READY) { "Reopened journal was not READY" }
            check(restored.tcpServiceId() == "fixture-tcp-id" && restored.udpServiceId() == "fixture-udp-id") {
                "Reopened service IDs did not match the fake allocations"
            }
            check(restored.tcpPublicPort() == 34001 && restored.udpPublicPort() == 34002) { "Reopened ports did not match fake allocations" }
            check(reopened.configuration().getString("accessToken") == fixtureToken) { "Encrypted configuration did not round-trip" }

            // Reopen the same encrypted file through another store instance while the
            // original instance owns its lock. Both the nested read and write must
            // avoid reacquiring the process file lock, and the write must retain the
            // identity keys associated with the already allocated services.
            val nestedReader = NetBirdStore(context, namespace)
            store.locked {
                val nestedConfiguration = nestedReader.configuration()
                check(nestedConfiguration.getString("accessToken") == fixtureToken) {
                    "Nested cross-instance configuration read did not round-trip"
                }
                reopened.saveConfiguration(nestedConfiguration.put("displayLabel", "fixture-updated"))
            }
            val updatedConfiguration = store.configuration()
            check(updatedConfiguration.getString("displayLabel") == "fixture-updated") {
                "Nested cross-instance configuration update did not persist"
            }
            check(updatedConfiguration.getString("accessToken") == fixtureToken) {
                "Nested configuration update lost the encrypted access token"
            }
            check(updatedConfiguration.getString("peerId") == plan.peerId() &&
                updatedConfiguration.getString("tcpDomain") == plan.tcpServiceDomain() &&
                updatedConfiguration.getString("udpDomain") == plan.udpServiceDomain() &&
                updatedConfiguration.getString("cluster") == plan.expectedCluster()) {
                "Nested configuration update changed allocation identity keys"
            }
            val afterNestedUpdate = store.load()
            check(afterNestedUpdate.phase() == NetBirdProvisioningCoordinator.Phase.READY &&
                afterNestedUpdate.tcpServiceId() == "fixture-tcp-id" &&
                afterNestedUpdate.udpServiceId() == "fixture-udp-id") {
                "Nested configuration update changed the existing service allocation journal"
            }
            check(!reopened.compareAndSet(0, NetBirdProvisioningCoordinator.Snapshot.empty())) { "Stale journal revision was accepted" }
            check(paths.count { it.startsWith("POST ") } == 2) { "Fake coordinator did not create exactly two test services" }

            return "NETBIRD_STORE_FIXTURE PASS encryptedConfig=true restartJournal=READY tcpUdpPersistence=true nestedCrossInstanceLock=true allocationKeysPreserved=true staleCasRejected=true fakeApiRequests=${paths.size} liveProviderRequests=0"
        } finally {
            check(directory.parentFile == expectedParent) { "Fixture cleanup path escaped no-backup storage" }
            directory.deleteRecursively()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private fun serviceJson(
        id: String,
        name: String,
        domain: String,
        mode: String,
        localPort: Int,
        publicPort: Int,
        peerId: String,
    ): String = """{"id":"$id","name":"$name","domain":"$domain","mode":"$mode","listen_port":$publicPort,"port_auto_assigned":true,"proxy_cluster":"shared-fixture","targets":[{"target_id":"$peerId","target_type":"peer","protocol":"$mode","port":$localPort,"enabled":true}],"enabled":true,"terminated":false,"meta":{"status":"active"}}"""

    private fun writeOnce(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private const val DNS_SNAPSHOT_NAME = "netbird-dns-before.json"
    }
}
