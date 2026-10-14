# KarooRideVault ProGuard rules
# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep RideVault serializable classes
-keep,includedescriptorclasses class com.ridevault.karoo.**$$serializer { *; }
-keepclassmembers class com.ridevault.karoo.** {
    *** Companion;
}
-keepclasseswithmembers class com.ridevault.karoo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
