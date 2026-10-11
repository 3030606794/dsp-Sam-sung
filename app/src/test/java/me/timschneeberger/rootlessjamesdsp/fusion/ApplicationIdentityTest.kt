package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.ContextWrapper
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.check
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the same launch gate as MainActivity with the real merged application manifest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApplicationIdentityTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Test fun currentBuildApplicationIdentityIsAccepted() {
        assertEquals(BuildConfig.APPLICATION_ID, application.packageName)
        assertEquals(0, application.check())
    }

    @Test fun displayNameDoesNotRejectAnOtherwiseValidApplication() {
        val info = application.applicationInfo
        val originalLabel = info.nonLocalizedLabel
        try {
            info.nonLocalizedLabel = "DSP Fusion 测试名称"
            assertEquals("DSP Fusion 测试名称", application.getAppName())
            assertEquals(0, application.check())
        } finally {
            info.nonLocalizedLabel = originalLabel
        }
    }

    @Test fun packageIdentityMismatchIsStillRejected() {
        val renamedPackage = object : ContextWrapper(application) {
            override fun getPackageName() = "${BuildConfig.APPLICATION_ID}.clone"
        }
        assertEquals(1, renamedPackage.check())
    }
}
