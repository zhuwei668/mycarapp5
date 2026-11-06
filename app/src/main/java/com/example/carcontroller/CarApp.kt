package com.example.carcontroller

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * 在 Application.attachBaseContext 里安装全局崩溃捕获——这是自定义代码能介入的最早时机，
 * 比任何 Activity.onCreate 都早。之前把 handler 装在 onCreate 里，既覆盖不到更早的启动阶段，
 * 又把异常"吞掉"了，导致 logcat / Android Studio 里看不到 FATAL EXCEPTION，无法诊断。
 *
 * 这里做三件事：
 *  1) 用 Log.e 把完整堆栈打进 logcat（Android Studio 里过滤 CarCtrlCrash 即可看到）；
 *  2) 写一份到应用私有目录，供下次启动的红屏回放显示；
 *  3) 交回系统默认 handler，让系统正常记录 FATAL EXCEPTION 并结束进程。
 */
class CarApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = "CarCtrlCrash @ ${java.util.Date()}\n\n${error.stackTraceToString()}"
            try {
                Log.e("CarCtrlCrash", report)
            } catch (_: Throwable) { }
            try {
                getExternalFilesDir(null)?.let { dir ->
                    java.io.File(dir, "crash_v2.txt").writeText(report)
                }
            } catch (_: Throwable) { }
            // 交回系统默认处理器：logcat 会打印标准 FATAL EXCEPTION 堆栈
            previous?.uncaughtException(thread, error)
        }
    }
}
