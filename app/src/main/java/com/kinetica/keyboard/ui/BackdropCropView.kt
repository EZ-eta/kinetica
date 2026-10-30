package com.kinetica.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * The background image with a frame of the keyboard's shape over it: one finger moves the frame,
 * two pinch it larger or smaller. The frame's arithmetic is [BackdropCrop]; this view only maps
 * the screen to image pixels and back.
 */
@SuppressLint("ViewConstructor")
class BackdropCropView(context: Context, private val image: Bitmap) : View(context) {

    /** The frame in image pixels; set with its shape by the screen, read back on Save. */
    var frame: FloatArray = BackdropCrop.largest(image.width, image.height, 1f)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 0, 0, 0)
        strokeWidth = 4f * density
    }

    // Image pixels to view pixels: the image fitted whole inside the view, centred.
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private val shown = RectF()
    private val box = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                frame = BackdropCrop.scaled(frame, detector.scaleFactor, image.width, image.height)
                return true
            }
        },
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 16f * density
        scale = minOf((w - 2f * margin) / image.width, (h - 2f * margin) / image.height).coerceAtLeast(1e-3f)
        offX = (w - image.width * scale) / 2f
        offY = (h - image.height * scale) / 2f
        shown.set(offX, offY, offX + image.width * scale, offY + image.height * scale)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(image, null, shown, imagePaint)
        box.set(
            offX + frame[0] * scale, offY + frame[1] * scale,
            offX + frame[2] * scale, offY + frame[3] * scale,
        )
        // The parts left out of the cut, shaded.
        canvas.drawRect(shown.left, shown.top, shown.right, box.top, shadePaint)
        canvas.drawRect(shown.left, box.bottom, shown.right, shown.bottom, shadePaint)
        canvas.drawRect(shown.left, box.top, box.left, box.bottom, shadePaint)
        canvas.drawRect(box.right, box.top, shown.right, box.bottom, shadePaint)
        // A dark line under the white one, so the frame shows on a white sky too.
        canvas.drawRect(box, edgePaint)
        canvas.drawRect(box, framePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            // A second finger is a pinch; the drag resumes from wherever the last one lifts.
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep)
                lastY = event.getY(keep)
                dragging = event.pointerCount == 2
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !pinch.isInProgress && event.pointerCount == 1) {
                frame = BackdropCrop.moved(
                    frame, (event.x - lastX) / scale, (event.y - lastY) / scale, image.width, image.height,
                )
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
