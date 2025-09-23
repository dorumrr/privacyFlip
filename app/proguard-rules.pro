# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep libsu classes
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# F-Droid compliance: Strip Google tracking URLs from string constants
-assumenosideeffects class java.lang.String {
    public java.lang.String(java.lang.String);
    public static java.lang.String valueOf(java.lang.Object);
}

# Remove Google Issue Tracker URLs specifically
-adaptresourcefilenames **.properties
-adaptresourcefilecontents **.properties,META-INF/MANIFEST.MF

# Keep essential classes but allow string optimization
-keep class androidx.room.** { *; }
-keep class androidx.work.** { *; }

# Allow ProGuard to optimize strings (this should remove the tracking URLs)
-optimizations !code/simplification/string
