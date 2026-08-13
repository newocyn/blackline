package online.pcguys.pockettally

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(39, 39, 39)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 92, 92)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var accentColor: Int = Color.WHITE
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = min(width, height) * 0.055f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        val inset = stroke / 2f + resources.displayMetrics.density * 3f
        val diameter = min(width, height).toFloat()
        val left = (width - diameter) / 2f + inset
        val topEdge = (height - diameter) / 2f + inset
        val bounds = RectF(left, topEdge, width - left, height - topEdge)
        canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
        if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, progressPaint)

        val centerX = width / 2f
        val top = topEdge + stroke * 1.4f
        canvas.drawLine(centerX, topEdge - stroke * 0.15f, centerX, top, markerPaint)
    }
}
