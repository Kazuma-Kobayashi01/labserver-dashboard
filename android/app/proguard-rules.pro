# the gomobile tunnel is called from native code by class name
-keep class go.** { *; }
-keep class labtunnel.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.slf4j.**
# the terminal's libvterm bridge calls back into these by name
-keep class org.connectbot.terminal.** { *; }
