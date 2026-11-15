# Release shrinking. Libraries (Room, kotlinx.serialization, Compose, ML Kit, Health Connect, the
# Anthropic SDK) ship their own keep rules; these cover what reflection in this app needs.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class app.shoku.**$$serializer { *; }
-keepclassmembers class app.shoku.** { *** Companion; }
-keepclasseswithmembers class app.shoku.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
