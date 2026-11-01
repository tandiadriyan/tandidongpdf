# PdfBox-Android (resources and security handlers are looked up dynamically)
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# PDFium JNI bindings
-keep class io.legere.pdfiumandroid.** { *; }

# App core engine (kept readable in stack traces)
-keepattributes SourceFile,LineNumberTable
