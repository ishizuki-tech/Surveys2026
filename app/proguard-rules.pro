# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep LiteRtLM class and all of its public APIs used via reflection.
-keep class com.negi.surveys.slm.LiteRtLM { *; }

# (Optional) Keep Kotlin metadata (helps reflection / debugging).
-keep class kotlin.Metadata { *; }

# If you moved/renamed the package, keep the correct one too.
# -keep class <your.actual.package>.LiteRtLM { *; }

