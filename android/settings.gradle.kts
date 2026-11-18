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
// The signer (Keykin v2: this phone and its box each hold a piece, any two sign). It began as the
// Cosign library in the submodule and keeps its seam to the app; the engine under it is the Keykin
// repo's Kotlin core.
include(":signer")
// Vaults (~/nostr-sync-nip/vaults.md): end-to-end encrypted sync on Nostr and Blossom; Marble's notes live in one.
include(":vaults")
// The QRST core and relay layer (sign-in by a scanned code), still from the Cosign submodule's own submodule.
includeBuild("../cosign/qr-secret-transfer/kotlin")
