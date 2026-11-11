import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer

plugins {
    id("openrsc.oss")
    alias(libs.plugins.shadow)
}

val livePage = configurations.dependencyScope("livePage")
val livePageFiles = configurations.resolvable("livePageFiles") { extendsFrom(livePage.get()) }

dependencies {
    api(project(":game-client"))
    implementation(project(":live-map"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    compileOnly(libs.jetbrains.annotations)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.junit.jupiter)

    "livePage"(project(":live-map-web", "staticBundle"))
}

// The live map's page, served from the classpath's static/.
tasks.processResources {
    from(livePageFiles) { into("static") }
}

// The proxy as one self-contained jar, apart from the module's own jar.
tasks.shadowJar {
    archiveFileName = "session-proxy.jar"
    destinationDirectory = layout.buildDirectory
    manifest { attributes("Main-Class" to "openrsc.sessionproxy.SessionProxyMain") }
    // Spring's modules each list what they contribute in files of the same name, which the merged jar
    // holds one of: each is the modules' lists joined.
    val springLists = listOf(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
        "META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports",
        "META-INF/spring.handlers",
        "META-INF/spring.schemas",
    )
    val springProperties = listOf(
        "META-INF/spring.factories",
        "META-INF/spring/aot.factories",
        "META-INF/spring-autoconfigure-metadata.properties",
    )
    filesMatching(listOf("META-INF/services/**") + springLists + springProperties) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    springLists.forEach { append(it) }
    transform(PropertiesFileTransformer::class.java) {
        paths.addAll(springProperties)
        mergeStrategy = PropertiesFileTransformer.MergeStrategy.Append
    }
    // Dependencies' module descriptors and jar signatures do not hold for the merged jar.
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.register<JavaExec>("run") {
    description = "Runs the session proxy; pass its options with --args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "openrsc.sessionproxy.SessionProxyMain"
    workingDir = rootDir
}
