# secp256k1 JNI bindings are looked up by name.
-keep class fr.acinq.secp256k1.** { *; }
# Keykin's FROST JNI binding is looked up by name (Java_keykin_FrostNative_call, in libkeykin_frost.so).
-keep class keykin.FrostNative { *; }
