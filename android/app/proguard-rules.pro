# Release shrinking. Libraries (Room, kotlinx.serialization, Compose, ML Kit, Health Connect, the
# Anthropic SDK) ship their own keep rules; these cover what reflection in this app needs.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class app.shoku.**$$serializer { *; }
-keepclassmembers class app.shoku.** { *** Companion; }
-keepclasseswithmembers class app.shoku.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# ML Kit's barcode scanner (Google code scanner) builds its parts by reflection (component registrars
# named in the manifest). R8 stripped them and Scan crashed in the release build (NPE in
# GmsBarcodeScanning.getClient, 2026-11-16). Keep ML Kit and its Firebase-style component plumbing whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
