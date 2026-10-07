package `in`.smartie.quotedesk.branding

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * A PNG's pixels, read with nothing but `java.util.zip`.
 *
 * Android unit tests compile against Android's API surface, which has no
 * `javax.imageio` and no `java.awt.image` — run #283 failed to compile on
 * exactly that. So this reads the one kind of PNG `tools/branding/make-assets.py`
 * writes: 8 bits a channel, RGBA or RGB, not interlaced. Anything else is
 * refused rather than guessed at.
 */
class PngImage private constructor(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    private val argb: IntArray,
) {
    /** The pixel at ([x], [y]) as 0xAARRGGBB. */
    fun getRGB(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun read(file: File): PngImage {
            val input = DataInputStream(file.inputStream().buffered())
            input.use {
                val signature = ByteArray(8).also(input::readFully)
                require(signature.contentEquals(SIGNATURE)) { "$file is not a PNG" }
                var width = 0
                var height = 0
                var channels = 0
                val compressed = ByteArrayOutputStream()
                while (true) {
                    val length = input.readInt()
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    val data = ByteArray(length).also(input::readFully)
                    input.readInt() // CRC
                    when (type) {
                        "IHDR" -> {
                            val header = DataInputStream(data.inputStream())
                            width = header.readInt()
                            height = header.readInt()
                            val depth = header.readUnsignedByte()
                            val colourType = header.readUnsignedByte()
                            header.readUnsignedByte() // compression: always 0
                            header.readUnsignedByte() // filter method: always 0
                            val interlace = header.readUnsignedByte()
                            require(depth == 8 && interlace == 0) { "$file: depth $depth, interlace $interlace" }
                            channels = when (colourType) {
                                6 -> 4
                                2 -> 3
                                else -> error("$file: colour type $colourType is not RGB or RGBA")
                            }
                        }
                        "IDAT" -> compressed.write(data)
                        "IEND" -> break
                    }
                }
                val stride = width * channels
                val raw = inflate(compressed.toByteArray(), (stride + 1) * height)
                val pixels = unfilter(raw, stride, height, channels)
                val argb = IntArray(width * height) { i ->
                    val p = i * channels
                    val r = pixels[p].toInt() and 0xFF
                    val g = pixels[p + 1].toInt() and 0xFF
                    val b = pixels[p + 2].toInt() and 0xFF
                    val a = if (channels == 4) pixels[p + 3].toInt() and 0xFF else 0xFF
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                return PngImage(width, height, channels == 4, argb)
            }
        }

        private fun inflate(data: ByteArray, size: Int): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArray(size)
            var done = 0
            while (done < size && !inflater.finished()) {
                done += inflater.inflate(out, done, size - done)
            }
            inflater.end()
            require(done == size) { "the image data is $done bytes, not $size" }
            return out
        }

        /** Undoes each row's filter (PNG specification, section 9). */
        private fun unfilter(raw: ByteArray, stride: Int, height: Int, bpp: Int): ByteArray {
            val out = ByteArray(stride * height)
            for (y in 0 until height) {
                val filter = raw[y * (stride + 1)].toInt()
                val from = y * (stride + 1) + 1
                val row = y * stride
                for (i in 0 until stride) {
                    val x = raw[from + i].toInt() and 0xFF
                    val a = if (i >= bpp) out[row + i - bpp].toInt() and 0xFF else 0
                    val b = if (y > 0) out[row - stride + i].toInt() and 0xFF else 0
                    val c = if (y > 0 && i >= bpp) out[row - stride + i - bpp].toInt() and 0xFF else 0
                    val value = when (filter) {
                        0 -> x
                        1 -> x + a
                        2 -> x + b
                        3 -> x + (a + b) / 2
                        4 -> x + paeth(a, b, c)
                        else -> error("row $y: filter $filter")
                    }
                    out[row + i] = value.toByte()
                }
            }
            return out
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a)
            val pb = abs(p - b)
            val pc = abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}
