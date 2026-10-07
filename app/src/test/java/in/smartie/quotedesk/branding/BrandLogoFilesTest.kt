package `in`.smartie.quotedesk.branding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intro's and sign-in's logo, checked as files (N5.12b): `QD_full_colour`
 * from `branding/`, made at every density by `tools/branding/make-assets.py`.
 * Plain JVM, like `LauncherIconFilesTest`, reading through [PngImage].
 */
class BrandLogoFilesTest {

    private val module = listOf(File("app"), File(".")).first { File(it, "src/main/res").isDirectory }
    private val res = File(module, "src/main/res")

    private val densities = listOf(
        "mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0,
    )

    private fun logo(density: String): PngImage = PngImage.read(File(res, "drawable-$density/qd_logo.png"))

    private fun channels(argb: Int) = listOf(argb ushr 24, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `the logo is made at 200dp for every density, with transparency`() {
        for ((name, scale) in densities) {
            val image = logo(name)
            assertEquals("drawable-$name width", (200 * scale).toInt(), image.width)
            assertEquals("drawable-$name height", (200 * scale).toInt(), image.height)
            assertTrue("drawable-$name has an alpha channel", image.hasAlpha)
        }
    }

    @Test
    fun `it is the whole logo, scaled, not cropped`() {
        // The artwork fills (35, 37)-(989, 986) of the 1024 original: about
        // 7dp to 192.5dp of 200 each way, measured to the half-opaque edge.
        for ((name, scale) in densities) {
            val image = logo(name)
            val opaque = (0 until image.height).flatMap { y ->
                (0 until image.width).filter { x -> channels(image.getRGB(x, y))[0] >= 128 }.map { x -> x to y }
            }
            val edges = listOf(opaque.minOf { it.first }, opaque.minOf { it.second },
                opaque.maxOf { it.first } + 1, opaque.maxOf { it.second } + 1).map { it / scale }
            assertTrue("drawable-$name left/top $edges", edges[0] in 6.0..9.0 && edges[1] in 6.0..9.0)
            assertTrue("drawable-$name right/bottom $edges", edges[2] in 191.0..194.0 && edges[3] in 191.0..194.0)
        }
    }

    @Test
    fun `it is the navy-text logo, made for a light background`() {
        // "Quote" sits in the lower left: navy in QD_full_colour, white in
        // QD_full_whitetext, which nothing is made from yet.
        for ((name, _) in densities) {
            val image = logo(name)
            var dark = 0
            var light = 0
            var all = 0
            for (y in (image.height * 0.78).toInt() until (image.height * 0.96).toInt()) {
                for (x in (image.width * 0.03).toInt() until (image.width * 0.55).toInt()) {
                    val (a, r, g, b) = channels(image.getRGB(x, y))
                    all++
                    if (a >= 200 && maxOf(r, g, b) < 80) dark++
                    if (a >= 200 && minOf(r, g, b) > 200) light++
                }
            }
            assertTrue("drawable-$name: ${dark * 100 / all}% of \"Quote\" is dark", dark * 5 > all)
            assertTrue("drawable-$name: ${light * 100 / all}% of \"Quote\" is white", light * 20 < all)
        }
    }

    @Test
    fun `the old SIE launcher PNG is gone`() {
        assertFalse(File(res, "drawable/ic_launcher.png").exists())
        val leftovers = res.listFiles().orEmpty()
            .filter { it.name.startsWith("drawable") }
            .flatMap { folder -> folder.listFiles().orEmpty().filter { it.name.startsWith("ic_launcher") }.map { "${folder.name}/${it.name}" } }
        assertEquals(emptyList<String>(), leftovers)
    }
}
