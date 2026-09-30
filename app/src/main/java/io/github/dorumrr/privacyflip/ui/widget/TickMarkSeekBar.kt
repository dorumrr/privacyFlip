package io.github.dorumrr.privacyflip.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * SeekBar with tick marks at 0s, 1m, 2m and 5m. Positions and seconds convert only in
 * [io.github.dorumrr.privacyflip.data.TimerSettings].
 */
class TickMarkSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val tickPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    // Tick mark positions (SeekBar positions)
    // 0s at 0%, 1m at 60%, 2m at 80%, 5m at 100%
    private val tickPositions = listOf(0, 60, 80, 100)
    private val tickLabels = listOf("0s", "1m", "2m", "5m")

    init {
        // Get colors from theme
        val typedArray = context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorSecondary)
        )
        val textColor = typedArray.getColor(0, 0xFF666666.toInt())
        typedArray.recycle()

        tickPaint.color = textColor
        labelPaint.color = textColor

        // Add padding at the bottom for labels
        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + 60)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTickMarks(canvas)
    }

    private fun drawTickMarks(canvas: Canvas) {
        val width = width - paddingLeft - paddingRight
        val height = height - paddingTop - paddingBottom
        val thumbY = paddingTop + height / 2f

        tickPositions.forEachIndexed { index, position ->
            // Calculate X position based on SeekBar position
            val ratio = position.toFloat() / max.toFloat()
            val x = paddingLeft + (width * ratio)

            // Draw tick mark (small circle)
            val tickRadius = if (progress == position) 8f else 6f
            canvas.drawCircle(x, thumbY, tickRadius, tickPaint)

            // Draw label below the tick
            val label = tickLabels[index]
            val labelY = thumbY + 40f
            canvas.drawText(label, x, labelY, labelPaint)
        }
    }

    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        // Redraw to update tick mark sizes
        invalidate()
    }
}

