plugins {
    id("openrsc.oss")
    // Viewer, a stream client on a fake socket, for the tests of modules that feed the stream.
    `java-test-fixtures`
}

dependencies {
    api(project(":game-client"))
    api(project(":map2d"))
    api(project(":world3d-bake"))
    api(libs.spring.boot.starter.web) { exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging") }
    api(libs.spring.boot.starter.websocket) { exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging") }
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":nav")))
    testFixturesImplementation(enforcedPlatform(libs.spring.boot.bom))
    testFixturesImplementation(libs.junit.jupiter)
}

// Spring binds path variables and request parameters by the names of the parameters they annotate.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
