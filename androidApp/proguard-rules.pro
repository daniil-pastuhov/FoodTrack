# Add project specific ProGuard rules here.

# ML Kit discovers its barcode/text-recognition component registrars via reflection at
# runtime (no static reference R8 can see), so without an explicit keep their no-arg
# constructors get stripped and ComponentDiscovery throws NoSuchMethodException on startup.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# WorkManager builds an InputMerger with Class.forName(name).newInstance(). Its own consumer
# rule is `-keep class * extends androidx.work.InputMerger`, which keeps the class but not its
# members; under R8's full mode (AGP's default) that no longer implies the default constructor,
# so it gets stripped and every job dies before it runs with
# "InstantiationException: ... has no zero argument constructor".
# Glance composes every app widget inside a WorkManager SessionWorker, so the visible symptom
# is release-only: all three home-screen widgets sit on their initial loading spinner forever,
# surviving re-add and reboot, while debug builds (unminified) look fine.
-keep class * extends androidx.work.InputMerger { <init>(); }

# Glance runs a widget tap's ActionCallback with getDeclaredConstructor().newInstance(). Its own
# consumer rule keeps the class but not its members, the same full-mode gap as InputMerger above,
# so the constructor gets stripped and every tap dies in Glance's catch-all. The visible symptom
# is release-only: widget buttons do nothing when tapped, while debug builds work.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Glance tells widgets apart by class name: updateAll() finds a widget's instances through
# GlanceAppWidget.javaClass.canonicalName. R8 would otherwise merge these same-shaped classes, so
# ExpiryWidget and ShoppingListWidget share one name, each updateAll() reaches both widgets, and
# the shopping list's slot can end up showing the expiry list. Also release-only.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
