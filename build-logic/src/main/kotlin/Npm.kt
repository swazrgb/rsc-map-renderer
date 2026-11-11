import com.github.gradle.node.NodeExtension
import com.github.gradle.node.npm.task.NpmTask
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/** Two npm processes at once, build-wide. */
abstract class NodeSlots : BuildService<BuildServiceParameters.None>

fun Project.nodeSlots() = gradle.sharedServices.registerIfAbsent("nodeSlots", NodeSlots::class.java) {
    maxParallelUsages.set(2)
}

val npmQuiet = mapOf(
    "npm_config_progress" to "false",
    "npm_config_update_notifier" to "false",
    "npm_config_fund" to "false",
    "npm_config_audit" to "false",
    "NODE_OPTIONS" to "--max-old-space-size=1024",
)

/** A package's sources, without what npm, Node.js, Vite or Gradle write into it. */
fun Project.npmSources(dir: File): FileTree = fileTree(dir) {
    exclude("node_modules/**", "node/**", "dist/**", ".vite/**", "target/**")
}

/** `npm ci` in `dir`. */
fun Project.npmCi(name: String, dir: File): TaskProvider<NpmTask> {
    val nodeVersion = extensions.getByType(NodeExtension::class.java).version
    val slots = nodeSlots()
    return tasks.register<NpmTask>(name) {
        description = "npm ci in ${dir.relativeTo(rootDir)}."
        usesService(slots)
        workingDir.set(dir)
        args.set(listOf("ci"))
        environment.putAll(npmQuiet)
        inputs.property("node", nodeVersion)
        inputs.files(File(dir, "package.json"), File(dir, "package-lock.json"))
            .withPropertyName("manifests").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(File(dir, "node_modules")).withPropertyName("nodeModules")
        outputs.cacheIf { false }
    }
}

/** The package's `npm run check` (prettier, eslint and tsc), over its sources and the other packages it compiles. */
fun Project.npmCheck(name: String, dir: File, vararg alsoReads: File): TaskProvider<NpmTask> {
    val nodeVersion = extensions.getByType(NodeExtension::class.java).version
    val slots = nodeSlots()
    val marker = layout.buildDirectory.file("checks/$name")
    return tasks.register<NpmTask>(name) {
        description = "prettier, eslint and tsc over ${dir.relativeTo(rootDir)}."
        usesService(slots)
        group = "verification"
        workingDir.set(dir)
        args.set(listOf("run", "--silent", "check"))
        environment.putAll(npmQuiet)
        inputs.property("node", nodeVersion)
        inputs.files(npmSources(dir), *alsoReads.map { npmSources(it) }.toTypedArray())
            .withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(marker).withPropertyName("marker")
        outputs.cacheIf { true }
        doLast { marker.get().asFile.writeText("passed\n") }
    }
}
