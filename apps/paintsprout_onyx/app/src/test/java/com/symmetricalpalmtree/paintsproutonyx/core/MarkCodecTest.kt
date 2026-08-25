package com.symmetricalpalmtree.paintsproutonyx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What this suite is actually guarding.
 *
 * A round-trip test is the obvious thing to write here and the least useful thing to rely on. A
 * codec that writes big-endian and reads big-endian passes every round-trip in this file with a
 * smile, and it is wrong — wrong in the one way that cannot be discovered later and cannot be
 * fixed once marks exist on a tablet. So the tests below check the real bytes as well: the version
 * byte in the clear, the compression the format specifies, the flags byte, and the exact four
 * bytes a known coordinate turns into.
 *
 * The other half of the suite is about damage. A `.soil` file lives on an e-ink tablet in
 * somebody's bag for years. Rows get truncated, bits flip, and a decoder that hangs or that reads
 * a mark one channel out of step turns a bad row into a lost sketchbook. Every mangled input here
 * exists because there is a plausible way for a file to arrive in that state.
 */
class MarkCodecTest {

    // ------------------------------------------------- the family-compatibility proof

    /*
     * Blobs below were produced by Notesprout Paper's own StrokeCodec — its real source, compiled
     * and run, not a transcription of what it looks like it would emit. They are here because
     * nothing else in this file can prove the thing the format exists for.
     *
     * Every other byte in this suite was written by the very codec under test. That makes the rest
     * of it a check on internal consistency, and internal consistency is exactly what a codec that
     * has quietly drifted away from the family still has. Both halves of MarkCodec could agree on
     * a wrong endianness, a wrong flag bit, a wrong compression level, and every test above would
     * pass. These do not: they were written by a different program, and the only way to read them
     * is to be the format we said we are.
     *
     * That claim is a decision, not an accident. Paintsprout Onyx is not meant to open a Notesprout
     * notebook, and Notesprout is not meant to open a sketchbook — the vocabulary differs and the
     * tables differ. What is shared is the mark geometry itself, so that the two apps stay one
     * family at the layer where a drawing actually lives, and so a future importer between them is
     * a schema question rather than a reverse-engineering one.
     *
     * To regenerate: compile Paper's StrokeCodec.kt (paper-screen/…/core/StrokeCodec.kt) against a
     * JVM and print Base64 of encode(). Never hand-compute a fixture — a hand-computed fixture
     * proves only that the person computing it believed the same thing the codec does.
     */

    private fun paper(base64: String): ByteArray = java.util.Base64.getDecoder().decode(base64)

    @Test
    fun `a Paper blob with both channels decodes to the points Paper encoded`() {
        val d = MarkCodec.decode(paper("AXjaY2aAgwY7CO3hyMDw4QCQYQ/hBxxg4Eh0YWBwgPAlGoBsBQegejAfAN8qB3E="))

        assertEquals(4, d.size)
        assertBitsEqual(floatArrayOf(0f, 12.5f, -3.25f, 1024.75f), d.x, "x")
        assertBitsEqual(floatArrayOf(0f, -7.5f, 900.125f, 2.5f), d.y, "y")
        assertBitsEqual(floatArrayOf(0.25f, 0.5f, 0.75f, 1f), assertNotNull2(d.pressure), "pressure")
        assertBitsEqual(floatArrayOf(0f, 0f, 0f, 0f), assertNotNull2(d.tilt), "tilt")
    }

    @Test
    fun `a Paper blob with pressure only decodes with no tilt channel`() {
        val d = MarkCodec.decode(paper("AXjaY2RgaLAHYoezZ87YMjAwODAwLACyfeyATCD7gMOsmTPtALs7CvA="))

        assertEquals(3, d.size)
        assertBitsEqual(floatArrayOf(1f, 2f, 3f), d.x, "x")
        assertBitsEqual(floatArrayOf(4f, 5f, 6f), d.y, "y")
        assertBitsEqual(floatArrayOf(0.1f, 0.2f, 0.3f), assertNotNull2(d.pressure), "pressure")
        assertNull("Paper wrote no tilt channel", d.tilt)
    }

    @Test
    fun `a Paper blob with tilt only decodes with no pressure channel`() {
        val d = MarkCodec.decode(paper("AXjaY2JgaLBnYHBwYGBgANIMQLoBiB3sASOoAwA="))

        assertEquals(2, d.size)
        assertBitsEqual(floatArrayOf(1f, 2f), d.x, "x")
        assertBitsEqual(floatArrayOf(3f, 4f), d.y, "y")
        assertNull("Paper wrote no pressure channel", d.pressure)
        assertBitsEqual(floatArrayOf(0.5f, 0.75f), assertNotNull2(d.tilt), "tilt")
    }

