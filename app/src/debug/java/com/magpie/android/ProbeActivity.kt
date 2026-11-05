package com.magpie.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 诊断用 Activity：在真实 App 进程内跑 [NetworkProbe]，把结果同时显示在屏幕上。
 *
 * 启动方式（不需要改 launcher）：
 * ```
 * adb shell am start -n com.magpie.android/.ProbeActivity
 * ```
 *
 * 这是开发期工具，发布前应删除或加 `android:enabled="false"`。
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            textSize = 13f
            setPadding(32, 96, 32, 32)
            text = "probing…"
        }
        setContentView(view)
        NetworkProbe.probe { log ->
            runOnUiThread { view.text = log.joinToString("\n") }
        }
    }
}
