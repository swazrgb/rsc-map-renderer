// The conventions of this repo's modules: openrsc.java, plus Javadoc linting and test timeouts.
plugins {
    id("openrsc.java")
}

// Javadoc is written only where it adds something, so an undocumented member or @param is not a defect.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

// A test still running after this long is a hang: it fails, with every thread's stack printed first.
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.execution.timeout.default", System.getProperty("openrsc.test.timeout") ?: "2 m")
    systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
}
