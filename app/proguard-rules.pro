# Moshi
-keep class com.ghissue.app.network.** { *; }
-keepclassmembers class com.ghissue.app.network.** { *; }

# Retrofit
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
