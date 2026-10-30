# the gomobile tunnel is called from native code by class name
-keep class go.** { *; }
-keep class labtunnel.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
# JSch builds its ciphers, key exchanges and signatures by class name
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
# the terminal's libvterm bridge calls back into these by name
-keep class org.connectbot.terminal.** { *; }
