# ProGuard rules for Android
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
