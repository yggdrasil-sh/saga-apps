# kotlinx.serialization: erzeugte Serializer behalten
-keepattributes *Annotation*, InnerClasses
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