    @Test
    fun `a Paper blob with bare geometry decodes with neither channel`() {
        val d = MarkCodec.decode(paper("AXjaY2BgUHBkYPgAxAuAWMEJABZOAtY="))

        assertEquals(2, d.size)
        assertBitsEqual(floatArrayOf(10f, 20f), d.x, "x")
        assertBitsEqual(floatArrayOf(30f, 40f), d.y, "y")
        assertNull(d.pressure)
        assertNull(d.tilt)
    }

    @Test
    fun `a Paper blob of a single point decodes to one point`() {
        val d = MarkCodec.decode(paper("AXjaY2Zg+OAAxAcYGBrsGYAAACT/A6M="))

        assertEquals(1, d.size)
        assertBitsEqual(floatArrayOf(7.5f), d.x, "x")
        assertBitsEqual(floatArrayOf(-7.5f), d.y, "y")
        assertBitsEqual(floatArrayOf(1f), assertNotNull2(d.pressure), "pressure")
        assertBitsEqual(floatArrayOf(0f), assertNotNull2(d.tilt), "tilt")
    }

    @Test
    fun `a Paper blob of an empty mark decodes to no points with both channels declared`() {
        // A pen-down that never moved and was cancelled. It has to survive as an empty mark rather
        // than as a decode failure, because a row that cannot be read is a row that stops a page
        // from loading.
        val d = MarkCodec.decode(paper("AXjaYwYAAAQABA=="))

        assertEquals(0, d.size)
        assertNotNull("the pressure channel was declared even with no points", d.pressure)
        assertNotNull("the tilt channel was declared even with no points", d.tilt)
    }

