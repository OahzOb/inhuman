# R8 rules for the release build.
#
# The release used to ship unminified because the document and SSH engines are
# reflection-heavy and a broken release build is found by a user, not by the
# JVM tests. The cost of that safety was measurable: 72 MB of APK, about 30 MB
# of which was every icon in `material-icons-extended` (the app names 40).
#
# The rule here is therefore "keep the engines whole, let R8 remove what is
# provably unused elsewhere". Keeping a library disables shrinking inside it,
# which is why these are the libraries where reflection or resource-name lookup
# is plausible -- POI/XMLBeans, PDFBox, BouncyCastle, sshj, JLaTeXMath and the
# JavaScript sandbox -- and not Compose, coroutines or the app's own code.

# --- document engines -------------------------------------------------------
# POI resolves OOXML schema classes and workbook factories dynamically;
# XMLBeans generates classes at build time and looks them up by name.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class org.apache.commons.** { *; }
-keep class org.apache.logging.log4j.** { *; }
-keep class com.tom_roush.** { *; }

# --- SSH --------------------------------------------------------------------
# sshj resolves key/cipher implementations and BouncyCastle is a JCA provider
# looked up through `java.security` by name.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class * extends java.security.Provider { *; }
-keep class * implements java.security.Provider { *; }

# --- maths ------------------------------------------------------------------
# JLaTeXMath loads its font tables and symbol mappings from bundled resources.
-keep class org.scilab.forge.jlatexmath.** { *; }

# --- platform sandbox -------------------------------------------------------
-keep class androidx.javascriptengine.** { *; }

# --- serialization ----------------------------------------------------------
# kotlinx.serialization ships consumer rules; these are the belt to their
# braces, and the app's persisted JSON (tool calls, provider state, backups)
# must decode in a release build.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- optional dependencies that are absent on Android -----------------------
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.**
-dontwarn org.apache.commons.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.tom_roush.**
-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn org.scilab.forge.jlatexmath.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
