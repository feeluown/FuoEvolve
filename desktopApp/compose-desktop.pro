# FuoEvolve desktop release shrink rules.
#
# Compose supplies its own default desktop rules. Keep the desktop/native integration
# boundaries conservative for the first release-shrinking phase because JNA, DBus and
# the credential store use reflection, native proxies and platform-selected providers
# that static analysis cannot fully discover.

# Keep shrinking enabled, but disable ProGuard bytecode optimization. The optimizer has
# confirmed Kotlin/Compose JVM verifier issues and can rewrite valid Kotlin library bytecode
# into methods that fail JVM verification (observed with Okio's buffered sink bridge).
-dontoptimize

# Preserve metadata commonly consumed by reflection/proxy libraries.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JNA resolves Library methods and Structure fields dynamically against native symbols.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# The credential storage library selects Windows/macOS/Linux implementations at runtime.
-keep class com.microsoft.credentialstorage.** { *; }
-keep interface com.microsoft.credentialstorage.** { *; }

# dbus-java creates DBus proxies dynamically and discovers transport/provider classes.
-keep class org.freedesktop.dbus.** { *; }
-keep interface org.freedesktop.dbus.** { *; }
-dontwarn org.freedesktop.dbus.**

# SQLDelight's desktop SQLite backend reaches the Xerial JDBC driver through DriverManager /
# ServiceLoader, which ProGuard cannot infer from META-INF/services/java.sql.Driver.
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# Ktor discovers kotlinx.serialization format integrations through java.util.ServiceLoader.
# ProGuard keeps META-INF/services resources but cannot infer that their implementation
# classes are runtime entry points, so preserve the JSON provider explicitly.
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }

# OkHttp ships adapters for optional runtimes/providers which are intentionally absent
# from this JVM desktop distribution. Their guarded references are safe to omit.
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn android.util.Log

# FuoEvolve's native interfaces and JNA Structure subclasses live in this package.
# Keeping the thin desktop host layer avoids removing methods only called from native code;
# the much larger shared/features/provider dependency graph remains shrinkable.
-keep class org.feeluown.mobile.desktop.** { *; }
