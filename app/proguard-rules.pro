# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class hu.szfvbus.**$$serializer { *; }
-keepclassmembers class hu.szfvbus.** { *** Companion; }
-keepclasseswithmembers class hu.szfvbus.** { kotlinx.serialization.KSerializer serializer(...); }

# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
