# Shared Fabric release rules for the 26.2 and 26.3 profiles.
# Other loaders need their own entrypoint and reflection rules when implemented.
# Fabric metadata and Mixin JSON refer to these binary names at runtime.
-keep class com.qb20nh.cbbg.CbbgEarlyInit { *; }
-keep class com.qb20nh.cbbg.CbbgLanguageAdapter { *; }
-keep class com.qb20nh.cbbg.CbbgClient { *; }
-keep class com.qb20nh.cbbg.compat.modmenu.CbbgModMenuApi { *; }
-keep class com.qb20nh.cbbg.mixin.** { *; }

# JsonReader's Gson 2.2.4 compatibility bridge has a constructor that only
# calls its empty superclass constructor. Preserve constructor semantics.
-assumenoexternalsideeffects class com.qb20nh.cbbg.internal.gson.stream.JsonReader$1 {
    <init>();
}

# Preserve Java 25 structural metadata, runtime annotations, and source positions for Retrace.
-keepattributes *
-adaptclassstrings
