package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSizeTest {
    @Test
    fun `missing and invalid preferences use standard size`() {
        assertEquals(BubbleSize.DEFAULT_PERCENT, BubbleSize.preferencePercent(null))
        assertEquals(BubbleSize.DEFAULT_PERCENT, BubbleSize.preferencePercent(89))
        assertEquals(BubbleSize.DEFAULT_PERCENT, BubbleSize.preferencePercent(96))
        assertEquals(BubbleSize.DEFAULT_PERCENT, BubbleSize.preferencePercent(155))
    }

    @Test
    fun `default sizing preserves original overlay dimensions`() {
        val dimensions = BubbleSize.dimensions(BubbleSize.DEFAULT_PERCENT)

        assertEquals(44, dimensions.buttonDp)
        assertEquals(56, dimensions.ringDp)
        assertEquals(10, dimensions.paddingDp)
        assertEquals(10, dimensions.tapThresholdDp)
        assertEquals(64, dimensions.feedbackOffsetDp)
    }

    @Test
    fun `range scales dimensions while maintaining a 48dp touch target`() {
        val minimum = BubbleSize.dimensions(BubbleSize.MIN_PERCENT)
        val maximum = BubbleSize.dimensions(BubbleSize.MAX_PERCENT)

        assertEquals(40, minimum.buttonDp)
        assertEquals(50, minimum.ringDp)
        assertEquals(9, minimum.paddingDp)
        assertEquals(66, maximum.buttonDp)
        assertEquals(84, maximum.ringDp)
        assertTrue(minimum.ringDp >= BubbleSize.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `slider values are bounded and snapped to five percent increments`() {
        assertEquals(BubbleSize.MIN_PERCENT, BubbleSize.sliderPercent(0))
        assertEquals(90, BubbleSize.sliderPercent(92))
        assertEquals(95, BubbleSize.sliderPercent(93))
        assertEquals(150, BubbleSize.sliderPercent(200))
        assertEquals(115, BubbleSize.preferencePercent(115))
    }

    @Test
    fun `resizing clamps position and edge snapping uses updated ring size`() {
        assertEquals(
            BubbleSize.Position(8, 2308),
            BubbleSize.clampPosition(-20, 5000, 1080, 2400, 84, 8)
        )
        assertEquals(8, BubbleSize.snappedX(300, 84, 1080, 8))
        assertEquals(988, BubbleSize.snappedX(900, 84, 1080, 8))
    }

    @Test
    fun `position percent maps onto the free height and keeps the ring on screen`() {
        // 2400 px screen, 84 px ring, 8 px margin: free range is y 8..2308.
        assertEquals(8, BubbleSize.positionFor(0, false, 1080, 2400, 84, 8).y)
        assertEquals(1158, BubbleSize.positionFor(50, false, 1080, 2400, 84, 8).y)
        assertEquals(2308, BubbleSize.positionFor(100, false, 1080, 2400, 84, 8).y)
    }

    @Test
    fun `position percent outside 0 to 100 is clamped`() {
        assertEquals(8, BubbleSize.positionFor(-20, true, 1080, 2400, 84, 8).y)
        assertEquals(2308, BubbleSize.positionFor(250, true, 1080, 2400, 84, 8).y)
        assertEquals(50, BubbleSize.positionPercent(null))
    }

    @Test
    fun `side switch picks the left or right edge`() {
        assertEquals(8, BubbleSize.positionFor(50, true, 1080, 2400, 84, 8).x)
        assertEquals(988, BubbleSize.positionFor(50, false, 1080, 2400, 84, 8).x)
    }

    @Test
    fun `a screen shorter than the ring never gives a negative position`() {
        val position = BubbleSize.positionFor(100, false, 1080, 90, 84, 8)
        assertTrue(position.y >= 8)
        assertTrue(position.x >= 8)
    }

    @Test
    fun `selection is described in relative terms`() {
        assertEquals("100% · standard", BubbleSize.valueLabel(100))
        assertEquals("90% · smaller", BubbleSize.valueLabel(90))
        assertEquals("150% · larger", BubbleSize.valueLabel(150))
        assertEquals("150 percent, larger than standard", BubbleSize.accessibilityDescription(150))
    }
}
