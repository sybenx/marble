// Vaults (the encrypted-sync spec, ~/nostr-sync-nip/vaults.md draft 7.14): keys and generations, commits,
// versions, nv1 blobs, heads, checkpoints and sync. No screens; the app gives it relays, Blossom and the account.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.aaro.vaults"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    tasks.withType<Test> {
        // The end-to-end tests against a running vaults-test-server and Keykin box are skipped unless these are set.
        listOf("VAULTS_SERVER", "VAULTS_SERVER2", "KEYKIN_BOX", "KEYKIN_SETUP").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    }
}

dependencies {
    // Hex, hashes, NIP-44 and secp256k1 from the Keykin core in the signer, so both use one implementation.
    api(project(":signer"))
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.19.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.19.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}
