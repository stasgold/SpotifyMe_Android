# kotlinx.serialization: keep generated serializers for our wire types.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.spotifyme.**$$serializer { *; }
-keepclassmembers class com.spotifyme.** {
    *** Companion;
}
-keepclasseswithmembers class com.spotifyme.** {
    kotlinx.serialization.KSerializer serializer(...);
}
