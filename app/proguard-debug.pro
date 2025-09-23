# Debug-specific ProGuard rules
# More aggressive Room removal for debug builds to ensure clean APKs

# Completely remove Room's InvalidationTracker and all its methods
-assumenosideeffects class androidx.room.InvalidationTracker {
    <init>(...);
    *** *;
}

# Remove all Room error logging and string constants
-assumenosideeffects class androidx.room.** {
    public static void log(...);
    public static void e(...);
    public static void w(...);
    public static void d(...);
    public static void i(...);
    public static void v(...);
    public static final java.lang.String *;
    private static final java.lang.String *;
}

# Remove Room database utility methods that contain error messages
-assumenosideeffects class androidx.room.util.DBUtil {
    public static void dropFtsSyncTriggers(...);
    *** *;
}

# Most aggressive approach: Remove the entire Room InvalidationTracker class
-assumenosideeffects class androidx.room.InvalidationTracker$ObservedTableTracker {
    <init>(...);
    *** *;
}

# Remove Room's AutoCloser class that contains the error message
-assumenosideeffects class androidx.room.AutoCloser {
    <init>(...);
    *** *;
}

# Remove all Room exception classes
-assumenosideeffects class androidx.room.** extends java.lang.Exception {
    <init>(...);
    *** *;
}

# Ultra-aggressive: Remove all string constants from Room package
-assumenosideeffects class androidx.room.** {
    static final *** *;
    private static final *** *;
    public static final *** *;
}

# More aggressive string removal for debug
-optimizations code/removal/advanced,code/simplification/string,code/simplification/arithmetic,!code/simplification/cast
-allowaccessmodification
-repackageclasses ''

# Keep debugging attributes but allow aggressive optimization
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
