# Library consumer ProGuard rules
-keepattributes *Annotation*, InnerClasses
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
