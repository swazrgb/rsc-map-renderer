plugins {
    id("openrsc.oss")
}

openrscJava {
    // Read by nav-web's TeaVM build.
    release = 25
}

dependencies {
    api(project(":game-data"))
    api(libs.fastutil.core)
    testImplementation(libs.junit.jupiter)
}
