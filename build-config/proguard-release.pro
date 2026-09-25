# Shared Fabric release rules for the 26.2 and 26.3 profiles.
# Other loaders need their own entrypoint and reflection rules when implemented.
# Fabric metadata and Mixin JSON refer to these binary names at runtime.
-keep class com.qb20nh.cbbg.CbbgEarlyInit { *; }
-keep class com.qb20nh.cbbg.CbbgClient { *; }
-keep class com.qb20nh.cbbg.compat.modmenu.CbbgModMenuApi { *; }
-keep class com.qb20nh.cbbg.mixin.** { *; }

# Gson constructs this private model and reads/writes its field names reflectively.
-keepclassmembers class com.qb20nh.cbbg.config.CbbgConfig$DiskModel {
    <init>();
    <fields>;
}
-keepclassmembers enum com.qb20nh.cbbg.config.CbbgConfig$* { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Preserve Java 25 structural metadata, runtime annotations, and source positions for Retrace.
-keepattributes *
-adaptclassstrings
