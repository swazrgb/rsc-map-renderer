// Replacements for TeaVM classlib classes that nav needs and 0.15.0 lacks or gets wrong. They
// shadow the originals, so on nav-web's TeaVM classpath this module has to come before
// teavm-classlib itself.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("openrsc.oss")
}

openrscJava {
    release = 25 // TeaVM 0.15.0 reads class files up to 25
}

dependencies {
    compileOnly(libs.teavm.classlib)
}

// Not under Error Prone. These are TeaVM's own classlib classes, patched, and follow TeaVM's code:
// what Error Prone finds in them (MathAbsoluteNegative, in the hashing) is upstream's, and fixing
// it would change how the browser build of nav hashes. javac's own warnings still fail the compile.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone.enabled = false
}
