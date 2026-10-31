// JMH benchmarks for nav. Run them from the jar: java -jar nav-bench/target/benchmarks.jar
plugins {
    id("openrsc.oss")
    alias(libs.plugins.shadow)
}

openrscJava {
    // The harness JMH's annotation processor generates is not ours to fix.
    errorproneArgs.add("-XepExcludedPaths:.*/target/generated/.*")
    // Its tests fit in half the forked-JVM memory.
    testFork = ForkShare.SECOND_HALF
}

dependencies {
    api(project(":nav"))
    api(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
    testImplementation(libs.junit.jupiter)
}

tasks.shadowJar {
    archiveFileName = "benchmarks.jar"
    // Target/benchmarks.jar.
    destinationDirectory = layout.buildDirectory
    manifest { attributes("Main-Class" to "org.openjdk.jmh.Main") }
    mergeServiceFiles()
    // Signatures of signed dependency jars do not hold for the merged jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
