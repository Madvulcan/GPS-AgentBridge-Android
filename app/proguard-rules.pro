# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.Hilt_AndroidApp { *; }

# Kotlin metadata (needed by reflection-based libs)
-keep class kotlin.Metadata { *; }

# Keep Application class for Hilt
-keep class com.madvulcan.gpsagentbridge.App { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
