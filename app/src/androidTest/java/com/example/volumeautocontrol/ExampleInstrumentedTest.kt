package com.example.volumeautocontrol

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // 仪器测试跑在 debug 变体上，包名是 applicationId 加 .debug 后缀，
        // 不是 namespace com.example.volumeautocontrol。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.lbmausky.volumeautocontrol.debug", appContext.packageName)
    }
}