package dev.phoenix.server

import org.phoenix.core.ServerController.State

/** One line of the online-play checklist. */
data class ChecklistItem(val title: String, val detail: String?, val tone: Tone)

/**
 * Words and colors for networking, shared by Home and Settings so both always say the same thing about the same state.
 * Pure: derived only from [Dashboard.net] and the server phase, and unit tested.
 */
object NetworkStatus {
    /** The provider Home shows: the selected route, or Playit as the recommended first choice. */
    fun homeProvider(view: NetworkView): ProviderState =
        view.selected?.takeIf { it.isPublic }?.let(view::provider) ?: view.provider(PublicRoute.PLAYIT)

    /**
     * Green connected, blue working, red error. A provider that isn't set up is orange when it is the one that matters
     * (the selected route, or the recommended Playit before any route is chosen), otherwise neutral gray.
     */
    fun tone(provider: ProviderState, view: NetworkView): Tone = when (provider.health) {
        LinkHealth.CONNECTED -> Tone.OK
        LinkHealth.CONNECTING -> Tone.WORKING
        LinkHealth.ERROR -> Tone.ERROR
        LinkHealth.NOT_SET_UP -> if (provider.selected || (view.selected == null && provider.route == PublicRoute.PLAYIT)) Tone.WARN else Tone.IDLE
        // Selected and set up, waiting for the server: orange "Idle", not a gray that looks broken.
        LinkHealth.OFF -> if (provider.selected && provider.configured) Tone.WARN else Tone.IDLE
    }

    fun nameStatus(name: CustomName): String = when (name.health) {
        NameHealth.NOT_SET_UP -> "Not active"
        NameHealth.OFF -> if (name.hostname != null) "Off" else "Not active"
        NameHealth.SIGN_IN -> "Sign-in needed"
        NameHealth.UPDATING -> "Updating…"
        NameHealth.PROPAGATING -> "Waiting for DNS"
        NameHealth.ACTIVE -> "Connected"
        NameHealth.ERROR -> "Needs attention"
        NameHealth.UNREACHABLE -> "Unavailable"
    }

    /** Gray when unused, green when verified, red on a real error; an outage or DNS propagation is not an error. */
    fun nameTone(name: CustomName): Tone = when (name.health) {
        NameHealth.ACTIVE -> Tone.OK
        NameHealth.UPDATING, NameHealth.PROPAGATING -> Tone.WORKING
        NameHealth.SIGN_IN, NameHealth.UNREACHABLE -> Tone.WARN
        NameHealth.ERROR -> Tone.ERROR
        NameHealth.NOT_SET_UP, NameHealth.OFF -> Tone.IDLE
    }

    /** Home's optional-name step: green only for a verified name; a saved but unchecked name shows its real state. */
    fun homeNameLine(name: CustomName): Pair<String, Tone> {
        val host = name.hostname ?: return "A free name like you.minecraft.how, in Settings" to Tone.IDLE
        return if (name.health == NameHealth.ACTIVE) host to Tone.OK else "$host · ${nameStatus(name)}" to nameTone(name)
    }

    /** Can friends join right now, and if not, why. */
    fun joinStatus(d: Dashboard): Pair<String, Tone> {
        val view = d.net
        val active = view.active
        return when {
            view.switching != null -> view.switching.step to Tone.WORKING
            d.phase == State.STARTING || d.phase == State.RESTARTING -> "Server is starting…" to Tone.WORKING
            d.phase != State.ONLINE -> "Start the server so friends can join" to Tone.IDLE
            d.serverNotResponding -> "The server isn't responding. Restart it if it doesn't recover" to Tone.ERROR
            view.selected == null -> "Connect Playit.gg or NetBird so friends can join from anywhere" to Tone.WARN
            view.selected == PublicRoute.LOCAL -> "Public access is off: friends on the same Wi-Fi only" to Tone.IDLE
            active == null -> "Online play isn't set up" to Tone.WARN
            active.health == LinkHealth.ERROR -> "${active.route.title}: ${active.status}" to Tone.ERROR
            active.health == LinkHealth.CONNECTING -> "Connecting ${active.route.title}…" to Tone.WORKING
            active.health != LinkHealth.CONNECTED -> "${active.route.title} isn't connected" to Tone.WARN
            view.readiness.java == Check.FAILED -> "Friends can't reach Java through ${active.route.title} yet" to Tone.ERROR
            view.readiness.java == Check.PENDING || view.readiness.java == Check.UNKNOWN -> "Checking the public address…" to Tone.WORKING
            else -> "Friends can join now" to Tone.OK
        }
    }

