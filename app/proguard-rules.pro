# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
# ExoPlayer
-keep class androidx.media3.** { *; }
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
# Data classes
-keep class com.xvideo.downloader.data.model.** { *; }
-keep class com.xvideo.downloader.data.local.database.entity.** { *; }
