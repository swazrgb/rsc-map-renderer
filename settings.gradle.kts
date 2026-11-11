pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "rsc-map-renderer"

include(
    ":game-data",
    ":account-data",
    ":bot-api",
    ":nav",
    ":game-client",
    ":session-proxy-core",
    ":teavm-classlib-patch",
    ":nav-web",
    ":client-render",
    ":world3d-bake",
    ":map2d",
    ":live-map",
    ":live-map-web",
)