    /** The selected route's milestones: account, relay, Java, Bedrock, voice and a public address, each with what is missing. */
    fun checklist(d: Dashboard): List<ChecklistItem> {
        val view = d.net
        val active = view.active
        val title = active?.route?.title ?: "a route"
        val online = d.phase == State.ONLINE
        fun check(item: String, check: Check, ok: String?, failed: String, pending: String = "Checking…", idle: String? = null) = when (check) {
            Check.OK -> ChecklistItem(item, ok, Tone.OK)
            Check.FAILED -> ChecklistItem(item, failed, Tone.ERROR)
            Check.PENDING -> ChecklistItem(item, pending, Tone.WORKING)
            Check.NOT_APPLICABLE -> ChecklistItem(item, "Not used", Tone.IDLE)
            Check.UNKNOWN -> ChecklistItem(item, idle ?: if (online) pending else "Checked when the server is online", Tone.IDLE)
        }
        val account = when {
            active == null -> ChecklistItem("Account connected", "Choose Playit.gg or NetBird", Tone.IDLE)
            active.configured -> ChecklistItem("Account connected", active.route.title, Tone.OK)
            active.health == LinkHealth.CONNECTING -> ChecklistItem("Account connected", active.detail ?: active.status, Tone.WORKING)
            else -> ChecklistItem("Account connected", "Sign in to ${active.route.title}", Tone.WARN)
        }
        val relay = when {
            active == null -> ChecklistItem("Relay connected", null, Tone.IDLE)
            active.health == LinkHealth.CONNECTED -> ChecklistItem("Relay connected", title, Tone.OK)
            active.health == LinkHealth.ERROR -> ChecklistItem("Relay connected", active.detail ?: active.status, Tone.ERROR)
            active.health == LinkHealth.CONNECTING -> ChecklistItem("Relay connected", active.status, Tone.WORKING)
            else -> ChecklistItem("Relay connected", active.detail ?: "Connects when the server starts", Tone.IDLE)
        }
        val endpoints = active?.endpoints
        val java = check("Java ready", view.readiness.java, endpoints?.java?.text?.let { "Answers at $it" }, "Didn't answer through $title")
        val bedrock = if (endpoints?.bedrock == null && active != null && active.configured)
            ChecklistItem("Bedrock ready", d.playitProblems.firstOrNull { "Bedrock" in it } ?: "No Bedrock address from $title", if (active.route == PublicRoute.PLAYIT) Tone.WARN else Tone.IDLE)
            else check("Bedrock ready", view.readiness.bedrock, endpoints?.bedrock?.let { "Answers at ${it.host} port ${it.port}" }, "Didn't answer through $title")
        val voice = when {
            d.voiceChatPort == 0 -> ChecklistItem("Voice ready", "Simple Voice Chat is not installed", Tone.IDLE)
            endpoints?.voice != null -> ChecklistItem("Voice ready", "Forwarded to ${endpoints.voice.text}; players confirm it in game", if (active?.health == LinkHealth.CONNECTED) Tone.OK else Tone.IDLE)
            active == null -> ChecklistItem("Voice ready", null, Tone.IDLE)
            else -> ChecklistItem("Voice ready", d.playitProblems.firstOrNull { "Voice" in it } ?: "Not forwarded through $title yet", Tone.WARN)
        }
        val options = Addresses.options(view)
        val address = options.firstOrNull()?.let { ChecklistItem("Public address available", it.java.text, Tone.OK) }
            ?: ChecklistItem("Public address available", null, Tone.IDLE)
        return listOf(account, relay, java, bedrock, voice, address)
    }
}

/** Home's top card, in priority order. */
enum class HomeAddressCard { ADDRESSES, NO_WIFI, LOCAL_ONLY, WAITING, SETUP }

object HomeCards {
    /**
     * The live addresses; no Wi-Fi (while the server isn't already online); public access off (same Wi-Fi only); a route
     * that is set up and waits for the server (empty address box); otherwise the short getting-started list.
     */
    fun address(d: Dashboard): HomeAddressCard {
        val view = d.net
        val routeReady = view.selected?.isPublic == true && view.active?.configured == true && d.playitClaimUrl == null
        return when {
            Addresses.options(view).isNotEmpty() -> HomeAddressCard.ADDRESSES
            d.lanIp == null && d.phase != State.ONLINE -> HomeAddressCard.NO_WIFI
            view.selected == PublicRoute.LOCAL -> HomeAddressCard.LOCAL_ONLY
            routeReady -> HomeAddressCard.WAITING
            else -> HomeAddressCard.SETUP
        }
    }
}

