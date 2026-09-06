# Add project specific ProGuard rules here.

# ML Kit discovers its barcode/text-recognition component registrars via reflection at
# runtime (no static reference R8 can see), so without an explicit keep their no-arg
# constructors get stripped and ComponentDiscovery throws NoSuchMethodException on startup.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
