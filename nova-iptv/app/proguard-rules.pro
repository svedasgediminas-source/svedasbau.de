# kotlinx.serialization (Xtream DTOs live in :data:xtream)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
-keep,includedescriptorclasses class app.novaiptv.**$$serializer { *; }
# XmlPullParser is provided by the platform
-dontwarn org.xmlpull.v1.**
-dontwarn org.kxml2.**
