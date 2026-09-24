# Shared Java 8 core

This independently buildable module contains FFT/STBN math, configuration and
format fallback policy and cache validation. It has no Minecraft, loader or graphics dependencies.
Minecraft supplies Gson; compilation and standalone tests use the oldest
supported API, 2.2.4. The Fabric client embeds the core classes and sources in
its artifact; the module also remains independently testable.
Async scheduling, image decoding, capability probes and diagnostics remain in the
client adapter. Each loader supplies filesystem paths and warning callbacks;
cache filenames and hash validation are shared. Initialize configuration
through `CbbgConfig.configure` before accessing settings.

From the repository root, use a JDK supported by the Gradle wrapper:

```
./gradlew -p core build
./gradlew -p core test -PtestJava=8
./gradlew -p core test -PtestJava=17
./gradlew -p core test -PtestJava=21
./gradlew -p core test -PtestJava=25
```

Each requested test JDK must be installed and discoverable by Gradle. Production
and test classes use `--release 8`; the Gradle runtime itself need not be Java 8.
The decoded-pixel fixture was captured from the pre-extraction implementation
at `a94136594fbedf70c4e3c461641274a808120d4d` on Java 21. Its SHA-256 covers
big-endian packed ABGR integers, independently of image encoders.
