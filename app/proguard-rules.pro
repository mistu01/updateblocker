# Keep LSPosed / Xposed entry points
-keep class io.github.updateblocker.hook.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# Keep data models and providers
-keep class io.github.updateblocker.provider.** { *; }
-keep class io.github.updateblocker.data.** { *; }