    @Test
    fun `a long Paper blob decodes every point in step`() {
        // 512 points. A stride or endianness error on a short mark can look like plausible noise;
        // over this many points it walks off the end or drifts visibly, which is the point of
        // testing a long one at all.
        val d = MarkCodec.decode(
            paper(
            "AXjaTdd7tFbTGsfxFznYbkUiole57ESiImlrlQgnB7nl2Ic3cjnC2Q65JMcj3aSbSirJq5vcUqS2" +
            "dJlrllR2qVBJ8m5SqbB1kyTnO0dzzGf2R6PP+O2xW2vNNcZ3rIMy+/9IJvwxreCcolX1W+53kvDX" +
            "3KJV47wH42TuILvT22Bhr1+y31U4P3dJRXvv6q35GX6+q3cxLsxtUDrIO2ntfgm/37sjrm62Xlfu" +
            "XYazZklFhXdP3NhMblfpPRgnhuvxHo2vNeWnHHzJfk/COdOg9GjvabjMjBpZ29tg4f+v712B+5on" +
            "azbyXo0Hcz3NvdfjEaZ0UBvvKpzn+tp778WTTEnRzd7V2mQyU7jeTt5FuNzU7dXFuzo2XH9X71p4" +
            "oeGavOvgZdxPL+96eLV5qKSfdzEucH+DvBvhTaay2zDvpriK+x3p3QLvNh3Kx3gnOJPy/L0vx9XS" +
            "eTsnebfHh6ZP1pzs3QEfkTZp8r53R1w95by8b8M10/Fls7074xPS0kHW+z5cJ605+RPvMpxNOV/v" +
            "R/Fpaa8ty7274+K0pGiV9zP47HRX8Vrvnrhxyvvg3Qc3Te++e4N3P9w8rdtri/cA3DJdNa7KezBO" +
            "Ut4f76G4bXpF5R7v4fiKlH+02u+RuD33c6D3aHxtyvvm/Sq+gfs7xHss7pg+VFLkPQGXcr9HeE/C" +
            "uZT30/st3Jn7r+E9Gd+bVnar6T0V38/zON57Gi5LeZ+9Z+BHeD51vGfix9MO5ad4z8bdeV6nehss" +
            "nH9973m4B8/vDO8FuCfvQ7H3Ityb59nQuwL35f1o5P0Z7sfzbey9AvfnfWni/SUeyPNu5r0aD+b9" +
            "ae79NR7C82/hvQ4P430q8a7EwzmPxHs9HsF5tPHeiEdxHpd5b8ajOY923j/hMZzHVd5VOM95tPfe" +
            "jsdyHtd478LjOY/rvH/HEzmPG7z34kmcx83ef+E3OY9bvA+4NJN5m/Mo3W85EE/mPG7zezU8hfPo" +
            "5PeD8Xucx51+PwRP4zzu9vuheDrnca/fi3A559HF74fjmZzHg34/Es/iPMr8fhSew3k87Pfq2HAe" +
            "Xf1eA1vO43G/H4vncx7d/F4TL+A8nvJ7LbwwDfmQ4/HiVPtRG1ekoR9yIl6aaj/q4GVp6IecjFek" +
            "2o+6+Is09EOyeGWq/aiHV6ehH1Ifr0m1H6fjtWnoh5yB16Xaj2JcSEM/pAH+LtV+NMTr09APORtv" +
            "SLUfjfCmNPRDzsWbU+3HeXhrGvoh5+OfU+1HU1yVhn5IM7wt1X5ciHekoR/SHO9KtR8t8O409EMu" +
            "xntS7UcJ3puGfsgleF+q/UhwxoZ+SGt8gNV+XIoPtKEf0hYfZLUfl+NqNvRD2uGDrfbjSvw3G/oh" +
            "V+FDrPajPT7Uhn7I1fgwq/24BhfZ0A+5Fh9utR8d8BE29EOux0da7ceN+Cgb+iE34aOt9qMjrm5D" +
            "P+QWXMNqP27Fx9jQDynFx1rtx224pg39kNvxcVb70QnXsqEfcgc+3mo/OuMTbOiH3IVrW+3HPfhE" +
            "G/oh9+KTrPbjPlzHhn5IF3yy1X48gE+xoR/yIK5rtR9lOGtDP+QhfKrVfjyM69nQD3kE17faj0fx" +
            "aTb0Qx7Dp1vtxxP4DBv6Id3wmVb70R0X29APeQo3sNqPp/FZNvQjEdzQaj+ewWfb0A/jfI4N/ZAe" +
            "uJEN/Uiexeda7UdP3NiGfhjn82zoh/TC59vQj6Q3bmK1H31wUxv6YZyb2dAP6YsvsKEfyXP4Qqv9" +
            "6Ieb29AP43yRDf2Q53ELG/qR9McXW+3HANzShn4Y5xIb+iED8SU29CMZhFtZ7cdgnNjQD+Pc2oZ+" +
            "yAu4jQ39SIbgS632Yyhua0M/jPNlNvRDhuHLbehH8iJuZ7Ufw/EVVj8/nK+0oR/yEr7Khn4kI/Df" +
            "rfZjJG5vQz+M89U29ENG4X/Y0I/kZXyN1X6Mxtfa0A/jfJ0N/ZBXcAcb+pGMwddb7cer+AYb+mGc" +
            "b7ShH5LHN9nQj+Q1fLPVfozFHW3oh3G+xYZ+yDj8Txv6kYzHt1rtxwRcakM/jPO/bOiHTMS32dCP" +
            "5HV8u9V+TMI5G/phnDvZ0A95A99hQz+SN/GdVvvxFu6s/TDOd2k/5G18t/YjeQffE/VjMr5X+2Gc" +
            "/639kHfxfdqPZAruEvVjKr5f+2GcH9B+yHv4Qe1H8j7+T9SPabhM+2GcH9J+yAf4v9qPZDp+OOrH" +
            "DPyI9sM4d9V+SDl+VPuRfIgfi/oxEz+u/TDOT2g/5CPcTfuRzMJPRv2YjbtrP4zzU9oPmYP/p/1I" +
            "5uKno34YLNoPg0W0H5KyP6P9SCz7M1E/5rH30H4YLD20HzKf/VntR/Ix+7NRPxaw99R+GCw9tR/y" +
            "CXsv7UeykL1X1I9F7L21HwZLb+2HLGbvE/XjU/Y+UT8q2PtG/cDSN+rHEvbnon4sZX8u6sdn7P2i" +
            "fmDpF/VjGfvzUT+Wsz8f9WMFe/+oH1j6R/34nH1A1I8v2AdE/fiSfWDUDywDo36sZB8U9WMV+6Co" +
            "H6vZB0f9wDI46sdX7C9E/VjD/kLUj6/Zh0T9wDIk6sda9qFRP75hHxr1Yx37sKgfWIZF/fiW/cWo" +
            "HwX2F6N+VLIPj/qBZXjUj+/YX4r68T37S1E/1rOPiPqBZUTUjx/YR0b92MA+MurHRvZRUT+wjIr6" +
            "sYn95agfP7K/HPVjM/voqB9YRkf92ML+StSPreyvRP34iX1M1A8sY6J+/Mz+atSPX9hfjfpRxZ6P" +
            "+oElH/XjV/bXon5sY38t6sd29rFRP7CMjfqxg31c1I+d7OOifuxiHx/1A8v4qB+/sU+I+rGbfULU" +
            "j9/ZJ0b9wDIx6sce9tejfvzB/nrUj73sk6J+YJkU9eNP9jeifuxjfyPqx1/sb0b9wPKm9iPJtKWR" +
            "2o88lreifhzA/rb2I4flbe2Hcfs72o/sgezvaD8EZyZrPwpun6z9SA5if1f7kcfybtSPauxTtB85" +
            "LFO0H8btU7Uf2YPZp2o/BGfe034U3P6e9iP5G/v72o88lvejfhzCPk37kcMyTfth3P6B9iN7KPsH" +
            "2g/Bmenaj4Lbp2s/ksPYZ2g/8lhmRP0oYi/XfuSwlGs/jNs/1H5kD2f/UPshODNT+1Fw+0ztR3IE" +
            "+0fajzyWj6J+HMk+S/uRwzJL+2HcPlv7kT2Kfbb2Q3Bmjvaj4PY52o/kaPa52o88lrlRP6qzG+1H" +
            "DovRfhi3p9qPbA32VPshOGO1HwW3W+1Hcgz7PO1HHsu8qB/Hss/XfuSwzNd+GLd/rP3I1mT/WPsh" +
            "OLNA+1Fw+wLtR3Ic+yfajzyWT6J+1GJfqP3IYVmo/TBuX6T9yB7Pvkj7ITizWPtRcPti7UdyAvun" +
            "2o88lk+jftRmr9B+5LBUaD+M25doP7Insi/RfgjOLNV+FNy+VPuRnMT+mfYjj+WzqB912JdpP3JY" +
            "lmk/jNuXaz+yJ7Mv134IzqzQfhTcvkL7kZzC/rn2I4/l86gfddm/0H7ksHyh/TBu/1L7kc2yf6n9" +
            "EJxZqf0ouH2l9iM5lX2V9iOPZVXUj3rsq7UfOSyrtR/G7V9pP7L12b/SfgjOrNF+FNy+RvuRnMb+" +
            "tfYjj+XrqB+ns6/VfuSwrNV+GLd/o/3InsH+jfZDcGad9qPg9nXaj+RM9m+1H3ks30bfH8XsBe1H" +
            "DktB+2HcXqn9yDZgr9R+CM58p/0ouP077UdyFvv32o88lu+j74+G7Ou1Hzks67Ufxu0/aD+yZ7P/" +
            "oP0QnNmg/Si4fYP2IzmHfaP2I49lY/T90Yh9k/Yjh2WT9sO4/UftR/Zc9h+1H4Izm7UfBbdv1n4k" +
            "jdm3aD/yWLZE3x/nsW/VfuSwbNV+GLf/pP3Ins/+k/ZDcOZn7UfB7T9rP5Im7L9oP/JYfom+P5qy" +
            "V2k/cliqtB/G7b9qP7LN2H/VfgjObNN+FNy+TfuRXMC+XfuRx7I9+v64kH2H9iOHZYf2w7h9p/Yj" +
            "25x9p/ZDcGaX9qPg9l3aj+Qi9t+0H3ksv0XfHy3Yd2s/clh2az+M23/XfmQvZv9d+yE4s0f7UXD7" +
            "Hu1H0pL9D+1HHssf0fdHCfte7UcOy17th3H7n9qP7CXsf2o/BGf2aT8Kbt+n/Uhasf+l/chj+Sv0" +
            "4/8loSZ+"
            )
        )

        assertEquals(512, d.size)
        val p = assertNotNull2(d.pressure)
        val t = assertNotNull2(d.tilt)
        for (i in 0 until 512) {
            assertEquals("x[$i]", i * 1.5f, d.x[i], 0f)
            assertEquals("y[$i]", i * -0.25f, d.y[i], 0f)
            assertEquals("pressure[$i]", (i % 100) / 100f, p[i], 0f)
            assertEquals("tilt[$i]", 0f, t[i], 0f)
        }
    }

