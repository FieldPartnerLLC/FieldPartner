# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.fieldpartner.app.**$$serializer { *; }
-keepclassmembers class com.fieldpartner.app.** { *** Companion; }
-keepclasseswithmembers class com.fieldpartner.app.** { kotlinx.serialization.KSerializer serializer(...); }
# LiveKit / WebRTC use JNI
-keep class livekit.org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
