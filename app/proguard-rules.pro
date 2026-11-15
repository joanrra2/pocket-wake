# HiveMQ MQTT Client / Netty on Android
# Netty looks up some fields and classes reflectively.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }

# Optional Netty integrations that are not on Android (logging backends, native TLS, compression).
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn reactor.blockhound.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn com.google.protobuf.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn io.reactivex.**
