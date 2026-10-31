plugins {
    id("openrsc.oss")
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":client-render"))
    api(project(":game-data"))
    api(project(":nav"))
    api(libs.jackson.databind)
    api(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

// The Bake command-line tool as one self-contained jar, apart from the module's own jar.
tasks.shadowJar {
    archiveFileName = "world3d-bake.jar"
    // Target/world3d-bake.jar, where the scripts look.
    destinationDirectory = layout.buildDirectory
    manifest { attributes("Main-Class" to "openrsc.maprender.bake.Bake") }
    mergeServiceFiles()
    // Dependencies' module descriptors and jar signatures do not hold for the merged jar.
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.register<JavaExec>("run") {
    description = "Runs the bake."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "openrsc.maprender.bake.Bake"
    workingDir = rootDir
}
