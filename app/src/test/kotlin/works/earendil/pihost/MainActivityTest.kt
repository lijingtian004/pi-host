package works.earendil.pihost

import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.environment.PathLayout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    @Before
    fun clearRuntimeMarker() {
        val files = RuntimeEnvironment.getApplication().filesDir
        PathLayout.runtimeDir(RuntimeEnvironment.getApplication()).deleteRecursively()
        assertFalse(files.resolve("runtime").exists())
    }

    private fun launch(): TextView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        return content.getChildAt(0) as TextView
    }

    @Test
    fun `reports runtime not installed on a clean sandbox`() {
        assertEquals("pi-android host (P2 shell)\nruntime: not installed", launch().text.toString())
    }

    @Test
    fun `reports the installed runtime version from the marker`() {
        val marker = PathLayout.installedMarker(RuntimeEnvironment.getApplication())
        marker.parentFile!!.mkdirs()
        marker.writeText("""{"runtimeVersion":"2026.10.06-1"}""")

        assertEquals(
            "pi-android host (P2 shell)\nruntime: 2026.10.06-1",
            launch().text.toString(),
        )
    }
}
