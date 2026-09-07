package com.foodtracker.android.perf

import java.io.File
import kotlin.system.measureNanoTime

/**
 * A deliberately small measurement helper for the JVM-side performance tests.
 *
 * This is not JMH — there is no forked JVM, no dead-code-elimination guard beyond consuming the
 * result, and no attempt at statistical rigour. It exists because the interesting numbers here
 * (the cost of filtering and sorting a pantry) differ by multiples, not by percent, and a
 * warm-up phase plus a median over many iterations resolves that reliably while running as an
 * ordinary unit test.
 *
 * Results are appended to `perf/raw/jvm-<label>.tsv`, where `<label>` comes from the
 * `perf.label` system property (see scripts/perf.sh). Comparison between two labels is done by
 * the script, not here: these tests deliberately assert nothing about wall-clock time, so they
 * can never fail because a machine was busy.
 */
object PerfHarness {

    private const val WARMUP_ITERATIONS = 25
    private const val MEASURED_ITERATIONS = 100

    private val label: String = System.getProperty("perf.label") ?: "adhoc"

    private val outputFile: File by lazy {
        // Tests run with the module dir as CWD; the report belongs at the repo root.
        val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
            if (cwd.name == "androidApp") cwd.parentFile else cwd
        }
        File(repoRoot, "perf/raw").apply { mkdirs() }
            .resolve("jvm-$label.tsv")
            .also { file ->
                if (!file.exists()) {
                    file.writeText("benchmark\tsize\tmedian_us\tp90_us\tmin_us\n")
                }
            }
    }

    /**
     * Runs [block] [WARMUP_ITERATIONS] times to let the JIT settle, then [MEASURED_ITERATIONS]
     * times for real, and records the median and P90.
     *
     * [reset] runs before every iteration and is *not* measured — use it to undo whatever the
     * measured block mutated, so iteration 2 does the same work as iteration 1.
     */
    fun measure(
        name: String,
        size: Int,
        reset: () -> Unit = {},
        block: () -> Any?
    ) {
        repeat(WARMUP_ITERATIONS) {
            reset()
            consume(block())
        }

        val samples = LongArray(MEASURED_ITERATIONS)
        repeat(MEASURED_ITERATIONS) { index ->
            reset()
            var result: Any? = null
            samples[index] = measureNanoTime { result = block() }
            consume(result)
        }
        samples.sort()

        val medianUs = samples[samples.size / 2] / 1_000.0
        val p90Us = samples[(samples.size * 90) / 100] / 1_000.0
        val minUs = samples.first() / 1_000.0

        outputFile.appendText(
            "%s\t%d\t%.1f\t%.1f\t%.1f%n".format(name, size, medianUs, p90Us, minUs)
        )
        println("[perf] %-34s n=%-5d median=%8.1fµs  p90=%8.1fµs".format(name, size, medianUs, p90Us))
    }

    /** Keeps the JIT from eliminating the measured work as unused. */
    private fun consume(value: Any?) {
        if (value is Collection<*> && value.size == Int.MIN_VALUE) error("unreachable")
        blackhole = value
    }

    @Volatile
    private var blackhole: Any? = null
}
