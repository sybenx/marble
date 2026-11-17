// The Cosign library: the signing engine an app builds on (the shares, FROST, the member
// logic, the relays a key's servers use, the signer process and NIP-55). It draws nothing;
// see Cosign.kt for what it asks of the app.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// An app takes it as "cc.aaro.cosign:cosign", from this build (Gradle's includeBuild).
group = "cc.aaro.cosign"

android {
    namespace = "cc.aaro.cosign.lib"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        // Keykin's FROST library (libkeykin_frost.so) is built for these two only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Unit tests run the native FROST too, from a desktop build of the same library:
    // the Keykin repo's: (cd frost && cargo build --release --features jni). KEYKIN_FROSTLIB names the file.
    tasks.withType<Test> {
        System.getenv("KEYKIN_FROSTLIB")?.let { systemProperty("keykin.frostlib", it) }
        environment("KEYKIN_BOX_QR", System.getenv("KEYKIN_BOX_QR") ?: "")
    }
}

dependencies {
    // The QRST core and relay layer (cc.aaro.qrst.core, .net), from the submodule. api, so an
    // app built on Cosign sees the QRST types its API hands out (Event, Json, Store, ...).
    api("cc.aaro.qrst:qrst")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.19.0")
    // The fingerprint prompt for account changes (Chip.kt).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.19.0")
}
