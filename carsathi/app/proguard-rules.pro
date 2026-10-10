# Room entities / DAOs are handled by Room's consumer rules.
# osmdroid loads tile providers reflectively.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-dontwarn org.slf4j.**
