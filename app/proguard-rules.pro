# Keep kotlinx.serialization serializers (release builds only; debug is unminified)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.locality.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.locality.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
