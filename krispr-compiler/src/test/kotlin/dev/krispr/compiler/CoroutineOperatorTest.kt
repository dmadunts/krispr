package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/** FLOW_EMIT, FLOW_OPERATOR, COROUTINE_CONTEXT, CATCH_SWALLOW and LAUNCH_BODY, run under `runBlocking`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoroutineOperatorTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            @file:OptIn(kotlinx.coroutines.FlowPreview::class)
            import kotlinx.coroutines.*
            import kotlinx.coroutines.channels.*
            import kotlinx.coroutines.flow.*

            fun next(calls: IntArray): Int { calls[0]++; return calls[0] }
            fun numbers(n: Int): List<Int> = runBlocking { flow { for (i in 0 until n) emit(i); emit(-1) }.toList() }
            fun sent(calls: IntArray): List<Int> = runBlocking { val ch = Channel<Int>(10); ch.send(next(calls)); ch.close(); ch.toList() }
            fun tried(calls: IntArray): List<Int> { val ch = Channel<Int>(10); ch.trySend(next(calls)); ch.close(); return runBlocking { ch.toList() } }
            fun peek(ch: Channel<Int>): Any = ch.trySend(1)
            fun seen(xs: List<Int>, sink: MutableList<Int>): List<Int> = runBlocking { xs.asFlow().onEach { sink.add(it) }.toList() }
            fun logged(xs: List<Int>): List<Int> = runBlocking { xs.asFlow().onEach { println(it) }.onStart { }.toList() }
            fun present(xs: List<Int?>): Int = runBlocking { xs.asFlow().filterNotNull().toList().sumOf { it } }
            fun recovered(): List<Int> = runBlocking { flow { emit(1); error("boom") }.catch { emit(-1) }.toList() }
            fun settled(calls: IntArray): List<Int> = runBlocking { flowOf(1, 2).debounce(next(calls).toLong()).toList() }
            fun named(calls: IntArray): String? = runBlocking { withContext(CoroutineName("n" + next(calls))) { coroutineContext[CoroutineName]?.name } }
            fun upstream(): List<String?> = runBlocking { flow { emit(currentCoroutineContext()[CoroutineName]?.name) }.flowOn(CoroutineName("up")).toList() }
            fun rethrown(x: Int): Int = try { check(x > 0); x } catch (e: IllegalStateException) { throw e }
            fun guarded(x: Int, log: MutableList<String>): Int = try { check(x > 0); x } catch (e: IllegalStateException) { if (x < -5) throw e; log.add("low"); -1 }
            fun unitRethrow(x: Int, log: MutableList<String>) { try { check(x > 0); log.add("ok") } catch (e: IllegalStateException) { log.add("bad"); throw e } }
            fun wrapped(x: Int): Int = try { check(x > 0); x } catch (e: IllegalStateException) { throw IllegalArgumentException(e) }
            fun cancelled(log: MutableList<String>): List<String> = runBlocking { val job = launch { try { delay(60_000) } catch (e: CancellationException) { throw e }; log.add("after") }; yield(); job.cancelAndJoin(); log }
            fun timedOut(): Int = runBlocking { try { withTimeout(1) { delay(60_000); 1 } } catch (e: TimeoutCancellationException) { throw e } }
            fun checked(x: Int): Int = runBlocking { try { check(x > 0); x } catch (e: Exception) { if (e is CancellationException) throw e; -1 } }
            fun ensured(x: Int): Int = runBlocking { try { check(x > 0); x } catch (e: IllegalStateException) { coroutineContext.ensureActive(); throw e } }
            fun plainBroadRethrow(x: Int): Int = try { check(x > 0); x } catch (e: Exception) { throw e }
            suspend fun suspendBroadRethrow(x: Int): Int = try { check(x > 0); x } catch (e: Exception) { throw e }
            fun lambdaBroadRethrow(x: Int): List<Int> = runBlocking { listOf(x).map { try { check(it > 0); it } catch (e: Exception) { throw e } } }
            fun dispatched(d: CoroutineDispatcher): List<Int> = runBlocking { listOf(withContext(Dispatchers.Default) { 1 }, withContext(d) { 2 }) + flowOf(3).flowOn(Dispatchers.IO).toList() + flowOf(4).flowOn(d.limitedParallelism(1)).toList() }
            fun combined(d: CoroutineDispatcher): String? = runBlocking { withContext(d + CoroutineName("c")) { coroutineContext[CoroutineName]?.name } }
            fun onDefault(combine: Boolean): Any? = if (combine) combined(Dispatchers.Default) else dispatched(Dispatchers.Default)
            fun launched(sink: MutableList<Int>): List<Int> = runBlocking { launch { sink.add(1); sink.add(2) }.join(); sink }
            fun record(sink: MutableList<Int>) { sink.add(3) }
            fun quiet(sink: MutableList<Int>): List<Int> = runBlocking { launch { println("x") }.join(); launch { delay(1); record(sink) }.join(); sink }
            """.trimIndent(),
            operators = listOf("DEFAULTS", "FLOW_EMIT", "FLOW_OPERATOR", "COROUTINE_CONTEXT", "CATCH_SWALLOW", "LAUNCH_BODY"),
        )
    }

    private fun of(operator: String, function: String): List<ManifestEntry> =
        compiled.mutantsOf(operator).filter { it.declaration.substringAfterLast('.').startsWith("$function(") || it.declaration.startsWith("$function(") }

    @Test
    fun flowEmitDropsEmitsAndSendsButEvaluatesTheirArgumentOnce() {
        val emits = of("FLOW_EMIT", "numbers").sortedBy { it.description }
        assertEquals(listOf("emit(-1) → (removed)", "emit(i) → (removed)"), emits.map { it.description })
        assertEquals(listOf(0, 1, -1), compiled.call("numbers", 2))
        assertEquals(listOf(-1), compiled.call("numbers", 2, activeId = emits[1].id))
        assertEquals(listOf(0, 1), compiled.call("numbers", 2, activeId = emits[0].id))
        // Emits are FLOW_EMIT's alone.
        assertEquals(emptyList<ManifestEntry>(), of("REMOVE_CALL", "numbers"))

        for (function in listOf("sent", "tried")) {
            val calls = IntArray(1)
            assertEquals(emptyList<Int>(), compiled.call(function, calls, activeId = of("FLOW_EMIT", function).single().id))
            assertEquals(1, calls[0])
            assertEquals(listOf(1), compiled.call(function, IntArray(1)))
        }
        // A `trySend` whose result is read is left alone.
        assertEquals(emptyList<ManifestEntry>(), of("FLOW_EMIT", "peek"))
    }

    @Test
    fun flowOperatorSkipsAnOperatorThatKeepsTheElementType() {
        val sink = mutableListOf<Int>()
        assertEquals(listOf(1, 2), compiled.call("seen", listOf(1, 2), sink))
        assertEquals(listOf(1, 2), sink)
        val onEach = of("FLOW_OPERATOR", "seen").single()
        assertEquals("xs.asFlow().onEach { sink.add(it) } → xs.asFlow()", onEach.description)
        val skipped = mutableListOf<Int>()
        assertEquals(listOf(1, 2), compiled.call("seen", listOf(1, 2), skipped, activeId = onEach.id))
        assertEquals(emptyList<Int>(), skipped)

        // Logging-only and empty side effects get no mutant.
        assertEquals(emptyList<ManifestEntry>(), of("FLOW_OPERATOR", "logged"))

        assertEquals(3, compiled.call("present", listOf(1, null, 2)))
        assertThrows<NullPointerException> { compiled.call("present", listOf(1, null, 2), activeId = of("FLOW_OPERATOR", "present").single().id) }

        assertEquals(listOf(1, -1), compiled.call("recovered"))
        assertThrows<IllegalStateException> { compiled.call("recovered", activeId = of("FLOW_OPERATOR", "recovered").single().id) }
    }

    @Test
    fun flowOperatorEvaluatesItsArgumentsOnce() {
        val calls = IntArray(1)
        assertEquals(listOf(2), compiled.call("settled", calls))
        assertEquals(1, calls[0])
        assertEquals(listOf(1, 2), compiled.call("settled", calls, activeId = of("FLOW_OPERATOR", "settled").single().id))
        assertEquals(2, calls[0])
    }

    @Test
    fun coroutineContextRunsTheBlockInTheCallersContext() {
        val calls = IntArray(1)
        assertEquals("n1", compiled.call("named", calls))
        val switch = of("COROUTINE_CONTEXT", "named").single()
        assertEquals("withContext(CoroutineName(\"n\" + next(calls))) → (caller's context)", switch.description)
        assertEquals(null, compiled.call("named", calls, activeId = switch.id))
        assertEquals(2, calls[0])

        assertEquals(listOf("up"), compiled.call("upstream"))
        assertEquals(listOf(null), compiled.call("upstream", activeId = of("COROUTINE_CONTEXT", "upstream").single().id))

        // A dispatcher alone is invisible under a test dispatcher: no mutant. A context with more in it has one.
        assertEquals(emptyList<ManifestEntry>(), of("COROUTINE_CONTEXT", "dispatched"))
        assertEquals(listOf(1, 2, 3, 4), compiled.call("onDefault", false))
        val combined = of("COROUTINE_CONTEXT", "combined").single()
        assertEquals("c", compiled.call("onDefault", true))
        assertEquals(null, compiled.call("onDefault", true, activeId = combined.id))
    }

    @Test
    fun catchSwallowTurnsARethrowIntoTheDefault() {
        assertThrows<IllegalStateException> { compiled.call("rethrown", 0) }
        val rethrow = of("CATCH_SWALLOW", "rethrown").single()
        assertEquals("throw e → (swallowed) 0", rethrow.description)
        assertEquals(0, compiled.call("rethrown", 0, activeId = rethrow.id))
        assertEquals(4, compiled.call("rethrown", 4, activeId = rethrow.id))

        // A rethrow in an `if` falls through to the rest of the catch.
        val log = mutableListOf<String>()
        assertThrows<IllegalStateException> { compiled.call("guarded", -9, log) }
        assertEquals(-1, compiled.call("guarded", -9, log, activeId = of("CATCH_SWALLOW", "guarded").single().id))
        assertEquals(listOf("low"), log)

        val unit = mutableListOf<String>()
        compiled.call("unitRethrow", 0, unit, activeId = of("CATCH_SWALLOW", "unitRethrow").single().id)
        assertEquals(listOf("bad"), unit)

        // Throwing something else is not a rethrow.
        assertEquals(emptyList<ManifestEntry>(), of("CATCH_SWALLOW", "wrapped"))
    }

    @Test
    fun catchSwallowLeavesCancellationRethrowsAlone() {
        // Swallowing these would let a cancelled coroutine carry on.
        for (function in listOf("cancelled", "timedOut", "checked", "ensured")) {
            assertEquals(emptyList<ManifestEntry>(), of("CATCH_SWALLOW", function), function)
        }
        assertEquals(emptyList<String>(), compiled.call("cancelled", mutableListOf<String>()))
        assertEquals(-1, compiled.call("checked", 0))
        assertThrows<IllegalStateException> { compiled.call("ensured", 0) }
    }

    @Test
    fun catchSwallowSkipsABroadCatchOnlyInSuspendCode() {
        // Outside suspend code a broad catch is CATCH_SWALLOW's as usual.
        assertThrows<IllegalStateException> { compiled.call("plainBroadRethrow", 0) }
        val plain = of("CATCH_SWALLOW", "plainBroadRethrow").single()
        assertEquals(0, compiled.call("plainBroadRethrow", 0, activeId = plain.id))
        assertEquals(4, compiled.call("plainBroadRethrow", 4, activeId = plain.id))

        // Inside a suspend fun, `catch (e: Exception) { throw e }` also rethrows cancellation: no mutant.
        assertEquals(emptyList<ManifestEntry>(), of("CATCH_SWALLOW", "suspendBroadRethrow"))

        // A non-suspend lambda (map's) nested in a suspend lambda (runBlocking's) is treated as still-suspend
        // code, the same way a lambda passed to a scope function is transparent for inInitialization: no mutant.
        assertEquals(emptyList<ManifestEntry>(), of("CATCH_SWALLOW", "lambdaBroadRethrow"))
        assertEquals(listOf(1), compiled.call("lambdaBroadRethrow", 1))
    }

    @Test
    fun launchBodyIsSkippedButTheJobStillCompletes() {
        assertEquals(listOf(1, 2), compiled.call("launched", mutableListOf<Int>()))
        val skip = of("LAUNCH_BODY", "launched").single()
        assertEquals("launch { … } → (body skipped)", skip.description)
        assertEquals(emptyList<Int>(), compiled.call("launched", mutableListOf<Int>(), activeId = skip.id))
        // Logging-only bodies, and a single call REMOVE_CALL removes, get none.
        assertEquals(emptyList<ManifestEntry>(), of("LAUNCH_BODY", "quiet"))
        assertTrue(of("REMOVE_CALL", "quiet").isNotEmpty())
        assertEquals(listOf(3), compiled.call("quiet", mutableListOf<Int>()))
    }
}
