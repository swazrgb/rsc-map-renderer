plugins {
    id("openrsc.oss")
}

dependencies {
    api(project(":game-data"))
    api(project(":account-data"))
    api(project(":bot-api"))
    api(project(":nav"))
    api(libs.netty.buffer)
    api(libs.fastutil.core)
    api(libs.slf4j.api)
    api(libs.jackson.databind)
    compileOnly(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
}
