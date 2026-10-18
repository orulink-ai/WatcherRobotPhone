package com.danielealbano.androidremotecontrolmcp.utils

import android.app.ActivityManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RecentsUtils")
class RecentsUtilsTest {
    @Test
    @DisplayName("setExcludeFromRecents updates all appTasks when ActivityManager is available")
    fun `setExcludeFromRecents updates all appTasks`() {
        val mockTask1 = mockk<ActivityManager.AppTask>(relaxed = true)
        val mockTask2 = mockk<ActivityManager.AppTask>(relaxed = true)
        val mockActivityManager =
            mockk<ActivityManager> {
                every { appTasks } returns listOf(mockTask1, mockTask2)
            }
        val mockContext =
            mockk<Context> {
                every { getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
            }

        RecentsUtils.setExcludeFromRecents(mockContext, true)

        verify { mockTask1.setExcludeFromRecents(true) }
        verify { mockTask2.setExcludeFromRecents(true) }
    }

    @Test
    @DisplayName("setExcludeFromRecents handles null ActivityManager gracefully")
    fun `setExcludeFromRecents handles null ActivityManager`() {
        val mockContext =
            mockk<Context> {
                every { getSystemService(Context.ACTIVITY_SERVICE) } returns null
            }

        RecentsUtils.setExcludeFromRecents(mockContext, true)
    }

    @Test
    @DisplayName("setExcludeFromRecents handles empty appTasks gracefully")
    fun `setExcludeFromRecents handles empty appTasks`() {
        val mockActivityManager =
            mockk<ActivityManager> {
                every { appTasks } returns emptyList()
            }
        val mockContext =
            mockk<Context> {
                every { getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
            }

        RecentsUtils.setExcludeFromRecents(mockContext, false)
    }
}
