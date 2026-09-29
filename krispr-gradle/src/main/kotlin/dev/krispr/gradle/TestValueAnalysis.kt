package dev.krispr.gradle

/**
 * The inverse of the mutant-by-mutant report: per test, does it protect anything? Built from
 * [MutantReport.killers] (every test confirmed to have killed a mutant) and [MutantReport.testedTests]
 * (the tests a verdict is real evidence about). Without [ReportSummary.killMatrix], a KILLED mutant's
 * evidence stops at its first confirmed killer — the rest of its covering tests, later in priority order,
 * never ran against it — so unique-kill, redundancy and minimal-set questions need the kill matrix; only
 * "covers N, kills 0" and per-test kill counts hold up on first-kill data alone.
 */
internal object TestValueAnalysis {
    class TestStat internal constructor(val name: String) {
        /** Mutants this test is real evidence about (it ran and either passed or was the confirmed killer). */
        var covers: Int = 0
            internal set
        internal val killedIds = HashSet<Int>()
        var millis: Long? = null
            internal set
        val kills: Int get() = killedIds.size
    }

    class Result(
        val killMatrix: Boolean,
        /** KILLED mutants with at least one confirmed killer, i.e. attributable to specific tests. */
        val attributedKills: Int,
        /** Killed mutants (TIMED_OUT, MEMORY_ERROR, or a KILLED with no recorded killer) with no such test. */
        val unattributedKills: Int,
        val stats: List<TestStat>,
        /** Tests that cover at least one mutant but are not the confirmed killer of any. */
        val noKills: List<TestStat>,
        /** Test name to how many of its kills no other single test also kills; [killMatrix] only. */
        val uniqueKillCounts: Map<String, Int>,
        /** (test, other test) where every mutant the first kills is also killed by the other; [killMatrix] only. */
        val subsumedByOneTest: List<Pair<String, String>>,
        /** Tests whose kills are each also killed by some other test, but no single one covers all of them. */
        val subsumedByRest: List<String>,
        /** A greedy minimal set of tests that kills the same mutants as every test that kills at least one; [killMatrix] only. */
        val minimalSet: List<String>?,
        /** Summed recorded time of [minimalSet], null unless every chosen test has a recorded time. */
        val minimalSetMillis: Long?,
        /** Summed recorded time of every test that kills at least one mutant, null unless all have a recorded time. */
        val fullMillis: Long?,
    )

    fun analyze(report: Report): Result {
        val byName = LinkedHashMap<String, TestStat>()
        fun stat(name: String) = byName.getOrPut(name) { TestStat(name) }

        var attributed = 0
        var unattributed = 0
        val killedTier = setOf(MutantStatus.KILLED, MutantStatus.TIMED_OUT, MutantStatus.MEMORY_ERROR)
        for (m in report.mutants) {
            when {
                m.status == MutantStatus.SURVIVED -> for (t in m.tests) stat(t).covers++
                m.status == MutantStatus.KILLED && m.killers.isNotEmpty() -> {
                    attributed++
                    for (t in m.testedTests) stat(t).covers++
                    for (k in m.killers) stat(k).killedIds += m.id
                }
                m.status in killedTier -> unattributed++
                else -> {}
            }
        }
        for ((name, millis) in report.testTimes) byName[name]?.millis = millis

        val stats = byName.values.sortedBy { it.name }
        val noKills = stats.filter { it.covers > 0 && it.kills == 0 }
        val withKills = stats.filter { it.kills > 0 }

        val killMatrix = report.summary.killMatrix
        val uniqueKillCounts: Map<String, Int>
        val subsumedByOneTest: List<Pair<String, String>>
        val subsumedByRest: List<String>
        val minimalSet: List<String>?
        val minimalSetMillis: Long?
        val fullMillis: Long?

        if (killMatrix && withKills.isNotEmpty()) {
            val killerCounts = report.mutants.filter { it.status == MutantStatus.KILLED }.associate { it.id to it.killers.size }
            uniqueKillCounts = withKills.associate { s -> s.name to s.killedIds.count { id -> (killerCounts[id] ?: 0) == 1 } }

            val subsumed = mutableListOf<Pair<String, String>>()
            for (t in withKills) {
                // Two tests with the exact same kill set each satisfy containsAll for the other; report
                // only one direction, with the alphabetically first name kept as the subsumer.
                val subsumer = withKills.filter { other ->
                    other.name != t.name && other.killedIds.containsAll(t.killedIds) &&
                        (other.killedIds.size > t.killedIds.size || other.name < t.name)
                }.minByOrNull { it.name }
                if (subsumer != null) subsumed += t.name to subsumer.name
            }
            subsumedByOneTest = subsumed
            val subsumedNames = subsumed.map { it.first }.toSet()
            subsumedByRest = withKills.filter { t ->
                t.name !in subsumedNames && t.killedIds.all { id -> withKills.any { other -> other.name != t.name && id in other.killedIds } }
            }.map { it.name }

            val remaining = withKills.flatMapTo(HashSet()) { it.killedIds }
            val pool = withKills.toMutableList()
            val chosen = mutableListOf<String>()
            while (remaining.isNotEmpty()) {
                val best = pool.minWithOrNull(compareBy({ -(it.killedIds intersect remaining).size }, { it.name })) ?: break
                val newlyKilled = best.killedIds intersect remaining
                if (newlyKilled.isEmpty()) break
                chosen += best.name
                remaining -= newlyKilled
                pool -= best
            }
            minimalSet = chosen
            minimalSetMillis = chosen.map { byName.getValue(it).millis }.let { ms -> if (ms.all { it != null }) ms.sumOf { it!! } else null }
        } else {
            uniqueKillCounts = emptyMap()
            subsumedByOneTest = emptyList()
            subsumedByRest = emptyList()
            minimalSet = null
            minimalSetMillis = null
        }
        val relevant = stats.filter { it.covers > 0 }
        fullMillis = relevant.map { it.millis }.let { ms -> if (ms.isNotEmpty() && ms.all { it != null }) ms.sumOf { it!! } else null }

        return Result(
            killMatrix = killMatrix, attributedKills = attributed, unattributedKills = unattributed, stats = stats,
            noKills = noKills, uniqueKillCounts = uniqueKillCounts, subsumedByOneTest = subsumedByOneTest,
            subsumedByRest = subsumedByRest, minimalSet = minimalSet, minimalSetMillis = minimalSetMillis, fullMillis = fullMillis,
        )
    }
}
