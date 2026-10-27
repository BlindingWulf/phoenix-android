package dev.phoenix.server

/**
 * Phoenium's tile engine, chosen in Settings > Performance. Standard (the default) adds no flags, so the launch is the one
 * Phoenium runs by default. Acceleration adds only Phoenium's own engine properties (EngineConfig.java, Phoenium 0.2.0-stage2).
 * H-BIG, AOT and diagnostics flags are never added here. Kept free of Android types so the exact flags are unit tested.
 */
object PhoeniumTuning {
    const val PREF_ACCELERATION = "phoenium-acceleration-v1"
    const val PREF_WORKERS = "phoenium-engine-workers-v1"
    const val DEFAULT_ACCELERATION = false
    /** The tile-engine worker count the phone runs were measured with. */
    const val DEFAULT_WORKERS = 2
    const val MIN_WORKERS = 1
    const val MAX_WORKERS = 4
    /** Only Phoenium builds with the tile engine contain this class; older builds get no acceleration flags. */
    const val ENGINE_CLASS = "dev/phoenium/engine/EngineConfig.class"

    /** Leaves one core for the server thread and one for the system, and never more than [MAX_WORKERS]. */
    fun maxWorkers(cores: Int): Int = (cores - 2).coerceIn(MIN_WORKERS, MAX_WORKERS)

    fun clampWorkers(requested: Int?, cores: Int): Int = (requested ?: DEFAULT_WORKERS).coerceIn(MIN_WORKERS, maxWorkers(cores))

    fun isEngineBuild(entryNames: Collection<String>): Boolean = ENGINE_CLASS in entryNames

    /** Acceleration works only with Fabric, an enabled Phoenium, and a Phoenium build that contains the tile engine. */
    fun applies(phoeniumEnabled: Boolean, engineBuild: Boolean, fabric: Boolean): Boolean = phoeniumEnabled && engineBuild && fabric

    /** The exact launch flags: nothing in Standard mode or when Phoenium cannot use the engine. */
    fun flags(acceleration: Boolean, active: Boolean, workers: Int): List<String> {
        if (!acceleration || !active) return emptyList()
        return listOf(
            "-Dphoenium.engine.tiles=true",
            "-Dphoenium.engine.workers=${workers.coerceIn(MIN_WORKERS, MAX_WORKERS)}",
            "-Dphoenium.engine.schedule=dag",
        )
    }
}
