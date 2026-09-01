# ProGuard 规则
-keep class com.farmtrade.app.data.Record { *; }
-keep class com.farmtrade.app.data.DatabaseHelper { *; }
-dontwarn org.apache.poi.**
-keep class org.apache.poi.** { *; }
-dontwarn com.github.mikephil.charting.**
-keep class com.github.mikephil.charting.** { *; }
