plugins {
    id("openrsc.oss")
}

openrscJava {
    // Read by nav-web's TeaVM build.
    release = 25
    // The server's id registries, copied verbatim: a finding in them is upstream's.
    errorproneArgs.add("-XepExcludedPaths:.*/openrsc/gamedata/data/(ItemId|NpcId|SceneryId|BoundaryId)\\.java")
}

dependencies {
    api(libs.slf4j.api)
    api(libs.jackson.databind)
    api(libs.commons.compress)
    testImplementation(libs.junit.jupiter)
}
