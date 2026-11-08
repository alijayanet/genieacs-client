package com.genieacs.client.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.genieacs.client.R
import kotlin.math.min

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(
        val label: String,
        val count: Int,
        val color: Int
    )

    private var slices: List<Slice> = emptyList()
    private var centerPrimaryText: String = "0"
    private var centerSecondaryText: String = ""

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val emptyArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#334155")
        strokeCap = Paint.Cap.ROUND
    }

    private var customColorsSet = false

    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private var strokeThickness = 24f

    init {
        // Density-independent pixels
        val density = context.resources.displayMetrics.density
        strokeThickness = 14f * density
        arcPaint.strokeWidth = strokeThickness
        emptyArcPaint.strokeWidth = strokeThickness
        primaryTextPaint.textSize = 20f * density
        secondaryTextPaint.textSize = 10f * density

        applyThemeColors()
    }

    private fun applyThemeColors() {
        if (!customColorsSet) {
            primaryTextPaint.color = ContextCompat.getColor(context, R.color.genie_text_primary)
            secondaryTextPaint.color = ContextCompat.getColor(context, R.color.genie_text_secondary)
            emptyArcPaint.color = ContextCompat.getColor(context, R.color.genie_divider)
        }
    }

    fun setData(
        newSlices: List<Slice>,
        primaryText: String = "",
        secondaryText: String = ""
    ) {
        this.slices = newSlices
        val total = newSlices.sumOf { it.count }
        this.centerPrimaryText = if (primaryText.isNotEmpty()) primaryText else total.toString()
        this.centerSecondaryText = secondaryText
        invalidate()
    }

    fun setTextColor(primaryColor: Int, secondaryColor: Int) {
        customColorsSet = true
        primaryTextPaint.color = primaryColor
        secondaryTextPaint.color = secondaryColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        applyThemeColors()

        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val halfStroke = strokeThickness / 2f
        val margin = 8f

        val left = (w - size) / 2f + halfStroke + margin
        val top = (h - size) / 2f + halfStroke + margin
        val right = (w + size) / 2f - halfStroke - margin
        val bottom = (h + size) / 2f - halfStroke - margin

        rect.set(left, top, right, bottom)

        val total = slices.sumOf { it.count }

        if (total <= 0) {
            // Draw empty ring
            canvas.drawArc(rect, 0f, 360f, false, emptyArcPaint)
        } else {
            var startAngle = -90f
            val validSlices = slices.filter { it.count > 0 }

            if (validSlices.size == 1) {
                // Single slice fills full ring
                arcPaint.color = validSlices[0].color
                canvas.drawArc(rect, 0f, 360f, false, arcPaint)
            } else {
                val gapDegrees = 3f // small aesthetic gap between slices
                val totalGaps = gapDegrees * validSlices.size
                val availableSweep = 360f - totalGaps

                for (slice in validSlices) {
                    val sweep = (slice.count.toFloat() / total.toFloat()) * availableSweep
                    arcPaint.color = slice.color
                    canvas.drawArc(rect, startAngle, sweep, false, arcPaint)
                    startAngle += sweep + gapDegrees
                }
            }
        }

        // Draw center text
        val centerX = w / 2f
        val centerY = h / 2f

        if (centerSecondaryText.isNotEmpty()) {
            val primaryY = centerY - (primaryTextPaint.descent() + primaryTextPaint.ascent()) / 2f - 7f
            val secondaryY = centerY - (secondaryTextPaint.descent() + secondaryTextPaint.ascent()) / 2f + 14f
            canvas.drawText(centerPrimaryText, centerX, primaryY, primaryTextPaint)
            canvas.drawText(centerSecondaryText, centerX, secondaryY, secondaryTextPaint)
        } else {
            val primaryY = centerY - (primaryTextPaint.descent() + primaryTextPaint.ascent()) / 2f
            canvas.drawText(centerPrimaryText, centerX, primaryY, primaryTextPaint)
        }
    }
}
