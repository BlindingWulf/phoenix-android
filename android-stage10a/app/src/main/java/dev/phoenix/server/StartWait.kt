package dev.phoenix.server

import org.phoenix.core.ServerController.State

/**
 * One step of Phoenix's wait for a start or restart to come online, decided from the controller's state alone.
 * [Late]: the start outlasted the readiness timeout (the controller's or Phoenix's own) but Java still runs, so it may
 * still finish; Phoenix keeps waiting, and the user's Stop ends the wait.
 */
internal sealed interface StartWait {
    data object Online : StartWait
    data object Waiting : StartWait
    data object Late : StartWait
    data object Stopped : StartWait
    data class Failed(val reason: String) : StartWait

    companion object {
        fun next(state: State, detail: String, javaAlive: Boolean, mayStillFinish: Boolean, pastDeadline: Boolean): StartWait = when {
            state == State.ONLINE -> Online
            state == State.STOPPING || state == State.STOPPED -> Stopped
            state == State.ERROR -> if (mayStillFinish) Late else Failed(detail)
            // Between the old Java's exit and the new one's start there is no process: that is the restart, not a failure.
            state == State.RESTARTING -> Waiting
            !javaAlive -> Failed("Minecraft exited while starting; see the console")
            pastDeadline -> Late
            else -> Waiting
        }
    }
}
