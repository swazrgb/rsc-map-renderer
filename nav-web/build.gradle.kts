// nav in the browser: TeaVM compiles openrsc.navweb.NavWeb, and nav under it, to a WebAssembly GC
// module, target/nav-web/nav.wasm, beside the JS runtime that loads it.
plugins {
    id("openrsc.oss")
    alias(libs.plugins.teavm)
}

openrscJava {
    release = 25
}

dependencies {
    // Before the classlib: its classes replace TeaVM classlib classes of the same name only while
    // they are found first.
    implementation(project(":teavm-classlib-patch"))
    implementation(project(":nav"))
    compileOnly(libs.teavm.classlib)
    compileOnly(libs.teavm.jso)
    compileOnly(libs.teavm.extension.spi)
}

// The TeaVM plugin adds its SPI annotation processor, which would write service files beside the
// hand-written ones in META-INF/services.
configurations.named("annotationProcessor") {
    exclude(group = "org.teavm", module = "teavm-extension-annotation-processor")
}

teavm {
    wasmGC {
        mainClass = "openrsc.navweb.NavWeb"
        targetFileName = "nav.wasm"
        optimization = org.teavm.gradle.api.OptimizationLevel.AGGRESSIVE
        // The plugin writes into <outputDir>/<relativePathInOutputDir>: target/nav-web/nav.wasm.
        outputDir = layout.buildDirectory
        relativePathInOutputDir = "nav-web"
        // Strict mode compiles in bounds and null checks, for a larger module that throws where this
        // one does not.
        strict = false
        // The plugin's runtime is named after the module; wasmRuntime below copies the one the viewer
        // loads by its own name.
        copyRuntime = false
    }
}

val teavmCore = configurations.dependencyScope("teavmCore")
val teavmCoreJar = configurations.resolvable("teavmCoreJar") {
    extendsFrom(teavmCore.get())
    isTransitive = false
}
dependencies { "teavmCore"("org.teavm:teavm-core:${libs.versions.teavm.get()}") }

// wasm-gc-module-runtime.js, the JS side of a WebAssembly GC module, which ships inside teavm-core.
val wasmRuntime = tasks.register<Copy>("wasmRuntime") {
    from(teavmCoreJar.map { zipTree(it.singleFile) }) {
        include("org/teavm/backend/wasm/wasm-gc-module-runtime.js")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("nav-web"))
}

// Cacheable, which the plugin does not mark it, and without sourceFiles: the plugin declares the
// source directories by absolute path, for source maps this module does not write, which gives every
// checkout a cache key of its own.
tasks.matching { it.name == "generateWasmGC" }.configureEach {
    outputs.cacheIf { true }
    (this as org.teavm.gradle.tasks.GenerateWasmGCTask).sourceFiles.setFrom()
}

tasks.assemble { dependsOn("generateWasmGC", wasmRuntime) }
