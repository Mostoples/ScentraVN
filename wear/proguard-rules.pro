# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
