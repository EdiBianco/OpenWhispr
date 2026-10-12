package com.edib.openwhispr

import kotlin.math.roundToInt

object BubbleSize {
    const val PREFERENCE_KEY = "bubble_size_percent"
    const val MIN_PERCENT = 90
    const val DEFAULT_PERCENT = 100
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 5

    const val BUTTON_DP = 44
    const val RING_DP = 56
    const val PADDING_DP = 10
    const val MIN_TOUCH_TARGET_DP = 48
    private const val TAP_THRESHOLD_DP = 10
    private const val FEEDBACK_OFFSET_DP = 64

    data class Dimensions(
        val buttonDp: Int,
        val ringDp: Int,
        val paddingDp: Int,
        val tapThresholdDp: Int,
        val feedbackOffsetDp: Int
    )

    data class Position(val x: Int, val y: Int)

    fun preferencePercent(value: Int?): Int =
        if (value != null && value in MIN_PERCENT..MAX_PERCENT &&
            (value - MIN_PERCENT) % STEP_PERCENT == 0
        ) {
            value
        } else {
            DEFAULT_PERCENT
        }

    fun sliderPercent(value: Int): Int {
        val bounded = value.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return MIN_PERCENT + ((bounded - MIN_PERCENT + STEP_PERCENT / 2) / STEP_PERCENT) * STEP_PERCENT
    }

    fun dimensions(percent: Int): Dimensions {
        val scale = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        fun scaled(value: Int) = (value * scale).roundToInt()

        return Dimensions(
            buttonDp = scaled(BUTTON_DP),
            ringDp = maxOf(MIN_TOUCH_TARGET_DP, scaled(RING_DP)),
            paddingDp = scaled(PADDING_DP),
            tapThresholdDp = scaled(TAP_THRESHOLD_DP),
            feedbackOffsetDp = scaled(FEEDBACK_OFFSET_DP)
        )
    }

    fun valueLabel(
        percent: Int,
        standard: String = "standard",
        smaller: String = "smaller",
        larger: String = "larger",
    ): String = when (percent) {
        DEFAULT_PERCENT -> "$percent% · $standard"
        in MIN_PERCENT until DEFAULT_PERCENT -> "$percent% · $smaller"
        else -> "$percent% · $larger"
    }

    fun accessibilityDescription(
        percent: Int,
        standard: String = "standard size",
        smaller: String = "smaller than standard",
        larger: String = "larger than standard",
        percentUnit: String = "percent",
    ): String = when (percent) {
        DEFAULT_PERCENT -> "$percent $percentUnit, $standard"
        in MIN_PERCENT until DEFAULT_PERCENT -> "$percent $percentUnit, $smaller"
        else -> "$percent $percentUnit, $larger"
    }

    fun clampPosition(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        ringSize: Int,
        margin: Int
    ): Position {
        val maxX = maxOf(margin, screenWidth - ringSize - margin)
        val maxY = maxOf(margin, screenHeight - ringSize - margin)
        return Position(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
    }

    fun snappedX(x: Int, ringSize: Int, screenWidth: Int, margin: Int): Int {
        val maxX = maxOf(margin, screenWidth - ringSize - margin)
        return if (x + ringSize / 2 > screenWidth / 2) maxX else margin
    }
}
