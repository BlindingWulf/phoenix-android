package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTest {
    @Test
    fun plainTextIsChatAndSlashRunsACommand() {
        assertEquals("say hello everyone", Console.toServerLine("  hello everyone "))
        assertEquals("time set day", Console.toServerLine("/time set day"))
        assertEquals("list", Console.toServerLine("/ list"))
        assertNull(Console.toServerLine("   "))
        assertNull(Console.toServerLine("/"))
    }

    @Test
    fun recognisesOnlyPhoenixStatusPollReplies() {
        assertTrue(Console.isStatusPollReply("[10:00:01] [Server thread/INFO]: There are 1 of a max of 20 players online: SteveTest"))
        assertTrue(Console.isStatusPollReply("[10:00:01] [Server thread/INFO]: The game is running normally"))
        assertTrue(Console.isStatusPollReply("[10:00:01] [Server thread/INFO]: Seed: [-123]"))
        assertFalse(Console.isStatusPollReply("[10:00:01] [Server thread/INFO]: SteveTest joined the game"))
        assertFalse(Console.isStatusPollReply("[10:00:01] [Server thread/INFO]: <SteveTest> There are 1 of a max of 20 players online"))
    }

    @Test
    fun parsesLogLinesIntoColoredKinds() {
        val join = Console.parse("[10:13:52] [Server thread/INFO]: .BedrockPlayer1 joined the game")
        assertEquals("10:13:52", join.time)
        assertEquals(".BedrockPlayer1 joined the game", join.text)
        assertEquals(Console.Kind.JOIN, join.kind)
        assertEquals(Console.Kind.LEAVE, Console.parse("[10:21:04] [Server thread/INFO]: .BedrockPlayer1 left the game").kind)
        assertEquals(Console.Kind.CHAT, Console.parse("[10:15:00] [Server thread/INFO]: <SteveTest> hi").kind)
        assertEquals(Console.Kind.CHAT, Console.parse("[10:15:00] [Server thread/INFO]: [Not Secure] [Server] hi").kind)
        assertEquals(Console.Kind.WARN, Console.parse("[10:15:00] [Server thread/WARN]: Can't keep up!").kind)
        assertEquals(Console.Kind.ERROR, Console.parse("[10:15:00] [Server thread/ERROR]: Exception").kind)
        assertEquals(Console.Kind.ERROR, Console.parse("[10:15:00] [Server thread/INFO]: hello<--[HERE]").kind)
        assertEquals(Console.Kind.SAVE, Console.parse("[10:15:00] [Server thread/INFO]: Saved the game").kind)
        assertEquals(Console.Kind.INFO, Console.parse("no prefix at all").kind)
    }

    @Test
    fun marksWhatTheUserSentAndPhoenixNotes() {
        val sent = Console.parse(Console.SENT_PREFIX + "/list")
        assertEquals(Console.Kind.SENT, sent.kind)
        assertEquals("/list", sent.text)
        assertNull(sent.time)
        val note = Console.parse(Console.PHOENIX_PREFIX + "Bedrock chat enabled")
        assertEquals(Console.Kind.PHOENIX, note.kind)
        assertEquals("Bedrock chat enabled", note.text)
    }

    @Test
    fun playerAddressesAreHiddenOnScreenOnly() {
        val raw = "Steve[/203.0.113.7:51234] logged in with entity id 42 at (1.5, 64.0, -3.5)"
        val shown = Console.hideAddresses(raw)
        assertEquals("Steve[/•••] logged in with entity id 42 at (1.5, 64.0, -3.5)", shown)
        assertEquals("the original line is untouched", "Steve[/203.0.113.7:51234] logged in with entity id 42 at (1.5, 64.0, -3.5)", raw)
        assertEquals("Alex[/•••] logged in", Console.hideAddresses("Alex[/[2001:db8::7]:51234] logged in"))
        // Versions, coordinates and clock times are not addresses.
        assertEquals("Loaded 214 mods: fabric 0.19.5, minecraft 26.2", Console.hideAddresses("Loaded 214 mods: fabric 0.19.5, minecraft 26.2"))
        assertEquals("Done (12.345s)! at [12:34:56]", Console.hideAddresses("Done (12.345s)! at [12:34:56]"))
        assertTrue(Console.hideAddresses("/198.51.100.20:60000 lost connection").startsWith("/•••"))
    }

    /** What the console does with each arriving line: fold it into the entry above, or start a new entry. */
    private fun feed(lines: List<String>): List<Console.Line> {
        val out = ArrayList<Console.Line>()
        for (raw in lines) {
            val folded = Console.foldInto(out.lastOrNull(), raw)
            if (folded != null) out[out.lastIndex] = folded else out.add(Console.parse(raw))
        }
        return out
    }

    @Test
    fun aCrashIsOneErrorEntryNamingItsCauseAndReportFile() {
        val lines = feed(listOf(
            "[04:39:50] [Server thread/ERROR]: Encountered an unexpected exception",
            "net.minecraft.ReportedException: Exception ticking world",
            "\tat knot//net.minecraft.server.MinecraftServer.tickChildren(MinecraftServer.java:1141)",
            "Caused by: dev.phoenium.chunk.ChunkProtocolException: unfinished synchronous chunk dependency inside an owner transaction",
            "\tat knot//dev.phoenium.chunk.ChunkSynchronousAccessGuard.beforeWait(ChunkSynchronousAccessGuard.java:14)",
            "\t... 9 more",
            "[04:39:50] [Server thread/ERROR]: This crash report has been saved to: /data/data/dev.phoenix.server.rccheck/files/servers/baseline/crash-reports/crash-2026-10-20_04.39.50-server.txt",
            "[04:39:50] [Server thread/INFO]: ViaVersion has been disabled; uninjected the platform and shut down the scheduler.",
        ))
        assertEquals(2, lines.size)
        val crash = lines[0]
        assertEquals(Console.Kind.CRASH, crash.kind)
        assertEquals("Server crashed: ChunkProtocolException: unfinished synchronous chunk dependency inside an owner transaction", crash.text)
        assertEquals("crash-2026-10-20_04.39.50-server.txt", crash.note)
        assertEquals("the trace keeps every line, the header first and the report path last", 7, crash.trace.size)
        assertEquals("Encountered an unexpected exception", crash.trace.first())
        assertTrue(crash.trace.last().startsWith("This crash report has been saved to: /data/"))
        assertEquals(Console.Kind.INFO, lines[1].kind)
        assertEquals("ViaVersion has been disabled; uninjected the platform and shut down the scheduler.", lines[1].text)
    }

    @Test
    fun traceLinesFoldIntoTheWarningAboveAndNeverBecomeEntries() {
        val lines = feed(listOf(
            "[01:23:56] [main/WARN]: Failed retrieving info for group processor",
            "Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.UnsatisfiedLinkError: /data/x.tmp: dlopen failed",
            "[01:23:56] [main/WARN]: Failed retrieving info for group memory",
            "Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError",
        ))
        assertEquals(2, lines.size)
        assertEquals(Console.Kind.WARN, lines[0].kind)
        assertEquals(1, lines[0].trace.size)
        assertEquals("Failed retrieving info for group memory", lines[1].text)
        assertEquals(1, lines[1].trace.size)
    }

    @Test
    fun aLineWithoutATraceContextIsNeverFolded() {
        val lines = feed(listOf(
            "[10:15:00] [Server thread/INFO]: Saved the game",
            "\tat knot//dev.example.Thing.run(Thing.java:1)",
            "[10:15:01] [Server thread/WARN]: Can't keep up!",
            "at the spawn point, plainly written",
        ))
        assertEquals(4, lines.size)
        assertEquals(Console.Kind.INFO, lines[1].kind)
        assertEquals(Console.Kind.INFO, lines[3].kind)
        assertEquals(0, lines[2].trace.size)
    }

    @Test
    fun javaExceptionHeadersWithoutAPrefixStartAnErrorAndTakeTheirFrames() {
        val lines = feed(listOf(
            "[04:39:50] [Server thread/ERROR]: Error upgrading chunk [1, -1] to \"minecraft:structure_starts\"",
            "java.util.concurrent.CompletionException: dev.phoenium.chunk.C2meGenerationControlException: C2ME generation branch ended with SHUTDOWN",
            "\tat java.base/java.util.concurrent.CompletableFuture.wrapInCompletionException(CompletableFuture.java:323)",
            "[04:39:51] [Server thread/INFO]: next",
            "Exception in thread \"main\" java.lang.IllegalStateException: boom",
            "\tat dev.example.Main.main(Main.java:9)",
        ))
        assertEquals(3, lines.size)
        assertEquals(Console.Kind.ERROR, lines[0].kind)
        assertEquals(2, lines[0].trace.size)
        assertEquals(Console.Kind.ERROR, lines[2].kind)
        assertEquals(1, lines[2].trace.size)
    }

    @Test
    fun theTraceOfOneEntryIsBoundedAndCountsWhatWasNotKept() {
        val frames = (1..500).map { "\tat dev.example.Frame$it(Frame.java:$it)" }
        val lines = feed(listOf("[04:39:50] [Server thread/ERROR]: Boom") + frames)
        assertEquals(1, lines.size)
        val entry = lines[0]
        assertEquals(500, entry.trace.size + entry.traceDropped)
        assertTrue(entry.traceDropped > 0)
        assertTrue(entry.trace.sumOf { it.length } <= Console.TRACE_CHARS)
    }

    @Test
    fun aVeryLongTraceLineIsCutAndMarked() {
        val lines = feed(listOf("[04:39:50] [Server thread/ERROR]: Boom", "\tat " + "x".repeat(2000)))
        val kept = lines[0].trace.single()
        assertTrue(kept.length <= 510)
        assertTrue(kept.endsWith("…"))
    }

    @Test
    fun serverStartShutdownAndSavesAreEventsAndDiagnosticsAreInternalLines() {
        assertEquals(Console.Kind.SERVER, Console.parse("[00:42:45] [Server thread/INFO]: Done (346.217s)! For help, type \"help\"").kind)
        assertEquals(Console.Kind.SERVER, Console.parse("[01:23:18] [Server thread/INFO]: Stopping the server").kind)
        assertEquals(Console.Kind.SERVER, Console.parse("[01:23:21] [Server thread/INFO]: Stopping server").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse("[00:42:55] [Server thread/INFO]: PHOENIUM_STAGE2 started lifecycle=RUNNING").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse("[00:42:55] [Server thread/INFO]: PHOENIUM_S8F6_CASCADE_TOTALS started cuts=0").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse("[00:42:46] [phoenix-metrics/INFO]: [STDOUT]: @@PHOENIX_METRICS {\"timestampMs\":1}").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse("[00:42:46] [Server thread/INFO]: Average time per tick: 3.9ms (Target: 50.0ms)").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse("[00:42:46] [Server thread/INFO]: Percentiles: P50: 3.1ms P95: 4.0ms").kind)
        // A warning or error from Minecraft or Phoenix is never a diagnostic line, whatever its label says.
        assertEquals(Console.Kind.WARN, Console.parse("[00:42:55] [Server thread/WARN]: PHOENIUM_MEM released late").kind)
        assertEquals(Console.Kind.ERROR, Console.parse("[00:42:55] [Server thread/ERROR]: PHOENIUM_MEM failed").kind)
        assertEquals(Console.Kind.JOIN, Console.parse("[10:13:52] [Server thread/INFO]: BlindingWulf joined the game").kind)
    }

    @Test
    fun thePlayitAgentKeepsItsWarningsAndErrorsAndHidesItsRoutineOutput() {
        val prefix = Console.PHOENIX_PREFIX
        assertEquals(Console.Kind.ERROR, Console.parse(prefix + "Playit agent: 2026-10-20T02:28:12.301648Z ERROR playit_agent_core::agent_control::address_selector: failed to ping tunnel server").kind)
        assertEquals(Console.Kind.WARN, Console.parse(prefix + "Playit agent: 2026-10-20T02:28:13.540593Z  WARN playit_agent_core::agent_control::maintained_control: control session expired").kind)
        assertEquals(Console.Kind.DIAGNOSTIC, Console.parse(prefix + "Playit agent: ::daemon: playit connected; tunnels loaded tunnel_count=3").kind)
        assertEquals(Console.Kind.PHOENIX, Console.parse(prefix + "Playit Java tunnel created for local port 25565").kind)
    }

    @Test
    fun theCopiedTextKeepsTheTraceAndHidesAddressesUnlessShown() {
        val lines = feed(listOf(
            "[04:39:50] [Server thread/WARN]: Steve[/203.0.113.7:51234] logged in",
            "\tat dev.example.Main.main(Main.java:9)",
        ))
        assertEquals(
            "[04:39:50] Steve[/•••] logged in\n\tat dev.example.Main.main(Main.java:9)",
            Console.plainText(lines[0], showAddresses = false),
        )
        assertTrue(Console.plainText(lines[0], showAddresses = true).contains("203.0.113.7:51234"))
    }
}
