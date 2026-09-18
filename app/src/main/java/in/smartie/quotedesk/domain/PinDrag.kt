package `in`.smartie.quotedesk.domain

/**
 * Where a dragged pinned card lands.
 *
 * The geometry lives here rather than in the composable so it can be tested
 * without a device and without injecting gestures: a card takes a neighbour's
 * place once it has travelled past **half** of that neighbour, and the rows
 * are not all the same height, so the heights have to be walked one at a time.
 */
object PinDrag {

    /**
     * The index [from] should move to after being dragged [offsetY] pixels.
     *
     * [heights] are the measured row heights in the order they are drawn.
     * Returns [from] when the drag has not cleared half of its first
     * neighbour, so a tap-and-wobble never reorders anything.
     */
    fun targetIndex(heights: List<Int>, from: Int, offsetY: Float): Int {
        if (from !in heights.indices) return from
        var target = from
        var travelled = 0f
        when {
            offsetY > 0f -> {
                var next = from + 1
                while (next < heights.size) {
                    if (offsetY < travelled + heights[next] / 2f) break
                    target = next
                    travelled += heights[next]
                    next++
                }
            }

            offsetY < 0f -> {
                var previous = from - 1
                while (previous >= 0) {
                    if (offsetY > travelled - heights[previous] / 2f) break
                    target = previous
                    travelled -= heights[previous]
                    previous--
                }
            }
        }
        return target
    }
}
