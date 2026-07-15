pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShareGuard"

include(
    ":app",
    ":core:model",
    ":core:pipeline",
    ":core:session",
    ":core:security",
    ":core:storage",
    ":core:ui",
    ":block:text",
    ":block:url",
    ":block:image",
    ":block:ocr",
    ":block:render",
    ":block:verify",
    ":feature:entry",
    ":feature:workflow",
    ":feature:review",
    ":feature:output",
    ":feature:saved",
    ":test-corpus",
    ":benchmark",
)
