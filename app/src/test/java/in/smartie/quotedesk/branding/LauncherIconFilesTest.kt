package `in`.smartie.quotedesk.branding

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher icon, checked as files (N5.12b).
 *
 * `tools/branding/make-assets.py` makes every one of them from the Owner's
 * 1024 PNGs in `branding/`; this pins what it made. Plain JVM — [PngImage]
 * reads the PNGs — so it runs without Android. The adaptive icon as Android
 * inflates it is `LauncherIconTest`'s.
 */
class LauncherIconFilesTest {

    // Gradle runs unit tests in app/; a run from the repository root works too.
    private val module = listOf(File("app"), File(".")).first { File(it, "src/main/res").isDirectory }
    private val res = File(module, "src/main/res")
    private val branding = File(module.absoluteFile, "../branding").normalize()

    private val densities = listOf(
        "mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0,
    )

    private fun png(folder: String, name: String): PngImage = PngImage.read(File(res, "$folder/$name"))

    private fun alpha(image: PngImage, x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    /** Pixels at least [opacity] opaque, out of 255 — by default, any that is not fully transparent. */
    private fun visible(image: PngImage, opacity: Int = 1): List<Pair<Int, Int>> =
        (0 until image.height).flatMap { y ->
            (0 until image.width).filter { x -> alpha(image, x, y) >= opacity }.map { x -> x to y }
        }

    /** From the image's centre to the farthest corner of any pixel that is not fully transparent. */
    private fun reach(image: PngImage): Double {
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        return visible(image).maxOf { (x, y) ->
            hypot(max(abs(x - cx), abs(x + 1 - cx)), max(abs(y - cy), abs(y + 1 - cy)))
        }
    }

    /**
     * left, top, right, bottom of the pixels at least half opaque — the
     * artwork's edge, not the faint fringe the scaling leaves round it.
     */
    private fun box(image: PngImage): List<Int> = visible(image, opacity = 128).let { pixels ->
        listOf(pixels.minOf { it.first }, pixels.minOf { it.second },
            pixels.maxOf { it.first }, pixels.maxOf { it.second })
    }

    @Test
    fun `every density has the four files, at their sizes, with transparency`() {
        for ((name, scale) in densities) {
            val folder = "mipmap-$name"
            for ((file, dp) in listOf(
                "ic_launcher_foreground.png" to 108, "ic_launcher_monochrome.png" to 108,
                "ic_launcher.png" to 48, "ic_launcher_round.png" to 48,
            )) {
                val image = png(folder, file)
                val size = (dp * scale).toInt()
                assertEquals("$folder/$file width", size, image.width)
                assertEquals("$folder/$file height", size, image.height)
                assertTrue("$folder/$file has an alpha channel", image.hasAlpha)
            }
        }
    }

    @Test
    fun `the mark and its silhouette stay inside the 66dp safe circle at every density`() {
        // A layer is 108dp; every launcher mask leaves at least the 66dp circle
        // in its middle. "Inside" counts the faintest pixel the scaling leaves.
        for ((name, scale) in densities) {
            for (file in listOf("ic_launcher_foreground.png", "ic_launcher_monochrome.png")) {
                val reach = reach(png("mipmap-$name", file)) / scale
                assertTrue("mipmap-$name/$file reaches ${"%.2f".format(reach)}dp of 33", reach <= 33.0)
            }
        }
    }

    @Test
    fun `the mark is drawn at 44dp, not shrunk out of sight`() {
        // The mark's own artwork fills 952 of its 1024 pixels' width, so at
        // 44dp it shows about 40.9dp wide, in the middle of the 108dp layer;
        // measured to its half-opaque edge, 40 to 40.7dp across the densities.
        for ((name, scale) in densities) {
            val (left, top, right, bottom) = box(png("mipmap-$name", "ic_launcher_foreground.png"))
            val width = (right - left + 1) / scale
            assertTrue("mipmap-$name mark ${"%.2f".format(width)}dp wide", width in 39.0..42.0)
            val middle = (left + right + 1) / 2.0 / scale
            assertTrue("mipmap-$name mark centred, at ${"%.2f".format(middle)}dp", abs(middle - 54.0) <= 1.0)
            assertTrue("mipmap-$name mark's top", top / scale in 32.0..35.0)
            assertTrue("mipmap-$name mark's bottom", (bottom + 1) / scale in 73.0..76.0)
        }
    }

    @Test
    fun `the themed icon's silhouette sits exactly where the colour mark does`() {
        for ((name, _) in densities) {
            val mark = box(png("mipmap-$name", "ic_launcher_foreground.png"))
            val silhouette = box(png("mipmap-$name", "ic_launcher_monochrome.png"))
            mark.zip(silhouette).forEach { (a, b) ->
                assertTrue("mipmap-$name: mark $mark, silhouette $silhouette", abs(a - b) <= 1)
            }
        }
    }

    @Test
    fun `the old launchers get the mark on a #F7F4FF shape - square, and round`() {
        for ((name, scale) in densities) {
            fun at(dp: Double) = (dp * scale).toInt()
            val square = png("mipmap-$name", "ic_launcher.png")
            val round = png("mipmap-$name", "ic_launcher_round.png")
            for ((label, icon) in listOf("square" to square, "round" to round)) {
                assertEquals("$name $label: 2dp clear at the corner", 0, alpha(icon, at(0.5), at(0.5)))
                // Between the shape's top edge, at 2dp, and the mark, from 11dp.
                assertEquals("$name $label: the shape is #F7F4FF", 0xFFF7F4FF.toInt(), icon.getRGB(at(24.0), at(6.0)))
            }
            // 6dp in from both edges: inside the square's rounded corner, outside the circle.
            assertEquals("$name square", 0xFFF7F4FF.toInt(), square.getRGB(at(6.0), at(6.0)))
            assertEquals("$name round", 0, alpha(round, at(6.0), at(6.0)))
        }
    }

    @Test
    fun `the adaptive icon is the mark on #F7F4FF, with the silhouette for themed icons`() {
        for (file in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val xml = File(res, "mipmap-anydpi-v26/$file").readText()
            assertTrue(file, xml.contains("<background android:drawable=\"@color/ic_launcher_background\" />"))
            assertTrue(file, xml.contains("<foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />"))
            assertTrue(file, xml.contains("<monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\" />"))
        }
        val colours = File(res, "values/ic_launcher_background.xml").readText()
        assertTrue(colours.contains("<color name=\"ic_launcher_background\">#F7F4FF</color>"))
    }

    @Test
    fun `the manifest uses the new icons, and takes its label from app_name`() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        // The words — "Quote Desk", and "Quote Desk Staging" on the staging
        // build — are AppNameTest's to pin.
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
    }

    @Test
    fun `the Owner's originals in branding are untouched`() {
        // SHA-256 of the files as uploaded in c9b2e3c. The generator only
        // reads them; a change here means somebody changed the artwork.
        val uploaded = mapOf(
            "QD_icon_mark_1024.png" to "31e64ff0a6382b54adefaadd10aab8c45ea9f728853054230f609bc2d7f8bfba",
            "QD_icon_monochrome_1024.png" to "89bee0c62a0b098f658ca2d546599fa5e84f3e3894d049d4d2cf5a66e52e69fe",
            "QD_full_colour_1024.png" to "da23bb22567b89c315dad8013ded3a976ad0053013dd6673f347ae7d3dddee28",
            "QD_full_whitetext_1024.png" to "a10ec38d4eed64506893dbec7f6f9b52f3cc0a46b6e50018e17f36e740348f44",
        )
        for ((file, sha) in uploaded) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File(branding, file).readBytes())
            assertEquals(file, sha, digest.joinToString("") { "%02x".format(it) })
        }
    }
}
