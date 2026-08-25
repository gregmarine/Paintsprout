package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a sketchbook on the tablet that did not draw it.
 *
 * The two here are the real ones: the MovinkPad 11 at 2200 × 1440 and ~229.5 PPI,
 * and the Movink 14 Pro at 2880 × 1800 and its measured 242.69.
 */
class PageSpaceTest {

    // A 5 × 7 sheet, landscape, in each tablet's view px.
    private val on11W = 7f * 229.5f // 1606.5
    private val on11H = 5f * 229.5f // 1147.5
    private val on14W = 7f * 242.69312f // 1698.9
    private val on14H = 5f * 242.69312f // 1213.5

    @Test
    fun `a page with no recorded size is opened exactly as it always was`() {
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(null, null, 2880f, 1800f))
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(null, 1440f, 2880f, 1800f))
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(2200f, null, 2880f, 1800f))
        assertTrue(PageSpace.fit(null, null, 2880f, 1800f).isIdentity)
    }

    /** Nonsense in, this device's own space out — never a guess. */
    @Test
    fun `a nonsense size is refused rather than divided by`() {
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(0f, 0f, 2880f, 1800f))
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(-8f, 4f, 2880f, 1800f))
        assertEquals(PageSpace.IDENTITY, PageSpace.fit(2200f, 1440f, 0f, 0f))
    }

    @Test
    fun `the same tablet is the identity, so nothing moves`() {
        assertTrue(PageSpace.fit(on14W, on14H, on14W, on14H).isIdentity)
    }

    /**
     * A print has the same shape on both tablets, so it fills the sheet and there
     * is nothing to centre — the whole conversion is the PPI ratio.
     */
    @Test
    fun `a print sheet scales by the ratio of the two screens and leaves no margin`() {
        val to14 = PageSpace.fit(on11W, on11H, on14W, on14H)
        assertEquals(242.69312f / 229.5f, to14.scale, 0.0001f)
        assertEquals(0f, to14.dx, 0.01f)
        assertEquals(0f, to14.dy, 0.01f)

        // A mark at the far corner of the 11's sheet lands on the far corner of
        // the 14's, which is the whole point: the artwork keeps the paper it had.
        assertEquals(on14W, to14.x(on11W), 0.01f)
        assertEquals(on14H, to14.y(on11H), 0.01f)
    }

    /**
     * Full screen is the case that cannot come out clean: 2200 × 1440 is 1.528
     * and 2880 × 1800 is 1.600, so the sheet is fitted and the leftover split.
     */
    @Test
    fun `a full-screen page is fitted and centred rather than stretched`() {
        val to14 = PageSpace.fit(2200f, 1440f, 2880f, 1800f)
        assertEquals(1800f / 1440f, to14.scale, 0.0001f) // height binds
        assertEquals(0f, to14.dy, 0.01f)
        assertEquals((2880f - 2200f * 1.25f) / 2f, to14.dx, 0.01f) // 65 px each side
        assertFalse(to14.isIdentity)

        // Fitted, so nothing leaves the panel and nothing is distorted.
        assertTrue(to14.x(2200f) <= 2880f)
        assertTrue(to14.y(1440f) <= 1800f)
    }

    @Test
    fun `going back undoes going out`() {
        for (space in listOf(
            PageSpace.fit(on11W, on11H, on14W, on14H),
            PageSpace.fit(2200f, 1440f, 2880f, 1800f),
            PageSpace.fit(2880f, 1800f, 2200f, 1440f),
        )) {
            val back = space.inverse()
            assertEquals(300f, back.x(space.x(300f)), 0.01f)
            assertEquals(120f, back.y(space.y(120f)), 0.01f)
            assertEquals(4.5f, back.length(space.length(4.5f)), 0.0001f)
        }
    }

    @Test
    fun `the identity inverts to itself rather than to a negative zero`() {
        assertTrue(PageSpace.IDENTITY.inverse().isIdentity)
        assertEquals(PageSpace.IDENTITY, PageSpace.IDENTITY.inverse())
    }

    /** A width is scaled but never offset — an offset width is not a width. */
    @Test
    fun `a length takes the scale and not the centring`() {
        val fit = PageSpace.fit(2200f, 1440f, 2880f, 1800f)
        assertEquals(65f, fit.dx, 0.01f)
        assertEquals(5f * 1.25f, fit.length(5f), 0.0001f)
    }

    /**
     * A move op's transform is conjugated, not merely translated: the rotation and
     * the scale mean the same in either space, and only where the thing sits
     * changes. Verified by going the long way round — transform a point through
     * the converted matrix, and through the original matrix in the original space.
     */
    @Test
    fun `a transform survives the change of space`() {
        val fit = PageSpace.fit(2200f, 1440f, 2880f, 1800f)
        // A half-scale about the origin plus a translation of (40, 90).
        val m = floatArrayOf(
            0.5f, 0f, 40f,
            0f, 0.5f, 90f,
            0f, 0f, 1f,
        )
        val m2 = fit.matrix(m)

        // The linear part is untouched.
        assertEquals(0.5f, m2[0], 0.0001f)
        assertEquals(0.5f, m2[4], 0.0001f)
        assertEquals(0f, m2[1], 0.0001f)
        assertEquals(0f, m2[3], 0.0001f)

        // And the two routes to the same pixel agree: mapping in the old space
        // then converting, versus converting then mapping in the new one.
        for ((px, py) in listOf(0f to 0f, 500f to 300f, 2199f to 1439f)) {
            val oldX = m[0] * px + m[1] * py + m[2]
            val oldY = m[3] * px + m[4] * py + m[5]
            val viaOld = fit.x(oldX) to fit.y(oldY)

            val nx = fit.x(px)
            val ny = fit.y(py)
            val viaNew = (m2[0] * nx + m2[1] * ny + m2[2]) to (m2[3] * nx + m2[4] * ny + m2[5])

            assertEquals(viaOld.first, viaNew.first, 0.01f)
            assertEquals(viaOld.second, viaNew.second, 0.01f)
        }
    }

    @Test
    fun `a rotation is conjugated too`() {
        val fit = PageSpace.fit(2200f, 1440f, 2880f, 1800f)
        // 90° about the origin.
        val m = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val m2 = fit.matrix(m)
        assertEquals(0f, m2[0], 0.0001f)
        assertEquals(-1f, m2[1], 0.0001f)
        assertEquals(1f, m2[3], 0.0001f)
        assertEquals(0f, m2[4], 0.0001f)

        val px = 400f
        val py = 250f
        val oldX = -py
        val oldY = px
        assertEquals(fit.x(oldX), m2[0] * fit.x(px) + m2[1] * fit.y(py) + m2[2], 0.01f)
        assertEquals(fit.y(oldY), m2[3] * fit.x(px) + m2[4] * fit.y(py) + m2[5], 0.01f)
    }

    /** A short matrix is handed back rather than read past the end. */
    @Test
    fun `a malformed matrix is left alone`() {
        val short = floatArrayOf(1f, 2f, 3f)
        assertTrue(short === PageSpace.fit(2200f, 1440f, 2880f, 1800f).matrix(short))
    }
}
