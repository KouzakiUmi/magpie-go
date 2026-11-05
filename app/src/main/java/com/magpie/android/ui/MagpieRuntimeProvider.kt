package com.magpie.android.ui

import com.magpie.android.runtime.MagpieConfig
import com.magpie.android.runtime.MagpieRuntime

/** Application 向界面层提供运行时；UI 不依赖具体实现。 */
interface MagpieRuntimeProvider {
    fun runtime(): MagpieRuntime
}

/** 启动参数由 Application 注入，固定 web key 的生成与保管不属于 UI。 */
fun interface StartConfigProvider {
    fun config(): MagpieConfig
}
