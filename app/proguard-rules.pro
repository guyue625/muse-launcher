# 保留本应用全部类与成员，确保 @JavascriptInterface、反射及异常堆栈清晰可靠；
# R8 主要裁剪 appcompat / core / kotlin-stdlib 等依赖库中未使用的类与资源，显著降低安装包体积与运行时 DEX 内存。
-keep class com.seekstars.muselauncher.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
