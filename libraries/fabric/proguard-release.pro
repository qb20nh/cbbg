-keep class com.qb20nh.cbbg.api.** { public protected *; }
-keep public class com.qb20nh.cbbg.render.DitherPass { public protected *; }
-keep public class com.qb20nh.cbbg.render.CbbgShaders { public protected *; }
-keep public class com.qb20nh.cbbg.render.DitherPipelines { public protected *; }
-keep class com.qb20nh.cbbg.mixin.** { *; }
-keepattributes !LocalVariableTable,!LocalVariableTypeTable,*
-adaptclassstrings
-repackageclasses com.qb20nh.cbbg.lib.internal
-allowaccessmodification

-keepclassmembers class com.qb20nh.cbbg.render.DitherPass {
    void renderInto(com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuTextureView, float, float, float, boolean, java.util.function.Supplier);
}

-keep,allowoptimization class com.qb20nh.cbbg.render.DitherPass$GlState {
    <init>();
    public void close();
}
