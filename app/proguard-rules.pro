# kotlinx.serialization generates a companion `serializer()` per @Serializable class and looks it up
# reflectively; R8 cannot see that use and would strip it. These are the rules the library documents.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    public static ** Companion;
}

# LiveKit ships a relocated libwebrtc whose native layer resolves Java classes by name.
-keep class livekit.org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
