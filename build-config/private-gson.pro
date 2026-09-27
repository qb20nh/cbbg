# Keep the streaming API usable by the Java 8 config codec. Other Gson code is
# retained only when reachable from it, then moved under the private package.
-dontoptimize
-dontpreverify
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,Signature,Exceptions
-keep,allowobfuscation class com.google.gson.stream.JsonReader { public *; }
-keep,allowobfuscation class com.google.gson.stream.JsonWriter { public *; }
-keep,allowobfuscation class com.google.gson.stream.JsonToken { public *; }
-keep,allowobfuscation class com.google.gson.stream.MalformedJsonException { public *; }
-keepclassmembers class com.google.gson.stream.** { public *; }
-repackageclasses com.qb20nh.cbbg.internal.gson.impl
