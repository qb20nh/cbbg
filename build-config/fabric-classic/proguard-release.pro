-include ../proguard-release.pro

# ProGuard misremaps the annotated Java 17 local variables in this class.
-keep,allowshrinking,allowobfuscation class com.qb20nh.cbbg.render.stbn.STBNGenerator { *; }

# EnumMap looks up the enum's values() method reflectively.
-keepclassmembers enum com.qb20nh.cbbg.config.CbbgConfig$PixelFormat {
    public static **[] values();
}
