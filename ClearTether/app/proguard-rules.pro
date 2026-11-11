# ClearTether R8 rules.

# ML Kit (QR code scanner) discovers its components by class name from manifest meta-data and
# instantiates them reflectively. In R8 full mode a plain "-keep class" no longer keeps the
# no-arg constructor, so the registrars lost theirs and GmsBarcodeScanning crashed the app.
# Keep the constructors explicitly.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-keep class com.google.mlkit.**.*Registrar { public <init>(); }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService { <init>(); }

# Keep service/receiver class names referenced only from the manifest (AGP already does this,
# listed here for clarity when auditing release builds).
-keep class com.cleartether.app.service.** { *; }

# Strip verbose logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
