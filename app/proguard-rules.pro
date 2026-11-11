# OpenCV: a parte nativa chama classes Java por nome.
-keep class org.opencv.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.andre.scanner.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
