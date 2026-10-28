# the gomobile tunnel is called from native code by class name
-keep class go.** { *; }
-keep class labtunnel.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
