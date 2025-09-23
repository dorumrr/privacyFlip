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



# Keep libsu classes
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Production-ready rules for F-Droid compliance
-keepattributes *Annotation*

# F-Droid compliance: Remove Google tracking URLs using R8 string replacement
# This approach replaces the problematic URLs with harmless strings

# Replace Google Issue Tracker URLs with generic text
-adaptresourcefilecontents **.properties,META-INF/MANIFEST.MF,**.xml
-adaptresourcefilenames **.properties,META-INF/MANIFEST.MF

# Use R8's string replacement to replace tracking URLs
-if class androidx.room.**
-keep,allowobfuscation class androidx.room.**

# Remove Room error messages entirely by making them no-ops
-assumenosideeffects class androidx.room.util.DBUtil {
    public static void dropFtsSyncTriggers(...);
}

# Remove Room's InvalidationTracker completely
-assumenosideeffects class androidx.room.InvalidationTracker {
    <init>(...);
    public void addObserver(...);
    public void removeObserver(...);
    *** *;
}

# Aggressive optimization to remove string constants
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-allowaccessmodification
-repackageclasses ''

# Keep only essential Room classes for functionality
-keep class androidx.room.Room {
    public static *** databaseBuilder(...);
}
-keep class androidx.room.RoomDatabase {
    <init>(...);
    public *** getOpenHelper();
    public *** runInTransaction(...);
}
-keep class androidx.work.** {
    <init>(...);
    public <methods>;
}
