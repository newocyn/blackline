package online.pcguys.pockettally

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class ActivityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 42, 42)
        strokeWidth = resources.displayMetrics.density
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(145, 145, 145)
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 220)
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
    }

    private var labels = List(7) { "" }
    private var values = List(7) { 0 }

    fun setData(labels: List<String>, values: List<Int>, accent: Int) {
        this.labels = labels.takeLast(7)
        this.values = values.takeLast(7)
        barPaint.color = accent
        contentDescription = this.labels.zip(this.values).joinToString(", ") { "${it.first}: ${it.second} actions" }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (190f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 8f * density
        val right = width - 8f * density
        val top = 22f * density
        val baseline = height - 30f * density
        val chartHeight = baseline - top
        val maxValue = max(1, values.maxOrNull() ?: 1)
        canvas.drawLine(left, baseline, right, baseline, gridPaint)
        canvas.drawLine(left, top + chartHeight / 2f, right, top + chartHeight / 2f, gridPaint)

        val slot = (right - left) / max(1, values.size)
        values.forEachIndexed { index, value ->
            val center = left + slot * index + slot / 2f
            val barWidth = slot * 0.48f
            val barHeight = chartHeight * (value.toFloat() / maxValue.toFloat())
            val rect = RectF(center - barWidth / 2f, baseline - barHeight, center + barWidth / 2f, baseline)
            canvas.drawRoundRect(rect, barWidth * 0.22f, barWidth * 0.22f, barPaint)
            if (value > 0) canvas.drawText(value.toString(), center, baseline - barHeight - 7f * density, valuePaint)
            canvas.drawText(labels.getOrElse(index) { "" }, center, height - 9f * density, labelPaint)
        }
    }
}
