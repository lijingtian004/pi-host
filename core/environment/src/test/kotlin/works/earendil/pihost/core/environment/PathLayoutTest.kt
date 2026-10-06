package works.earendil.pihost.core.environment

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PathLayoutTest {

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `runtime dirs are siblings under filesDir`() {
        val files = ctx.filesDir
        assertEquals(files, PathLayout.runtimeDir(ctx).parentFile)
        assertEquals(files, PathLayout.runtimeTmpDir(ctx).parentFile)
        assertEquals(files, PathLayout.runtimeOldDir(ctx).parentFile)
        assertEquals(files, PathLayout.homeDir(ctx).parentFile)
        assertEquals(files, PathLayout.projectsDir(ctx).parentFile)

        assertEquals("runtime", PathLayout.runtimeDir(ctx).name)
        assertEquals("runtime.tmp", PathLayout.runtimeTmpDir(ctx).name)
        assertEquals("home", PathLayout.homeDir(ctx).name)
        assertEquals("projects", PathLayout.projectsDir(ctx).name)
    }

    @Test
    fun `installed marker lives inside runtime dir`() {
        val marker = PathLayout.installedMarker(ctx)
        assertEquals(PathLayout.runtimeDir(ctx), marker.parentFile)
        assertEquals(".installed.json", marker.name)
    }

    @Test
    fun `project dir nests under projects dir`() {
        val project = PathLayout.projectDir(ctx, "demo repo")
        assertEquals(PathLayout.projectsDir(ctx), project.parentFile)
        assertEquals(File(ctx.filesDir, "projects/demo repo"), project)
    }
}
