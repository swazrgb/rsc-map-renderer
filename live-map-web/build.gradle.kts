import com.github.gradle.node.npm.task.NpxTask

plugins {
    id("npm-packages")
}

// The 3D viewer is compiled from source into this package's page, so its packages are installed and
// checked here too.
val viewer = file("../viewer")

val viewerCi = npmCi("viewerNpmCi", viewer)
val ci = npmCi("npmCi", projectDir)

val checkViewer = npmCheck("checkViewer", viewer)
checkViewer { dependsOn(viewerCi) }
val checkPage = npmCheck("checkPage", projectDir, viewer)
checkPage { dependsOn(viewerCi, ci) }

val nodeVersion = node.version
val bundleDir = layout.buildDirectory.dir("static")

val bundle = tasks.register<NpxTask>("bundle") {
    description = "The live map page's production bundle, which session-proxy-core serves as its static/ resources."
    usesService(nodeSlots())
    group = "build"
    dependsOn(viewerCi, ci, checkViewer, checkPage)
    workingDir = projectDir
    command = "vite"
    args = listOf("build")
    environment.putAll(npmQuiet)
    environment.put("STATIC_OUT", bundleDir.map { it.asFile.relativeTo(projectDir).path })
    inputs.property("node", nodeVersion)
    inputs.files(npmSources(projectDir), npmSources(viewer))
        .withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(bundleDir).withPropertyName("bundle")
    outputs.cacheIf { true }
}

tasks.check { dependsOn(checkViewer, checkPage) }
tasks.assemble { dependsOn(bundle) }

configurations.consumable("staticBundle")
artifacts {
    add("staticBundle", bundleDir) { builtBy(bundle) }
}
