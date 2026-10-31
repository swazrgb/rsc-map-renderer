plugins {
    id("openrsc.oss")
}

openrscJava {
    // Read by nav-web's TeaVM build.
    release = 25
}

dependencies {
    api(project(":game-data"))
    api(project(":account-data"))
    api(project(":bot-api"))
    api(libs.fastutil.core)
    api(libs.caffeine)
    api(libs.slf4j.api)
    api(libs.jackson.annotations)
    compileOnly(libs.jetbrains.annotations)
    testCompileOnly(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
}
