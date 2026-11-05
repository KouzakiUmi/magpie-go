package com.magpie.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 厂商识别。
 *
 * 识别的结果决定跳哪个设置页，认错会让用户看到一个不相关的界面，
 * 所以这里把真实 ROM 上会出现的 manufacturer / brand 组合都固定下来。
 */
class PowerSettingsTest {

    @Test
    fun `xiaomi 的几种品牌名都认作小米`() {
        assertEquals(PowerSettings.XIAOMI, PowerSettings.vendorOf("Xiaomi", "Xiaomi"))
        // Redmi / POCO 是小米的子品牌，manufacturer 各不相同
        assertEquals(PowerSettings.XIAOMI, PowerSettings.vendorOf("Xiaomi", "Redmi"))
        assertEquals(PowerSettings.XIAOMI, PowerSettings.vendorOf("Xiaomi", "POCO"))
        // 大小写不固定
        assertEquals(PowerSettings.XIAOMI, PowerSettings.vendorOf("xiaomi", "redmi"))
    }

    @Test
    fun `其他国产 ROM`() {
        assertEquals("huawei", PowerSettings.vendorOf("HUAWEI", "HUAWEI"))
        assertEquals("honor", PowerSettings.vendorOf("HONOR", "HONOR"))
        // 老荣耀报 HUAWEI，新荣耀报 HIHONOR，两者都不能落到华为分支以外
        assertEquals("honor", PowerSettings.vendorOf("HONOR", "HIHONOR"))
        assertEquals("oppo", PowerSettings.vendorOf("OPPO", "OPPO"))
        assertEquals("oppo", PowerSettings.vendorOf("OnePlus", "OnePlus"))
        assertEquals("oppo", PowerSettings.vendorOf("realme", "realme"))
        assertEquals("vivo", PowerSettings.vendorOf("vivo", "vivo"))
        assertEquals("vivo", PowerSettings.vendorOf("vivo", "iQOO"))
        assertEquals("samsung", PowerSettings.vendorOf("samsung", "samsung"))
    }

    @Test
    fun `原生 Android 与其他厂商返回 null`() {
        assertNull(PowerSettings.vendorOf("Google", "google"))
        assertNull(PowerSettings.vendorOf("unknown", "unknown"))
        assertNull(PowerSettings.vendorOf("", ""))
        // 不能因为名字里恰好包含某个厂商名就误判
        assertNull(PowerSettings.vendorOf("Motorola", "motorola"))
    }
}
