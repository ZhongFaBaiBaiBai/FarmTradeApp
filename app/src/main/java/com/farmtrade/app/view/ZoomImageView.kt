package com.farmtrade.app.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放 + 双击放大 + 单指拖动的 ImageView。
 *
 * 用于单据照片的全屏查看：很多过磅单字很小，必须能放大才看得清。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var baseScale = 1f
    private var maxScale = 5f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor)
                    .coerceIn(baseScale * 0.9f, maxScale)
                val factor = target / current
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                val target = if (current > baseScale * 1.3f) baseScale else (baseScale * 2.6f)
                val factor = target / current
                matrix.postScale(factor, factor, e.x, e.y)
                imageMatrix = matrix
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    /** 图片按比例完整居中显示 */
    private fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || iw <= 0f || ih <= 0f) return

        val scale = minOf(vw / iw, vh / ih)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f)
        imageMatrix = matrix
        baseScale = scale
        maxScale = scale * 5f
    }

    private fun currentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X].takeIf { it > 0f } ?: 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // 单指拖动（缩放过程中不做平移，避免和双指冲突）
        if (!scaleDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    imageMatrix = matrix
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        return true
    }
}
