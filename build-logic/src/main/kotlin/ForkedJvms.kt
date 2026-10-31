import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Memory for the JVMs a build forks beside its own, as two halves of room for one 3g fork: a fork
// that needs 3g leases both, one that fits in 1.5g leases one, so two small forks run side by side.
// Shared by name, so every project in a build queues on the same pair. -Dopenrsc.test.forks=N gives
// each half N leases, for a machine with room for more. This is the first half.
abstract class ForkedJvms : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "forkedJvms"
    }
}

// The second half.
abstract class ForkedJvmsSecondHalf : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "forkedJvmsSecondHalf"
    }
}

// Which halves a test fork leases, and the heap that then fits it (null: the task's own). A half is
// named, not picked at run time, because Gradle leases a named service: two forks meant to run side
// by side take different halves.
enum class ForkShare(val heap: String?) {
    WHOLE(null),
    FIRST_HALF("1536m"),
    SECOND_HALF("1536m"),
}

// Error Prone compiles, two at a time. Unbounded, they are the longest tasks and fill every worker
// while the compiles and tests the rest of the build waits for queue behind them.
abstract class ErrorproneChecks : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "errorproneChecks"
    }
}

// TeaVM compiles, one at a time: they run inside the build's own JVM, where two at once beside the
// other compiles run its heap out.
abstract class TeavmCompiles : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "teavmCompiles"
    }
}