    @Test
    fun `our bytes are Paper's bytes for the same mark`() {
        // The other direction, and the one that matters for a file we write: re-encoding the same
        // points must reproduce Paper's blob exactly. Decoding leniently while writing something
        // subtly different is how a format forks without anyone noticing.
        val ours = MarkCodec.encode(
            floatArrayOf(0f, 12.5f, -3.25f, 1024.75f),
            floatArrayOf(0f, -7.5f, 900.125f, 2.5f),
            floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
            floatArrayOf(0f, 0f, 0f, 0f),
        )

        assertArrayEquals2(paper("AXjaY2aAgwY7CO3hyMDw4QCQYQ/hBxxg4Eh0YWBwgPAlGoBsBQegejAfAN8qB3E="), ours)
    }

    private fun assertNotNull2(a: FloatArray?): FloatArray {
        assertNotNull("channel missing", a)
        return a!!
    }

    private fun assertArrayEquals2(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            fail(
                "blob differs from Paper's\n  expected: " +
                    java.util.Base64.getEncoder().encodeToString(expected) +
                    "\n  actual:   " + java.util.Base64.getEncoder().encodeToString(actual)
            )
        }
    }

    // ---------------------------------------------------------------- round trips

    @Test
    fun `a mark with neither pressure nor tilt survives the round trip`() {
        val x = floatArrayOf(10f, 20.5f, 31.25f)
        val y = floatArrayOf(-4f, 0f, 900.125f)

        val decoded = MarkCodec.decode(MarkCodec.encode(x, y))

        assertEquals(3, decoded.size)
        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        // Null, not an array of zeroes. "The pen never reported pressure" and "the pen reported no
        // pressure at all" would draw as two different marks, so the codec must not conflate them.
        assertNull("pressure channel was never written", decoded.pressure)
        assertNull("tilt channel was never written", decoded.tilt)
    }

    @Test
    fun `a mark with pressure only survives the round trip`() {
        val x = floatArrayOf(1f, 2f, 3f, 4f)
        val y = floatArrayOf(5f, 6f, 7f, 8f)
        val pressure = floatArrayOf(0f, 0.25f, 0.75f, 1f)

        val decoded = MarkCodec.decode(MarkCodec.encode(x, y, pressure = pressure))

        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        assertBitsEqual(pressure, assertNotNullArray(decoded.pressure, "pressure"), "pressure")
        assertNull("tilt channel was never written", decoded.tilt)
    }

    @Test
    fun `a mark with tilt only survives the round trip`() {
        val x = floatArrayOf(1f, 2f, 3f)
        val y = floatArrayOf(5f, 6f, 7f)
        val tilt = floatArrayOf(-1.5f, 0f, 89.5f)

        val decoded = MarkCodec.decode(MarkCodec.encode(x, y, tilt = tilt))

        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        assertNull("pressure channel was never written", decoded.pressure)
        assertBitsEqual(tilt, assertNotNullArray(decoded.tilt, "tilt"), "tilt")
    }

    @Test
    fun `a mark with both channels survives the round trip`() {
        val n = 64
        val x = FloatArray(n) { it * 1.5f }
        val y = FloatArray(n) { 100f - it * 0.25f }
        val pressure = FloatArray(n) { it / n.toFloat() }
        val tilt = FloatArray(n) { (it % 90).toFloat() }

        val decoded = MarkCodec.decode(MarkCodec.encode(x, y, pressure, tilt))

        assertEquals(n, decoded.size)
        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        assertBitsEqual(pressure, assertNotNullArray(decoded.pressure, "pressure"), "pressure")
        assertBitsEqual(tilt, assertNotNullArray(decoded.tilt, "tilt"), "tilt")
    }

    @Test
    fun `a mark of one point survives the round trip`() {
        // A dot. The pen came down and went up again, which is a legal mark and one an artist makes
        // constantly — stippling is nothing but this.
        val decoded = MarkCodec.decode(
            MarkCodec.encode(floatArrayOf(42.5f), floatArrayOf(-7.25f), floatArrayOf(0.5f), floatArrayOf(30f)),
        )

        assertEquals(1, decoded.size)
        assertBitsEqual(floatArrayOf(42.5f), decoded.x, "x")
        assertBitsEqual(floatArrayOf(-7.25f), decoded.y, "y")
        assertBitsEqual(floatArrayOf(0.5f), assertNotNullArray(decoded.pressure, "pressure"), "pressure")
        assertBitsEqual(floatArrayOf(30f), assertNotNullArray(decoded.tilt, "tilt"), "tilt")
    }

    @Test
    fun `a mark of no points is still a legal mark`() {
        // An empty mark should never reach the file, but a writer that races a pen-up can produce
        // one, and the reader's job is to hand back an empty mark rather than to throw and take the
        // rest of the page down with it.
        val decoded = MarkCodec.decode(MarkCodec.encode(FloatArray(0), FloatArray(0)))

        assertEquals(0, decoded.size)
        assertNull(decoded.pressure)
        assertNull(decoded.tilt)
    }

    @Test
    fun `an empty mark still reports the channels it declared`() {
        val decoded = MarkCodec.decode(
            MarkCodec.encode(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0)),
        )

        assertEquals(0, decoded.size)
        assertEquals(0, assertNotNullArray(decoded.pressure, "pressure").size)
        assertEquals(0, assertNotNullArray(decoded.tilt, "tilt").size)
    }

    @Test
    fun `a long mark survives the round trip`() {
        // A slow deliberate stroke across a 2480 px panel is tens of thousands of samples. This is
        // the size at which an off-by-one stride stops being subtle and at which an allocation per
        // point would start to matter.
        val n = 100_000
        val x = FloatArray(n) { (it % 1860).toFloat() + 0.5f }
        val y = FloatArray(n) { (it % 2480).toFloat() - 0.25f }
        val pressure = FloatArray(n) { (it % 1024) / 1023f }
        val tilt = FloatArray(n) { (it % 180) - 90f }

        val blob = MarkCodec.encode(x, y, pressure, tilt)
        val decoded = MarkCodec.decode(blob)

        assertEquals(n, decoded.size)
        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        assertBitsEqual(pressure, assertNotNullArray(decoded.pressure, "pressure"), "pressure")
        assertBitsEqual(tilt, assertNotNullArray(decoded.tilt, "tilt"), "tilt")
    }

    @Test
    fun `strange floats come back exactly as they went in`() {
        // Nothing should ever hand this codec a NaN coordinate, and something eventually will — a
        // divide in a smoothing pass, a driver reporting a dud sample. Storing it verbatim keeps
        // the blame where it belongs: the bad value is visible in the file, rather than quietly
        // becoming a zero that looks like a real point at the origin.
        val x = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 0.0f)
        val y = floatArrayOf(Float.MIN_VALUE, Float.MAX_VALUE, -0.0f, Float.NaN, 1f)
        val pressure = floatArrayOf(Float.NaN, 0f, 1f, Float.POSITIVE_INFINITY, -0.0f)

        val decoded = MarkCodec.decode(MarkCodec.encode(x, y, pressure = pressure))

        assertBitsEqual(x, decoded.x, "x")
        assertBitsEqual(y, decoded.y, "y")
        assertBitsEqual(pressure, assertNotNullArray(decoded.pressure, "pressure"), "pressure")
    }

    // ---------------------------------------------------------------- the bytes themselves

    @Test
    fun `the version byte sits in the clear at the front`() {
        val blob = MarkCodec.encode(floatArrayOf(1f), floatArrayOf(2f))

        assertEquals(
            "byte 0 must be the plaintext format-B version, readable without inflating anything",
            1.toByte(),
            blob[0],
        )
        assertEquals(1.toByte(), MarkCodec.VERSION_FLOAT32)
    }

    @Test
    fun `the payload is zlib at best compression`() {
        // 0x78 0xDA is zlib's own way of saying "deflate, 32K window, maximum effort". Asserting it
        // pins BEST_COMPRESSION as part of the wire format rather than a setting someone may later
        // decide to relax for speed — marks are written once and read for years.
        val blob = MarkCodec.encode(FloatArray(500) { it.toFloat() }, FloatArray(500) { it.toFloat() })

        assertEquals("zlib CMF byte", 0x78.toByte(), blob[1])
        assertEquals("zlib FLG byte for BEST_COMPRESSION", 0xDA.toByte(), blob[2])
    }

    @Test
    fun `coordinates are written little-endian`() {
        // 1.0f is 0x3F800000. Little-endian puts the 0x3F last, and this assertion is the whole
        // reason this file is not just a pile of round-trips: a codec that is big-endian on both
        // sides round-trips perfectly and is unreadable by every other member of the family.
        val payload = payloadOf(MarkCodec.encode(floatArrayOf(1.0f), floatArrayOf(2.0f)))

        assertEquals("payload is flags + one 8-byte point", 9, payload.size)
        assertEquals("x low byte", 0x00.toByte(), payload[1])
        assertEquals(0x00.toByte(), payload[2])
        assertEquals(0x80.toByte(), payload[3])
        assertEquals("x high byte last", 0x3F.toByte(), payload[4])
        // 2.0f is 0x40000000.
        assertEquals("y low byte", 0x00.toByte(), payload[5])
        assertEquals(0x00.toByte(), payload[6])
        assertEquals(0x00.toByte(), payload[7])
        assertEquals("y high byte last", 0x40.toByte(), payload[8])
    }

    @Test
    fun `the flags byte names exactly the channels that were written`() {
        val x = floatArrayOf(0f)
        val y = floatArrayOf(0f)
        val one = floatArrayOf(1f)

        assertEquals(0x00.toByte(), payloadOf(MarkCodec.encode(x, y))[0])
        assertEquals(0x01.toByte(), payloadOf(MarkCodec.encode(x, y, pressure = one))[0])
        assertEquals(0x02.toByte(), payloadOf(MarkCodec.encode(x, y, tilt = one))[0])
        assertEquals(0x03.toByte(), payloadOf(MarkCodec.encode(x, y, one, one))[0])
    }

    @Test
    fun `the stride follows the flags`() {
        val n = 7
        val x = FloatArray(n) { it.toFloat() }
        val y = FloatArray(n) { it.toFloat() }
        val c = FloatArray(n) { it.toFloat() }

        assertEquals(1 + n * 8, payloadOf(MarkCodec.encode(x, y)).size)
        assertEquals(1 + n * 12, payloadOf(MarkCodec.encode(x, y, pressure = c)).size)
        assertEquals(1 + n * 12, payloadOf(MarkCodec.encode(x, y, tilt = c)).size)
        assertEquals(1 + n * 16, payloadOf(MarkCodec.encode(x, y, c, c)).size)
    }

    // ---------------------------------------------------------------- damage

    @Test
    fun `a partial trailing point is dropped rather than misread`() {
        // Built by hand, because this is the shape a half-finished write leaves behind and the
        // encoder will never produce it: two whole points followed by three orphan bytes.
        val body = ByteBuffer.allocate(1 + 2 * 8 + 3).order(ByteOrder.LITTLE_ENDIAN)
        body.put(0x00)
        body.putFloat(1f); body.putFloat(2f)
        body.putFloat(3f); body.putFloat(4f)
        body.put(0x11); body.put(0x22); body.put(0x33)

        val decoded = MarkCodec.decode(blobOf(body.array()))

        assertEquals("the orphan bytes must not become a third point", 2, decoded.size)
        assertBitsEqual(floatArrayOf(1f, 3f), decoded.x, "x")
        assertBitsEqual(floatArrayOf(2f, 4f), decoded.y, "y")
    }

    @Test
    fun `an unknown flag bit is ignored and the channels this build knows still read`() {
        // A flag bit nobody here has heard of. It is deliberately NOT treated as tilt and NOT
        // treated as a reason to refuse the mark: the channels this build understands are read at
        // the stride those channels imply, and the mark comes back drawable.
        //
        // Worth being honest about the limit of that. This only holds while the unknown bit added
        // no bytes; a genuine third channel would widen the real stride and this reader would walk
        // it wrong. That is why a new channel is a new version byte, not a spare flag bit — the
        // version is the thing a reader can refuse, and a flag bit is not.
        val body = ByteBuffer.allocate(1 + 3 * 12).order(ByteOrder.LITTLE_ENDIAN)
        body.put(0x05) // pressure, plus a bit from the future
        for (i in 0 until 3) {
            body.putFloat(i.toFloat()); body.putFloat(i + 10f); body.putFloat(0.5f)
        }

        val decoded = MarkCodec.decode(blobOf(body.array()))

        assertEquals(3, decoded.size)
        assertBitsEqual(floatArrayOf(0f, 1f, 2f), decoded.x, "x")
        assertBitsEqual(floatArrayOf(10f, 11f, 12f), decoded.y, "y")
        assertBitsEqual(
            floatArrayOf(0.5f, 0.5f, 0.5f),
            assertNotNullArray(decoded.pressure, "pressure"),
            "pressure",
        )
        assertNull("the unknown bit is not tilt", decoded.tilt)
    }

    @Test(timeout = 10_000)
    fun `a truncated payload gives back a prefix of the mark, or nothing, but never garbage`() {
        val n = 500
        val x = FloatArray(n) { it * 1.5f }
        val y = FloatArray(n) { it * -0.5f }
        val blob = MarkCodec.encode(x, y)

        // Several cut points, because how much survives depends on where the deflate stream was
        // severed and the guarantee has to hold at all of them.
        for (cut in intArrayOf(1, 2, 3, blob.size / 4, blob.size / 2, blob.size - 1)) {
            val truncated = blob.copyOf(cut)
            val decoded = try {
                MarkCodec.decode(truncated)
            } catch (expected: Throwable) {
                continue // Refusing the row outright is a perfectly good answer.
            }
            assertTrue(
                "a truncated mark must not grow points (cut at $cut)",
                decoded.size <= n,
            )
            for (i in 0 until decoded.size) {
                assertEquals("x[$i] after cut at $cut", bits(x[i]), bits(decoded.x[i]))
                assertEquals("y[$i] after cut at $cut", bits(y[i]), bits(decoded.y[i]))
            }
        }
    }

    @Test(timeout = 10_000)
    fun `a zlib header with FDICT set is refused instead of spun on forever`() {
        // 0x78 0x20 is a header whose checksum is valid and whose FDICT bit says "a preset
        // dictionary follows". There is no dictionary, so inflate() returns 0 bytes and asks for
        // nothing, round after round. The naive loop here is a hot thread that never ends: not a
        // bad mark, a sketchbook that can never be opened. One flipped bit in flash is enough to
        // produce this, which is why it is a named test and not a curiosity.
        val corrupt = ByteArray(64)
        corrupt[0] = MarkCodec.VERSION_FLOAT32
        corrupt[1] = 0x78
        corrupt[2] = 0x20
        for (i in 3 until corrupt.size) corrupt[i] = (i * 7).toByte()

        assertRefusedOrEmpty(corrupt)
    }

    @Test(timeout = 10_000)
    fun `payload bytes that are not zlib at all are refused`() {
        val garbage = ByteArray(64) { 0xFF.toByte() }
        garbage[0] = MarkCodec.VERSION_FLOAT32

        assertRefusedOrEmpty(garbage)
    }

    @Test
    fun `an unknown version byte is refused rather than guessed at`() {
        val blob = MarkCodec.encode(floatArrayOf(1f), floatArrayOf(2f))
        for (version in byteArrayOf(0, 2, 9, 127, -1)) {
            val foreign = blob.copyOf()
            foreign[0] = version
            try {
                MarkCodec.decode(foreign)
                fail("version $version should not have decoded")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the error should name the version so a log can explain itself",
                    expected.message.orEmpty().contains(version.toString()),
                )
            }
        }
    }

    @Test
    fun `an empty blob is refused`() {
        try {
            MarkCodec.decode(ByteArray(0))
            fail("an empty blob is not a mark")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `channel arrays that do not match the points are refused at the door`() {
        val x = floatArrayOf(1f, 2f, 3f)
        val y = floatArrayOf(1f, 2f, 3f)

        assertRejects { MarkCodec.encode(x, floatArrayOf(1f, 2f)) }
        assertRejects { MarkCodec.encode(x, y, pressure = floatArrayOf(1f)) }
        assertRejects { MarkCodec.encode(x, y, tilt = FloatArray(4)) }
    }

    // ---------------------------------------------------------------- helpers

    /** Bit-exact float comparison — the only kind that can tell -0.0f from 0.0f, or NaN from NaN. */
    private fun bits(f: Float): Int = java.lang.Float.floatToRawIntBits(f)

    private fun assertBitsEqual(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals("$label length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$label[$i]", bits(expected[i]), bits(actual[i]))
        }
    }

    private fun assertNotNullArray(array: FloatArray?, label: String): FloatArray {
        assertNotNull("$label channel should be present", array)
        return array!!
    }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            fail("expected the encoder to refuse mismatched channel lengths")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun assertRefusedOrEmpty(blob: ByteArray) {
        val decoded = try {
            MarkCodec.decode(blob)
        } catch (expected: Throwable) {
            return // Refusing the row is the preferred answer; returning promptly is acceptable.
        }
        assertEquals("a corrupt payload must not produce points", 0, decoded.size)
    }

    /** The inflated payload of a blob this codec wrote — flags byte first. */
    private fun payloadOf(blob: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(blob, 1, blob.size - 1)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) break
                out.write(chunk, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /** Wrap a hand-built payload as a format-B blob, so damaged shapes can be fed to the decoder. */
    private fun blobOf(payload: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(payload)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        val compressed = out.toByteArray()
        val blob = ByteArray(1 + compressed.size)
        blob[0] = MarkCodec.VERSION_FLOAT32
        System.arraycopy(compressed, 0, blob, 1, compressed.size)
        return blob
    }
}
