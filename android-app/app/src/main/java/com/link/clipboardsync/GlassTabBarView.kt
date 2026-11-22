package com.link.clipboardsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Floating capsule tab bar.
 *
 * API 31+: blurs the scroll content behind the bar for real — the source
 * view is drawn into a small bitmap (throttled to scroll/settle
 * invalidations only) and the layer is blurred on the GPU via
 * [RenderEffect]. Below API 31 an opaque tint is used instead.
 */
class GlassTabBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var blurSource: View? = null
    private var blurLayer: BlurLayerView? = null
    private var tintLayer: View? = null
    private var snapshotBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptureMs = 0L
    private var capturePending = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurLayer = BlurLayerView(context).also { addView(it, 0) }
        }
        tintLayer = View(context).apply {
            setBackgroundResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    R.drawable.bg_tab_bar_tint
                } else {
                    R.drawable.bg_tab_bar_tint_opaque
                }
            )
        }
        addView(tintLayer, if (blurLayer != null) 1 else 0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // The glass layers must cover the full capsule while the tab
        // content stays inset by the FrameLayout padding.
        val pad = paddingLeft
        if (pad > 0) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is BlurLayerView || child === tintLayer) {
                    val lp = child.layoutParams as? LayoutParams ?: continue
                    lp.setMargins(-pad, -pad, -pad, -pad)
                    child.layoutParams = lp
                }
            }
        }
    }

    /** View whose pixels should appear blurred behind the bar (the scroller). */
    fun setBlurSource(source: View) {
        blurSource = source
        source.viewTreeObserver.addOnScrollChangedListener { scheduleCapture() }
        viewTreeObserver.addOnGlobalLayoutListener { scheduleCapture() }
        post { scheduleCapture() }
    }

    private fun scheduleCapture() {
        if (blurLayer == null || !isShown || width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        val wait = CAPTURE_INTERVAL_MS - (now - lastCaptureMs)
        if (capturePending) return
        capturePending = true
        if (wait <= 0) {
            handler.post {
                capturePending = false
                captureBackdrop()
            }
        } else {
            handler.postDelayed({
                capturePending = false
                captureBackdrop()
            }, wait)
        }
    }

    private fun captureBackdrop() {
        val src = blurSource ?: return
        val layer = blurLayer ?: return
        if (width <= 0 || height <= 0 || !src.isShown) return
        lastCaptureMs = SystemClock.uptimeMillis()

        val barLoc = IntArray(2).also { getLocationOnScreen(it) }
        val srcLoc = IntArray(2).also { src.getLocationOnScreen(it) }
        val left = barLoc[0] - srcLoc[0]
        val top = barLoc[1] - srcLoc[1]

        var bmp = snapshotBitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            snapshotBitmap = bmp
        }
        val canvas = Canvas(bmp!!)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        canvas.translate(-left.toFloat(), -top.toFloat())
        src.draw(canvas)
        canvas.restore()
        layer.snapshot = bmp
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    private class BlurLayerView(context: Context) : View(context) {
        var snapshot: Bitmap? = null
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radius = 16f * resources.displayMetrics.density
                setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height, view.height / 2f
                        )
                    }
                }
                clipToOutline = true
            }
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            snapshot?.let {
                canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint)
            }
        }
    }

    companion object {
        private const val CAPTURE_INTERVAL_MS = 150L
    }
}
