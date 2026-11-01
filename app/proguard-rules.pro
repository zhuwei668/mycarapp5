# 保留控制器代码，避免混淆导致蓝牙/JSON 解析异常
-keep class com.example.carcontroller.** { *; }
-dontwarn org.json.**
