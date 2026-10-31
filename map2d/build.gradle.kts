plugins {
    id("openrsc.oss")
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":game-data"))
    api(libs.slf4j.api)
    api(libs.jackson.databind)
    api(libs.commons.compress)
    runtimeOnly(libs.logback.classic)
}

// The command-line tool as one self-contained jar, apart from the module's own jar.
tasks.shadowJar {
    archiveFileName = "rsc-map-renderer.jar"
    // Target/rsc-map-renderer.jar, where the scripts look.
    destinationDirectory = layout.buildDirectory
    manifest { attributes("Main-Class" to "openrsc.map.MapRendererMain") }
    mergeServiceFiles()
    // Dependencies' module descriptors and jar signatures do not hold for the merged jar.
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.register<JavaExec>("run") {
    description = "Runs the map renderer."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "openrsc.map.MapRendererMain"
    workingDir = rootDir
}
