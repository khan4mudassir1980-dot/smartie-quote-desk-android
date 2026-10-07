package `in`.smartie.quotedesk.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's name is "Quote Desk" (the Owner, 2026-10-07): in the code that
 * shows it, and in the launcher label, which lives in XML and so is read here
 * as files. Production's launcher says "Quote Desk" and staging's "Quote Desk
 * Staging", so a test build is never mistaken for the real one; no build says
 * "SMARTIE Quote Desk". The firm's name and the package are not the app's
 * name, and stay.
 */
class AppNameTest {

    // Gradle runs unit tests in app/; a run from the repository root works too.
    private val module = listOf(File("app"), File(".")).first { File(it, "src/main/res").isDirectory }

    private val appName = Regex("<string name=\"app_name\">([^<]*)</string>")

    /** The `app_name` a source set's default `values/strings.xml` defines, if any. */
    private fun labelIn(sourceSet: String): String? =
        File(module, "src/$sourceSet/res/values/strings.xml").takeIf { it.isFile }
            ?.let { appName.find(it.readText())?.groupValues?.get(1) }

    /**
     * The label a build ends up with, by Android's own order: the variant's
     * source set, then the build type's, then the flavour's, then main.
     */
    private fun launcherLabel(flavour: String, buildType: String): String? =
        listOf(flavour + buildType.replaceFirstChar { it.uppercase() }, buildType, flavour, "main")
            .firstNotNullOfOrNull { labelIn(it) }

    @Test
    fun `the app is called Quote Desk, and its staging build Quote Desk Staging`() {
        assertEquals("Quote Desk", AppName.NAME)
        assertEquals("Quote Desk Staging", AppName.STAGING_LABEL)
    }

    @Test
    fun `production's launcher says Quote Desk and staging's Quote Desk Staging, debug and release alike`() {
        // The flavours and build types this resolves are the ones the build declares.
        val gradle = File(module, "build.gradle.kts").readText()
        for (declared in listOf("create(\"production\")", "create(\"staging\")", "debug {", "release {")) {
            assertTrue("build.gradle.kts declares $declared", gradle.contains(declared))
        }
        for (buildType in listOf("debug", "release")) {
            assertEquals("production $buildType", AppName.NAME, launcherLabel("production", buildType))
            assertEquals("staging $buildType", AppName.STAGING_LABEL, launcherLabel("staging", buildType))
        }
    }

    @Test
    fun `no build says SMARTIE Quote Desk`() {
        // Every strings.xml under src/, every qualifier, every source set.
        val labels = File(module, "src").walkTopDown()
            .filter { it.name == "strings.xml" }
            .flatMap { file -> appName.findAll(file.readText()).map { "${file.path}: ${it.groupValues[1]}" } }
            .toList()
        assertTrue("found no app_name at all", labels.isNotEmpty())
        labels.forEach { assertFalse(it, it.contains("SMARTIE")) }
    }

    @Test
    fun `the invite a member shares names Quote Desk`() {
        assertEquals(
            "Join Quote Desk. Open the app and choose Continue with Google; " +
                "you enter as Staff and an Owner or Administrator sets your role.",
            AppName.invite(""),
        )
        assertEquals(
            "Join Quote Desk. Open the app and choose Continue with Google; " +
                "you enter as Staff and an Owner or Administrator sets your role." +
                "\n\nhttps://example.invalid/app",
            AppName.invite("https://example.invalid/app"),
        )
    }

    @Test
    fun `the old name is gone from what the app says`() {
        assertFalse(AppName.NAME.contains("SMARTIE"))
        assertFalse(AppName.invite("").contains("SMARTIE"))
    }
}
