# kotlinx.serialization: erzeugte Serializer behalten
-keepattributes *Annotation*, InnerClasses

# 2.5 (clientlog1): Zeilennummern in Absturzberichten behalten (Dateiname neutral); Namen bleiben verschleiert,
# zurueckuebersetzen mit retrace und der mapping.txt der jeweiligen Version (CI-Artefakt "android-mapping")
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class io.github.yggdrasil_sh.saga.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.yggdrasil_sh.saga.**$$serializer { *; }

# OkHttp / zxing: optionale Plattform-Klassen
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Chat: lazysodium/JNA (Aufrufe ueber Reflection/JNI)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Anrufe (2.1): WebRTC (webrtc-sdk) ruft Java-Klassen aus nativem Code (JNI) auf - nichts umbenennen/entfernen
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { native <methods>; }
-dontwarn org.webrtc.**
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
