// A project that builds npm packages: Node.js, downloaded from the settings' Node.js repository, and
// the tasks of Npm.kt to run npm in each package.
plugins {
    base
    id("com.github.node-gradle.node")
}

layout.buildDirectory = layout.projectDirectory.dir("target")

node {
    version = "26.2.0"
    download = true
    distBaseUrl = null
    workDir = layout.buildDirectory.dir("nodejs")
}
