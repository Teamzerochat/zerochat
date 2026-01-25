# Add project specific ProGuard rules here.

# Keep Lazysodium/JNA
-keep class com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }

# Keep SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }

# Keep WebRTC
-keep class org.webrtc.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
