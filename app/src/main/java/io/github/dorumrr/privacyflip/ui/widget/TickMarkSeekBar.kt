package io.github.dorumrr.privacyflip.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import io.github.dorumrr.privacyflip.R

/**
 * Custom SeekBar with tick marks at specific positions.
 * Shows visual indicators at: 0s, 60s (1m), 120s (2m), 300s (5m)
 * Snaps to discrete positions: 0-60 (continuous), 80 (2m), 100 (5m)
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

    // Track if user is currently dragging
    private var isTracking = false

    // Store external listener
    private var externalListener: OnSeekBarChangeListener? = null

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

        // Set up internal listener to handle snapping
        super.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Forward to external listener
                externalListener?.onProgressChanged(seekBar, progress, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isTracking = true
                externalListener?.onStartTrackingTouch(seekBar)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isTracking = false
                // Snap to nearest valid position
                val snappedPosition = snapToValidPosition(progress)
                if (snappedPosition != progress) {
                    setProgress(snappedPosition)
                }
                externalListener?.onStopTrackingTouch(seekBar)
            }
        })
    }

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        // Store the external listener instead of setting it directly
        externalListener = listener
    }

    /**
     * Snaps the position to the nearest valid tick mark.
     * Valid positions: 0-60 (continuous), 80 (2m), 100 (5m)
     */
    private fun snapToValidPosition(position: Int): Int {
        return when (position) {
            in 0..60 -> position  // Allow any value 0-60
            in 61..70 -> 60       // Snap to 60s (1m)
            in 71..90 -> 80       // Snap to 80 (2m)
            in 91..100 -> 100     // Snap to 100 (5m)
            else -> position
        }
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

