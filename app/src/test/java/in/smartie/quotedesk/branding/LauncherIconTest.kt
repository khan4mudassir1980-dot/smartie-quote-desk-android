package `in`.smartie.quotedesk.branding

import android.app.Application
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The launcher icon as Android inflates it (N5.12b), on Android 14 — so the
 * adaptive icon and its Android 13 monochrome layer are both in play.
 * `LauncherIconFilesTest` checks the files themselves.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class LauncherIconTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `both launcher icons are adaptive - the mark on #F7F4FF, with a themed-icon layer`() {
        for ((name, id) in listOf("ic_launcher" to R.mipmap.ic_launcher, "ic_launcher_round" to R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(id)
            assertTrue("$name is ${icon?.javaClass?.simpleName}", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            val background = icon.background
            assertTrue("$name background is ${background?.javaClass?.simpleName}", background is ColorDrawable)
            assertEquals(name, 0xFFF7F4FF.toInt(), (background as ColorDrawable).color)
            assertNotNull("$name foreground", icon.foreground)
            assertNotNull("$name monochrome", icon.monochrome)
        }
    }

    @Test
    fun `the background colour resource is the icon background`() {
        assertEquals(0xFFF7F4FF.toInt(), context.getColor(R.color.ic_launcher_background))
    }
}
