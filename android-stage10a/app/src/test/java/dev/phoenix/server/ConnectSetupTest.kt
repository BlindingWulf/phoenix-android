package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.ServerController

/** Shared networking wording (Home and Settings read the same state) and the Oracle catalog. */
class ConnectSetupTest {
    private val playitEndpoints = RouteEndpoints(Endpoint("java-name.gl.joinmc.link", null), Endpoint("bedrock-name.gl.at.ply.gg", 6695),
        Endpoint("voice-name.gl.at.ply.gg", 7712), Endpoint("java-name.gl.at.ply.gg", 25565))

    private fun net(selected: PublicRoute?, playit: ProviderState = ProviderState(PublicRoute.PLAYIT), readiness: Readiness = Readiness(),
                    name: CustomName = CustomName(), switching: RouteSwitch? = null) = NetworkView(
        selected, PublicRoute.entries.associateWith { if (it == PublicRoute.PLAYIT) playit else ProviderState(it) }, name, readiness, switching)

    private val connected = ProviderState(PublicRoute.PLAYIT, selected = true, configured = true, health = LinkHealth.CONNECTED,
        status = "Connected", endpoints = playitEndpoints)
    private val online = Dashboard(phase = ServerController.State.ONLINE, voiceChatPort = 24454)

    @Test fun aFreshInstallRecommendsPlayitInOrangeAndNeverClaimsItIsConnected() {
        val view = net(null)
        val home = NetworkStatus.homeProvider(view)
        assertEquals(PublicRoute.PLAYIT, home.route)
        assertEquals("Not set up", home.status)
        assertEquals(Tone.WARN, NetworkStatus.tone(home, view))
        // Another provider that isn't set up is neutral, not a warning.
        assertEquals(Tone.IDLE, NetworkStatus.tone(view.provider(PublicRoute.NETBIRD_EU), view))
        assertTrue(Addresses.options(view).isEmpty())
        assertEquals(Tone.WARN, NetworkStatus.joinStatus(online.copy(net = view)).second)
    }

    @Test fun homeShowsTheSelectedProviderNotAHardcodedPlayitCard() {
        val netBird = ProviderState(PublicRoute.NETBIRD_EU, selected = true, configured = true, health = LinkHealth.CONNECTED, status = "Connected")
        val view = NetworkView(PublicRoute.NETBIRD_EU, PublicRoute.entries.associateWith { if (it == PublicRoute.NETBIRD_EU) netBird else ProviderState(it) })
        assertEquals(PublicRoute.NETBIRD_EU, NetworkStatus.homeProvider(view).route)
        assertEquals(Tone.OK, NetworkStatus.tone(NetworkStatus.homeProvider(view), view))
    }

    @Test fun errorsAreRedAndConnectingIsBlue() {
        val error = connected.copy(health = LinkHealth.ERROR, status = "Agent disconnected")
        assertEquals(Tone.ERROR, NetworkStatus.tone(error, net(PublicRoute.PLAYIT, error)))
        val joining = NetworkStatus.joinStatus(online.copy(net = net(PublicRoute.PLAYIT, error)))
        assertEquals("Playit.gg: Agent disconnected" to Tone.ERROR, joining)
        val connecting = connected.copy(health = LinkHealth.CONNECTING, status = "Connecting…")
        assertEquals(Tone.WORKING, NetworkStatus.tone(connecting, net(PublicRoute.PLAYIT, connecting)))
    }

    @Test fun friendsCanJoinOnlyAfterThePublicJavaCheckPasses() {
        val pending = online.copy(net = net(PublicRoute.PLAYIT, connected, Readiness(server = Check.OK, relay = Check.OK, java = Check.PENDING)))
        assertEquals(Tone.WORKING, NetworkStatus.joinStatus(pending).second)
        val failed = online.copy(net = net(PublicRoute.PLAYIT, connected, Readiness(server = Check.OK, relay = Check.OK, java = Check.FAILED)))
        assertEquals(Tone.ERROR, NetworkStatus.joinStatus(failed).second)
        val ok = online.copy(net = net(PublicRoute.PLAYIT, connected, Readiness(server = Check.OK, relay = Check.OK, java = Check.OK, bedrock = Check.OK, voice = Check.OK)))
        assertEquals("Friends can join now" to Tone.OK, NetworkStatus.joinStatus(ok))
        val stopped = ok.copy(phase = ServerController.State.STOPPED)
        assertEquals(Tone.IDLE, NetworkStatus.joinStatus(stopped).second)
        val switching = ok.copy(net = ok.net.copy(switching = RouteSwitch(PublicRoute.NETBIRD_EU, PublicRoute.PLAYIT, "Creating the Playit Java tunnel")))
        assertEquals("Creating the Playit Java tunnel" to Tone.WORKING, NetworkStatus.joinStatus(switching))
        val local = online.copy(net = net(PublicRoute.LOCAL))
        assertTrue(NetworkStatus.joinStatus(local).first.contains("same Wi-Fi"))
        // Two missed health checks on the phone itself beat a green public check.
        assertEquals(Tone.ERROR, NetworkStatus.joinStatus(ok.copy(serverNotResponding = true)).second)
    }

