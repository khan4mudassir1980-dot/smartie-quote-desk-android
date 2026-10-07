package `in`.smartie.quotedesk.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The app's name is "Quote Desk" (the Owner, 2026-10-07): in the code that
 * shows it, and in the launcher label, which lives in XML and so is read here
 * as a file. The firm's name and the package are not the app's name, and stay.
 */
class AppNameTest {

    // Gradle runs unit tests in app/; a run from the repository root works too.
    private val module = listOf(File("app"), File(".")).first { File(it, "src/main/res").isDirectory }

    @Test
    fun `the app is called Quote Desk`() {
        assertEquals("Quote Desk", AppName.NAME)
    }

    @Test
    fun `the launcher label says the same, in every flavour`() {
        // Every strings.xml under src/ — main, and any flavour's own — that
        // names the app. There is one today; a flavour adding its own label
        // has to say the same, or this fails.
        val labels = File(module, "src").walkTopDown()
            .filter { it.name == "strings.xml" }
            .flatMap { file ->
                Regex("<string name=\"app_name\">([^<]*)</string>").findAll(file.readText())
                    .map { "${file.parentFile.parentFile.parentFile.name}: ${it.groupValues[1]}" }
            }
            .toList()
        assertEquals(listOf("main: ${AppName.NAME}"), labels)
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
