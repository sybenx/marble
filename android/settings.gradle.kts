pluginManagement {
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
rootProject.name = "marble"
include(":app")
// The Cosign library, from the submodule (github.com/sybenx/cosign): Gradle builds it from
// source and gives it to `implementation("cc.aaro.cosign:cosign")`, as it would for any app.
includeBuild("../cosign/android")
