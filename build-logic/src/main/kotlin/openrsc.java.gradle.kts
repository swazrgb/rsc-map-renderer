// The conventions every Java module here is built by.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
}

interface OpenrscJavaExtension {
    // The class-file level: 26, or 25 for a module a TeaVM build reads, the most TeaVM 0.15.0 reads.
    val release: Property<Int>

    // Error Prone flags of the module's own, after the shared ones.
    val errorproneArgs: ListProperty<String>

    // How much of the forked-JVM memory the module's `test` task takes; its other test tasks take it all.
    val testFork: Property<ForkShare>
}

val openrscJava = extensions.create<OpenrscJavaExtension>("openrscJava")
openrscJava.release.convention(26)
openrscJava.testFork.convention(ForkShare.WHOLE)

// Nullable only because compiling a convention plugin that applies this one applies it to a
// synthetic project with no catalog.
val libs: VersionCatalog? = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)
fun lib(alias: String) = libs!!.findLibrary(alias).get()

group = "openrsc.bot"
version = "0.1.0-SNAPSHOT"

// target/, where the tests write their traces and logs and the scripts look for the jars.
layout.buildDirectory = layout.projectDirectory.dir("target")

if (libs != null) dependencies {
    // Enforced, so the BOM's version wins even over a newer one a transitive dependency asks for; a
    // plain platform() only recommends it.
    "implementation"(enforcedPlatform(lib("spring-boot-bom")))
    "annotationProcessor"(enforcedPlatform(lib("spring-boot-bom")))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
    "errorprone"(lib("errorprone-core"))
    "errorprone"(lib("errorprone-contrib"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = openrscJava.release
    // Any warning fails the compile. -Dopenrsc.werror=false lets them through, to see every finding
    // at once: under -Werror, Error Prone stops at its first.
    val werror = (System.getProperty("openrsc.werror") ?: "true").toBoolean()
    options.compilerArgs.addAll(listOfNotNull("-Werror".takeIf { werror }, "-Xlint:deprecation,unchecked", "-Xmaxwarns", "100000"))

    options.errorprone {
        // Error Prone needs this on JDK 21 and later.
        errorproneArgs.add("-XDaddTypeAnnotationsToSymbol=true")
        val refasterRules = providers.gradleProperty("refaster.rules").orNull
        if (refasterRules != null) {
            // A Refaster patch pass, with every check off so it runs on a tree that does not yet pass
            // them. IN_PLACE rewrites the sources; -Prefaster.location=<dir> writes a patch there instead.
            val location = providers.gradleProperty("refaster.location").getOrElse("IN_PLACE")
            errorproneArgs.addAll(
                "-XepDisableAllChecks",
                "-XepPatchChecks:refaster:$refasterRules",
                "-XepPatchLocation:$location",
            )
        } else {
            errorproneArgs.addAll(SHARED_ERRORPRONE_ARGS)
            errorproneArgs.addAll(openrscJava.errorproneArgs)
        }
    }
}

// Error Prone runs as a task of its own per source set, errorprone<SourceSet>, which compiles the
// sources again into an output nothing reads; `check` depends on it. Error Prone takes several times
// as long as javac, and this way the compile everything downstream waits for runs without it.
if (providers.gradleProperty("refaster.rules").orNull == null) {
    val errorproneChecks = gradle.sharedServices.registerIfAbsent(ErrorproneChecks.NAME, ErrorproneChecks::class) {
        maxParallelUsages = 2
    }
    the<SourceSetContainer>().configureEach {
        val sourceSet = this
        tasks.named<JavaCompile>(compileJavaTaskName) { options.errorprone.enabled = false }
        val errorprone = tasks.register<JavaCompile>(getTaskName("errorprone", null).let {
            if (it == "errorprone") "errorproneMain" else it
        }) {
            description = "Error Prone over the ${sourceSet.name} sources."
            group = "verification"
            usesService(errorproneChecks)
            source(sourceSet.java)
            classpath = sourceSet.compileClasspath
            options.annotationProcessorPath = sourceSet.annotationProcessorPath
            destinationDirectory = layout.buildDirectory.dir("errorprone/${sourceSet.name}")
            // Under target/generated/, which a module's -XepExcludedPaths matches as generated code.
            options.generatedSourceOutputDirectory = layout.buildDirectory.dir("generated/errorprone/${sourceSet.name}")
            options.errorprone.enabled = true
            // The plugin marks only the source set's own compile as test code, which some checks read.
            options.errorprone.compilingTestOnlyCode =
                tasks.named<JavaCompile>(compileJavaTaskName).flatMap { it.options.errorprone.compilingTestOnlyCode }
            val enabled = options.errorprone.enabled
            onlyIf("Error Prone is enabled for this module") { enabled.get() }
        }
        tasks.named("check") { dependsOn(errorprone) }
    }
}

// Reproducible archives: two builds of one commit produce the same bytes.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
}

// At most one 3g test fork's worth of forked JVMs at a time, across the whole build (ForkedJvms).
val forks = (System.getProperty("openrsc.test.forks") ?: "1").toInt()
val forkedJvms = gradle.sharedServices.registerIfAbsent(ForkedJvms.NAME, ForkedJvms::class) {
    maxParallelUsages = forks
}
val forkedJvmsSecondHalf = gradle.sharedServices.registerIfAbsent(ForkedJvmsSecondHalf.NAME, ForkedJvmsSecondHalf::class) {
    maxParallelUsages = forks
}

// Files the tests read that are not on a classpath, declared as inputs so a cached result is not
// reused after they change: the OpenRSC checkout's game data, found as ServerConf finds it, or
// wherever -D or the environment points ServerConf instead.
val openrscCheckout: File? = generateSequence(rootDir) { it.parentFile }
    .map { File(it, "openrsc") }
    .firstOrNull { File(it, "server/conf/server/defs").isDirectory }
val gameData: List<File> = (openrscCheckout?.let { o ->
    listOf("server/conf/server/defs", "server/conf/server/data", "server/src/com/openrsc/server",
        "server/plugins", "Client_Base/Cache")
        .map { File(o, it) } +
        (File(o, "server").listFiles { f -> f.isFile && f.name.endsWith(".conf") }?.toList() ?: emptyList())
} ?: emptyList()) +
    listOfNotNull(
        System.getProperty("openrsc.serverConfDir") ?: System.getenv("OPENRSC_SERVER_CONF"),
        System.getProperty("openrsc.clientCacheDir") ?: System.getenv("OPENRSC_CLIENT_CACHE"),
    ).map(::File)

tasks.withType<Test>().configureEach {
    inputs.files(gameData).withPropertyName("openrscGameData").withPathSensitivity(PathSensitivity.RELATIVE)
    // A test may read the module's own sources as text.
    inputs.files(fileTree("src/main/java")).withPropertyName("ownSources").withPathSensitivity(PathSensitivity.RELATIVE)
    useJUnitPlatform()
    // Gradle's default of 512m is too small for nav's tests.
    maxHeapSize = "3g"
    val parallelism = System.getProperty("openrsc.test.parallelism") ?: "4"
    // Test classes run side by side; a class's own methods run in order.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism)
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", parallelism)


    // Extra JVM flags for the fork, such as a flight recording.
    System.getProperty("openrsc.test.jvmArgs")?.takeIf { it.isNotBlank() }?.let {
        jvmArgs(it.trim().split(Regex("\\s+")))
    }

    // -Dtest replaces the module's includes, so it can also run a *Bench or *Probe class by hand.
    val selected = surefireSelectors(System.getProperty("test"))
    for (pattern in selected) {
        filter.includeTestsMatching(pattern)
    }
    // A *Probe or *Bench is a measurement run by hand, not a check.
    if (selected.isEmpty() && name == "test") {
        include("**/*Test.class")
    }

    // Every -D on the command line reaches the test JVM, last so it overrides the defaults above.
    gradle.startParameter.systemPropertiesArgs
        .filterKeys { !it.startsWith("org.gradle.") }
        .forEach { (k, v) -> systemProperty(k, v) }
}

// After evaluation, because a module's build file may set testFork after the task is configured.
afterEvaluate {
    tasks.withType<Test>().configureEach {
        val share = if (name == "test") openrscJava.testFork.get() else ForkShare.WHOLE
        if (share != ForkShare.SECOND_HALF) usesService(forkedJvms)
        if (share != ForkShare.FIRST_HALF) usesService(forkedJvmsSecondHalf)
        share.heap?.let { maxHeapSize = it }
    }
}

// Each project's gradle.lockfile pins every version its classpaths, annotation processors and Error
// Prone resolve to, so changing one version shows every transitive version it moves. After changing
// a dependency: ./gradlew resolveAndLockAll --write-locks
dependencyLocking {
    lockMode = LockMode.STRICT
}

val lockedClasspaths = setOf(
    "compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath",
    "annotationProcessor", "testAnnotationProcessor",
)
configurations.matching { it.name in lockedClasspaths }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

tasks.register("resolveAndLockAll") {
    description = "Resolves the locked classpaths; with --write-locks, rewrites gradle.lockfile."
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    val locked = configurations.matching { it.name in lockedClasspaths && it.isCanBeResolved }
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
    }
    doLast { locked.forEach { it.resolve() } }
}
