# LiteRT's native code looks up its Java classes (e.g. LiteRtException) by name, so keep them,
# except Play model delivery (deployment/AiPackModelProvider): unused, and its Google Play
# dependencies are excluded, so R8 removes it.
-keep class !com.google.ai.edge.litert.deployment.**,com.google.ai.edge.litert.** { *; }
-dontwarn com.google.android.play.**
