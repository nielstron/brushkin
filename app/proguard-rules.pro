# LiteRT 2's Play model delivery (AiPackModelProvider) is unused; its Play dependency is excluded.
-dontwarn com.google.android.play.**
# LiteRT's native code looks up its Java classes (e.g. LiteRtException) by name.
-keep class com.google.ai.edge.litert.** { *; }