    @Test fun aSetUpRouteWaitingForTheServerIsOrangeIdleNotGray() {
        val idle = ProviderState(PublicRoute.PLAYIT, selected = true, configured = true, health = LinkHealth.OFF, status = "Idle", detail = "Ready to start the server")
        assertEquals(Tone.WARN, NetworkStatus.tone(idle, net(PublicRoute.PLAYIT, idle)))
        val notInUse = ProviderState(PublicRoute.PLAYIT, selected = false, configured = true, health = LinkHealth.OFF, status = "Off")
        assertEquals(Tone.IDLE, NetworkStatus.tone(notInUse, net(PublicRoute.NETBIRD_EU, notInUse)))
    }

    @Test fun bedrockIsSavedByPlayitsIpv4AndTheNameFollowsIt() {
        val withIp = playitEndpoints.copy(bedrockIp = "147.185.221.213")
        assertEquals(Endpoint("147.185.221.213", 6695), withIp.bedrockForList)
        assertEquals(Endpoint("bedrock-name.gl.at.ply.gg", 6695), playitEndpoints.bedrockForList)
        assertTrue("a new IPv4 means the name's records must be checked again", withIp.fingerprint != playitEndpoints.fingerprint)
        val view = net(PublicRoute.PLAYIT, connected.copy(endpoints = withIp))
        assertEquals(Endpoint("147.185.221.213", 6695), Addresses.options(view).single().bedrock)
        // Shared text tells Bedrock players which address shows players in their server list.
        val shared = Addresses.shareText("Phoenix", listOf(
            AddressOption(true, "Custom address", Endpoint("friends.example", null), Endpoint("friends.example", 6695)),
            AddressOption(false, "Playit.gg address", Endpoint("java-name.gl.joinmc.link", null), Endpoint("147.185.221.213", 6695))))
        assertTrue(shared.contains("147.185.221.213  Port: 6695\n(Use this IP in Bedrock to see players in the server list.)"))
    }

    @Test fun checklistKeepsServerRelayJavaBedrockAndVoiceSeparate() {
        val d = online.copy(net = net(PublicRoute.PLAYIT, connected, Readiness(server = Check.OK, relay = Check.OK, java = Check.OK, bedrock = Check.FAILED, voice = Check.OK)))
        val items = NetworkStatus.checklist(d)
        assertEquals(listOf("Account connected", "Relay connected", "Java ready", "Bedrock ready", "Voice ready", "Public address available"), items.map { it.title })
        assertEquals(listOf(Tone.OK, Tone.OK, Tone.OK, Tone.ERROR, Tone.OK, Tone.OK), items.map { it.tone })
        assertTrue(items[4].detail!!.contains("players confirm"))
        assertEquals("java-name.gl.joinmc.link", items[5].detail)
        val noVoiceMod = NetworkStatus.checklist(d.copy(voiceChatPort = 0))[4]
        assertEquals(Tone.IDLE, noVoiceMod.tone)
        val voiceMissing = NetworkStatus.checklist(d.copy(playitProblems = listOf("Playit refused the Voice chat tunnel (tunnel rejected)."),
            net = net(PublicRoute.PLAYIT, connected.copy(endpoints = playitEndpoints.copy(voice = null)))))[4]
        assertEquals(Tone.WARN, voiceMissing.tone)
    }

