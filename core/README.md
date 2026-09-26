# Shared Java 8 core

This module provides FFT/STBN math, configuration, format fallback rules and
cache validation. It can be built and tested independently of Minecraft, loaders
and graphics libraries. Minecraft supplies Gson; compilation and standalone
tests use the oldest supported API, 2.2.4. Fabric jars include the core classes
and sources.

The client adapter handles async scheduling, image decoding, graphics capability
checks and diagnostics. Each loader supplies filesystem paths and warning
callbacks. The core defines cache filenames and validates hashes. Call
`CbbgConfig.configure` before accessing settings.

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
