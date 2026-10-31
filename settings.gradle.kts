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
    }
}

rootProject.name = "rsc-map-renderer"

include(
    ":game-data",
    ":account-data",
    ":bot-api",
    ":nav",
    ":nav-bench",
    ":teavm-classlib-patch",
    ":nav-web",
    ":client-render",
    ":world3d-bake",
    ":map2d",
)
