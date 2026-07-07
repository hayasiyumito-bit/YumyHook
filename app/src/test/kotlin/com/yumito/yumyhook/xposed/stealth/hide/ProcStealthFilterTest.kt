package com.yumito.yumyhook.xposed.stealth.hide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcStealthFilterTest {

    @Test
    fun mapsFilter_hidesShadowhookAndYumyhook() {
        val raw = """
            7f1234000-7f1235000 r-xp 00000000 fd:00 12345 /system/lib64/libc.so
            7f2000000-7f2010000 r-xp 00000000 00:00 0 /memfd:jit-cache
            7f3000000-7f3010000 r-xp 00000000 fd:00 99999 /data/app/libshadowhook.so
            7f4000000-7f4010000 r-xp 00000000 00:00 0 [anon:ShadowHook-plt]
            7f5000000-7f5010000 r-xp 00000000 fd:00 88888 /data/app/libyumyhook_native.so
        """.trimIndent()
        val filtered = ProcMapsFilter.filter(raw)
        assertFalse(filtered.contains("shadowhook"))
        assertFalse(filtered.contains("yumyhook"))
        assertTrue(filtered.contains("libc.so"))
    }

    @Test
    fun statusFilter_clearsTracerPid() {
        val raw = """
            Name:   app
            TracerPid:   1234
            Ptrace:   1
        """.trimIndent()
        val filtered = ProcStatusFilter.filter(raw)
        assertTrue(filtered.contains("TracerPid:\t0"))
        assertTrue(filtered.contains("Ptrace:\t0"))
        assertFalse(filtered.contains("1234"))
    }

    @Test
    fun xposedFingerprint_doesNotBlockQqSettingApi() {
        val name = "com.tencent.mobileqq.setting.api.impl.SettingApiImpl"
        assertFalse(XposedFingerprintStealthHook.isBlockedClassName(name))
    }

    @Test
    fun xposedFingerprint_blocksXposedBridge() {
        assertTrue(XposedFingerprintStealthHook.isBlockedClassName("de.robv.android.xposed.XposedBridge"))
    }
}
