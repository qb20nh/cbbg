# Fabric release entrypoints and Mixin reflection rules.
# Other loaders need their own entrypoint and reflection rules when implemented.
# Fabric metadata and Mixin JSON refer to these binary names at runtime.
-keep class com.qb20nh.cbbg.CbbgEarlyInit { *; }
-keep class com.qb20nh.cbbg.CbbgLanguageAdapter { *; }
-keep class com.qb20nh.cbbg.CbbgClient { *; }
-keep class com.qb20nh.cbbg.compat.modmenu.CbbgModMenuApi { *; }
-keep class com.qb20nh.cbbg.compat.sodium.CbbgSodiumConfig { *; }
-keep class com.qb20nh.cbbg.api.** { public protected *; }
-keep public class com.qb20nh.cbbg.render.DitherPass { public protected *; }
-keep class com.qb20nh.cbbg.mixin.** { *; }

# Mixin handlers pass the Minecraft target, not their compiled mixin type.
-keepclassmembers,allowshrinking,allowobfuscation class com.qb20nh.cbbg.render.MainTargets {
    public static boolean contains(java.lang.Object);
}

# JsonReader's Gson compatibility bridge has a constructor that only
# calls its empty superclass constructor. Preserve constructor semantics.
-assumenoexternalsideeffects class com.qb20nh.cbbg.internal.gson.stream.JsonReader$1 {
    <init>();
}

# Preserve Java 25 structural metadata, runtime annotations, and source positions for Retrace.
-keepattributes !LocalVariableTable,!LocalVariableTypeTable,*
-adaptclassstrings
