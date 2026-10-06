# Java 21 build

This Android project is configured to build with JDK 21 and Gradle 8.7.

The bundled `app/libs/ProtoHax-1.4.0.jar` is the Java 21 ProtoHax build and already contains the Bedrock protocol codec for protocol 844 (Minecraft Bedrock 1.21.111).

Build locally:

```bash
chmod +x gradlew
./gradlew app:assembleDebug
```

GitHub Actions uses JDK 21 and builds the release APK.
