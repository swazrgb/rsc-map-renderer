plugins {
    `kotlin-dsl`
}

group = "openrsc.build"

dependencies {
    implementation(libs.gradle.errorprone)
}
