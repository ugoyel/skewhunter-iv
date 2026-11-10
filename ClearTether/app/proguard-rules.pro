# ClearTether R8 rules.
# Play Billing ships its own consumer rules; ZXing core and Compose need none.

# Keep service/receiver class names referenced only from the manifest (AGP already does this,
# listed here for clarity when auditing release builds).
-keep class com.cleartether.app.service.** { *; }

# Strip verbose logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
