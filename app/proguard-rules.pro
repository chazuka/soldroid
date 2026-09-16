# R8 configuration for the release build (`isMinifyEnabled` + `isShrinkResources` in
# app/build.gradle.kts). AGP 9 runs R8 in full mode, which shrinks, optimises and obfuscates.
#
# Almost everything this app needs already arrives with its dependencies, and R8 applies those
# automatically: kotlinx.serialization carries its rules inside kotlinx-serialization-core
# (META-INF/com.android.tools/r8/), LiveKit ships proguard.txt in its AAR covering the relocated
# libwebrtc JNI surface, the NIST SDP parser and protobuf, and Hilt/Dagger and Compose ship theirs.
#
# Restating a library's rules here is how a keep rule grows into a blanket `-keep class io.livekit.**
# { *; }` that silently turns shrinking and obfuscation back off for the largest dependency in the
# APK. So this file holds only what nothing else supplies.
#
# Verify after any change: `./gradlew :app:assembleRelease`, then install the APK and walk one
# conversation end to end. Shrinking only ever breaks in release, and only at runtime.

# -- Readable crash reports ---------------------------------------------------------------------
# R8 drops the file and line attributes by default, so every frame of a release stack trace comes
# back as `Unknown Source`. Keeping them costs a few kB and gives up no secrecy: class and method
# names stay obfuscated, and `-renamesourcefileattribute` replaces the real .kt filename with the
# constant "SourceFile".
#
# Each release build writes the symbol table to `app/build/outputs/mapping/release/mapping.txt`.
# Archive it next to the APK it came from — it is the only way to turn an obfuscated trace back into
# source positions:
#
#     retrace app/build/outputs/mapping/release/mapping.txt crash.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -- OkHttp -------------------------------------------------------------------------------------
# OkHttp 5 stopped shipping consumer rules; 4.12 was the last release carrying
# META-INF/proguard/okhttp3.pro. Its two shrinker needs are therefore ours to state.
#
# PublicSuffixDatabase.list is read with a *relative* resource path, which resolves against the
# package of the class doing the loading. Obfuscate that package and the stream comes back null,
# taking domain matching for cookies and URL parsing with it. `-keepnames` pins the names while
# still allowing the classes to be shrunk away entirely if unused.
-keepnames class okhttp3.internal.publicsuffix.**

# The TLS platform layer compiles against Conscrypt, BouncyCastle and OpenJSSE. None of them ship on
# Android and none are on this app's classpath — the absence is by design, so silence the warnings
# rather than pulling the providers in.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
