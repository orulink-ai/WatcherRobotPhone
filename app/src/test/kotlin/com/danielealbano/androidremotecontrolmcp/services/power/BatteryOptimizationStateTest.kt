package com.danielealbano.androidremotecontrolmcp.services.power

import android.content.Context
import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryOptimizationStateTest {
    private val packageName = "com.danielealbano.androidremotecontrolmcp"

    private fun context(exempt: Boolean): Context {
        val powerManager =
            mockk<PowerManager> {
                every { isIgnoringBatteryOptimizations(packageName) } returns exempt
            }
        return mockk {
            every { getSystemService(Context.POWER_SERVICE) } returns powerManager
            every { this@mockk.packageName } returns this@BatteryOptimizationStateTest.packageName
        }
    }

    @Test
    fun `returns true when PowerManager reports exempt`() {
        assertTrue(context(exempt = true).isIgnoringBatteryOptimizations())
    }

    @Test
    fun `returns false when PowerManager reports not exempt`() {
        assertFalse(context(exempt = false).isIgnoringBatteryOptimizations())
    }
}
