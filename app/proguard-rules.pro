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
-renamesourcefileattribute null

# keep netty related classes
-keep class io.netty.** { *; }
-keep class kotlinx.** { *; }
-keep class org.luaj.** { *; }
-keep class org.cloudburstmc.netty.** { *; }
# Packet class simple names are written to the release login trace; keep them readable instead of
# letting R8 turn entries such as RequestNetworkSettingsPacket/LoginPacket into q2/m1.
-keepnames class org.cloudburstmc.protocol.bedrock.packet.**
-keep @io.netty.channel.ChannelHandler$Sharable class *

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class coelho.msftauth.api.** { *; }
# AuthHttpClient.install() replaces ProtoHax's HTTP client through reflection on this field
-keep class dev.sora.relay.utils.HttpUtils {
    okhttp3.OkHttpClient client;
}
#-keep class dev.sora.** { *; }
#-keep class org.cloudburstmc.** { *; }

# R8 reports every class it cannot find as an error unless it is listed here. These are optional integrations
# that Netty, Apache HttpClient, BouncyCastle, LuaJ ... reference behind class-presence checks, or from code that
# is never used on Android (JNDI / Kerberos / JSR-223 / GraalVM / BlockHound / codecs for compression libraries
# that are not shipped; ProtoHax uses its own Zlib and OkHttp for HTTP). The list is exactly what R8 reports for
# the release build (AGP's missing_rules.txt).
#
# It used to be a blanket "-dontwarn **": that hid java.awt.Color - which does not exist on Android but is used by
# the protocol classes - until it crashed SerializedSkin at runtime. Any other missing class now fails the build.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.protobuf.ExtensionRegistry
-dontwarn com.google.protobuf.ExtensionRegistryLite
-dontwarn com.google.protobuf.MessageLite
-dontwarn com.google.protobuf.MessageLite$Builder
-dontwarn com.google.protobuf.MessageLiteOrBuilder
-dontwarn com.google.protobuf.Parser
-dontwarn com.google.protobuf.nano.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.**
-dontwarn javax.naming.**
-dontwarn javax.script.**
-dontwarn lombok.NonNull
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.apache.bcel.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.jboss.marshalling.**
-dontwarn reactor.blockhound.**