    @Test fun theOptionalNameIsGrayWhenUnusedGreenWhenVerifiedAndAnOutageIsNotAnError() {
        assertEquals("Not active" to Tone.IDLE, NetworkStatus.nameStatus(CustomName()) to NetworkStatus.nameTone(CustomName()))
        val active = CustomName(true, "fun.minecraft.co.com", NameHealth.ACTIVE)
        assertEquals("Connected" to Tone.OK, NetworkStatus.nameStatus(active) to NetworkStatus.nameTone(active))
        assertEquals("Unavailable", NetworkStatus.nameStatus(active.copy(health = NameHealth.UNREACHABLE)))
        assertEquals("Needs attention", NetworkStatus.nameStatus(active.copy(health = NameHealth.ERROR)))
        assertEquals(Tone.ERROR, NetworkStatus.nameTone(active.copy(health = NameHealth.ERROR)))
        assertEquals(Tone.WARN, NetworkStatus.nameTone(active.copy(health = NameHealth.UNREACHABLE)))
        assertEquals(Tone.WORKING, NetworkStatus.nameTone(active.copy(health = NameHealth.PROPAGATING)))
        assertEquals(Tone.WARN, NetworkStatus.nameTone(active.copy(health = NameHealth.SIGN_IN)))
        assertEquals("Off", NetworkStatus.nameStatus(active.copy(enabled = false, health = NameHealth.OFF)))
    }

    @Test fun homeShowsTheSavedNameGreenOnlyWhenItIsVerified() {
        assertEquals("A free name like you.minecraft.how, in Settings" to Tone.IDLE, NetworkStatus.homeNameLine(CustomName()))
        val saved = CustomName(true, "bestserver.minecraft.co.com", NameHealth.ERROR)
        assertEquals("bestserver.minecraft.co.com · Needs attention" to Tone.ERROR, NetworkStatus.homeNameLine(saved))
        assertEquals(Tone.WORKING, NetworkStatus.homeNameLine(saved.copy(health = NameHealth.PROPAGATING)).second)
        assertEquals("bestserver.minecraft.co.com" to Tone.OK, NetworkStatus.homeNameLine(saved.copy(health = NameHealth.ACTIVE)))
    }

    @Test fun regionCatalogCoversTheThreeAreasAndUnknownRegionsShowTheirId() {
        assertEquals(listOf("us", "ca", "eu"), OracleRegions.areas.map { it.key })
        assertEquals("ca-toronto-1".let { OracleRegions.place(it) }, "Canada Southeast (Toronto)")
        assertEquals("ap-tokyo-1", OracleRegions.place("ap-tokyo-1"))
    }

    @Test fun withoutAPublishedStackOracleOpensTheEmptyCreatePage() {
        if (BuildConfig.ORACLE_STACK_ZIP_URL.isBlank()) assertEquals("https://cloud.oracle.com/resourcemanager/stacks/create", OracleDeploy.deployUrl())
        else assertTrue(OracleDeploy.deployUrl().endsWith("?zipUrl=" + BuildConfig.ORACLE_STACK_ZIP_URL))
    }

    @Test fun homeShowsNoWifiOnlyWhenThereIsNoWifiAndNothingElseToShow() {
        val stopped = Dashboard(phase = ServerController.State.STOPPED)
        val noWifi = stopped.copy(lanIp = null, net = net(null))
        assertEquals(HomeAddressCard.NO_WIFI, HomeCards.address(noWifi))
        assertEquals("a new owner on Wi-Fi gets the getting-started list", HomeAddressCard.SETUP, HomeCards.address(noWifi.copy(lanIp = "10.0.0.5")))
        // An online server keeps its card even if the Wi-Fi address is momentarily unknown.
        assertEquals(HomeAddressCard.SETUP, HomeCards.address(noWifi.copy(phase = ServerController.State.ONLINE)))
        val idle = ProviderState(PublicRoute.PLAYIT, selected = true, configured = true, health = LinkHealth.OFF, status = "Idle")
        assertEquals("a set-up route waits with an empty address box", HomeAddressCard.WAITING,
            HomeCards.address(stopped.copy(lanIp = "10.0.0.5", net = net(PublicRoute.PLAYIT, idle))))
        assertEquals(HomeAddressCard.NO_WIFI, HomeCards.address(stopped.copy(lanIp = null, net = net(PublicRoute.PLAYIT, idle))))
        assertEquals(HomeAddressCard.LOCAL_ONLY, HomeCards.address(stopped.copy(lanIp = "10.0.0.5", net = net(PublicRoute.LOCAL))))
        assertEquals("live addresses win over everything", HomeAddressCard.ADDRESSES,
            HomeCards.address(online.copy(lanIp = null, net = net(PublicRoute.PLAYIT, connected))))
    }
}

