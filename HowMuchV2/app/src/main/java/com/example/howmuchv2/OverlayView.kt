package com.example.howmuchv2 // Pastikan ini sesuai dengan package Anda

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import java.util.LinkedList
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<DetectionResult> = LinkedList()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf() // clear results, not just paint
        invalidate()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        Log.d("OverlayDebug", "View Dimens: [${width}, ${height}], Image Dimens: [${imageWidth}, ${imageHeight}]")
        for (result in results) {
            val boundingBox = result.boundingBox
            Log.d("OverlayDebug", "Incoming BBox (model coords): $boundingBox")
            val scaleX = width.toFloat() / imageWidth
            val scaleY = height.toFloat() / imageHeight

            val scale = max(scaleX, scaleY)

            val xOffset = (width - imageWidth * scale) / 2
            val yOffset = (height - imageHeight * scale) / 2
            Log.d("OverlayDebug", "Calculated -> Scale: $scale, xOffset: $xOffset, yOffset: $yOffset")

            val scaledLeft = boundingBox.left * scale + xOffset
            val scaledTop = boundingBox.top * scale + yOffset
            val scaledRight = boundingBox.right * scale + xOffset
            val scaledBottom = boundingBox.bottom * scale + yOffset

            val screenRect = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom)
            Log.d("OverlayDebug", "Drawing BBox at (screen coords): $screenRect")
            canvas.drawRect(screenRect, boxPaint)

            val drawableText = "${result.text} ${"%.2f".format(result.confidence)}"

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            canvas.drawRect(
                screenRect.left,
                screenRect.top,
                screenRect.left + textWidth + 8,
                screenRect.top + textHeight + 8,
                textBackgroundPaint
            )

            canvas.drawText(drawableText, screenRect.left + 4, screenRect.top + textHeight + 4, textPaint)
        }
    }

    fun setResults(detectionResults: List<DetectionResult>, imageHeight: Int, imageWidth: Int) {
        this.results = detectionResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        invalidate()
    }
}
