package com.kanzz.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Bingkai seleksi overlay: drag, resize, rotasi, pinch dua jari, dan magnet tengah. */
class OverlayVideoSelectionView(
    context: Context,
    private val clipProvider: () -> OverlayVideoClip?,
    private val previewVisible: (OverlayVideoClip) -> Boolean,
    private val onGestureStart: () -> Unit,
    private val onTransformChanged: (OverlayVideoClip) -> Unit,
    private val onGestureEnd: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val green = Color.rgb(24, 213, 138)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; alpha = 190; strokeWidth = 1f * density }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeWidth = 2f * density }
    private var mode = 0
    private var downX = 0f; private var downY = 0f
    private var startX = 0f; private var startY = 0f
    private var startScale = 1f; private var startRotation = 0f
    private var startDistance = 1f; private var startAngle = 0f
    private var startMidX = 0f; private var startMidY = 0f
    private var snapX = false; private var snapY = false
    private var gestureClipId: String? = null
    private var cropMode = false
    private var cropHandle = -1
    private var startCropLeft = 0f
    private var startCropTop = 0f
    private var startCropRight = 0f
    private var startCropBottom = 0f

    fun setCropMode(enabled: Boolean) {
        cropMode = enabled
        mode = 0
        gestureClipId = null
        invalidate()
    }

    private data class G(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val scale: Float, val rotation: Float)
    private fun geometry(c: OverlayVideoClip): G {
        val vw = c.videoWidth.takeIf { it > 0 }?.toFloat() ?: 16f
        val vh = c.videoHeight.takeIf { it > 0 }?.toFloat() ?: 9f
        val aspect = (vw / vh).coerceIn(0.1f, 10f)
        // Samakan batas bingkai dengan area video FIT yang benar-benar tampil di PlayerView.
        val maxW = width.toFloat().coerceAtLeast(1f)
        val maxH = height.toFloat().coerceAtLeast(1f)
        val w: Float; val h: Float
        if (aspect >= maxW / maxH) { w = maxW; h = w / aspect } else { h = maxH; w = h * aspect }
        return G(width / 2f + c.transformX, height / 2f + c.transformY, w / 2f, h / 2f, c.transformScale.coerceIn(0.2f, 5f), c.rotationDegrees)
    }
    private fun point(g: G, x: Float, y: Float): Pair<Float, Float> {
        val a = Math.toRadians(g.rotation.toDouble()); val sx = x * g.scale; val sy = y * g.scale
        return (g.cx + (sx * cos(a) - sy * sin(a)).toFloat()) to (g.cy + (sx * sin(a) + sy * cos(a)).toFloat())
    }
    private fun local(g: G, x: Float, y: Float): Pair<Float, Float> {
        val dx = x - g.cx; val dy = y - g.cy; val a = Math.toRadians((-g.rotation).toDouble())
        return ((dx * cos(a) - dy * sin(a)) / g.scale).toFloat() to ((dx * sin(a) + dy * cos(a)) / g.scale).toFloat()
    }
    private fun angle(x: Float, y: Float, cx: Float, cy: Float) = Math.toDegrees(atan2((y-cy).toDouble(), (x-cx).toDouble())).toFloat()
    private fun snapAngle(v: Float): Float { val n = (v / 90f).roundToInt() * 90f; return if (abs(v-n) <= 2.5f) n else v }
    private fun magnet(c: OverlayVideoClip, x: Float, y: Float) {
        val t = 8f * density; snapX = abs(x) <= t; snapY = abs(y) <= t
        c.transformX = if (snapX) 0f else x; c.transformY = if (snapY) 0f else y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = clipProvider() ?: return
        if (!previewVisible(c)) return
        val g = geometry(c)
        canvas.save(); canvas.translate(g.cx, g.cy); canvas.rotate(g.rotation); canvas.scale(g.scale, g.scale)
        if (cropMode) {
            val l = -g.hw + 2f * g.hw * c.cropLeft
            val r = g.hw - 2f * g.hw * c.cropRight
            val t = -g.hh + 2f * g.hh * c.cropTop
            val b = g.hh - 2f * g.hh * c.cropBottom
            val cropRect = RectF(l, t, r, b)
            canvas.drawRect(cropRect, border)
            val points = arrayOf(
                l to t, r to t, l to b, r to b,
                ((l+r)/2f) to t, ((l+r)/2f) to b, l to ((t+b)/2f), r to ((t+b)/2f)
            )
            points.forEach { (x,y) -> canvas.drawCircle(x,y,6f*density,handle); canvas.drawCircle(x,y,6f*density,handleBorder) }
        } else {
            canvas.drawRect(RectF(-g.hw, -g.hh, g.hw, g.hh), border)
            val corners = arrayOf(-g.hw to -g.hh, g.hw to -g.hh, -g.hw to g.hh, g.hw to g.hh)
            corners.forEach { (x,y) -> canvas.drawCircle(x,y,5f*density,handle); canvas.drawCircle(x,y,5f*density,handleBorder) }
            canvas.drawLine(0f,-g.hh,0f,-g.hh-27f*density,stem)
            canvas.drawCircle(0f,-g.hh-27f*density,6f*density,handle); canvas.drawCircle(0f,-g.hh-27f*density,6f*density,handleBorder)
        }
        canvas.restore()
        if (snapX) canvas.drawLine(width/2f,0f,width/2f,height.toFloat(),guide)
        if (snapY) canvas.drawLine(0f,height/2f,width.toFloat(),height/2f,guide)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = clipProvider() ?: return false
        if (!previewVisible(c)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val g=geometry(c); val p=local(g,e.x,e.y)
                if (cropMode) {
                    val l=-g.hw+2f*g.hw*c.cropLeft; val r=g.hw-2f*g.hw*c.cropRight
                    val t=-g.hh+2f*g.hh*c.cropTop; val b=g.hh-2f*g.hh*c.cropBottom
                    val handles=arrayOf(l to t,r to t,l to b,r to b,((l+r)/2f) to t,((l+r)/2f) to b,l to ((t+b)/2f),r to ((t+b)/2f))
                    val nearest=handles.indices.minByOrNull { i -> val q=point(g,handles[i].first,handles[i].second); (e.x-q.first)*(e.x-q.first)+(e.y-q.second)*(e.y-q.second) } ?: -1
                    val q=if(nearest>=0) point(g,handles[nearest].first,handles[nearest].second) else 0f to 0f
                    if(nearest<0 || hypot(e.x-q.first,e.y-q.second)>30f*density)return false
                    cropHandle=nearest; mode=5
                    startCropLeft=c.cropLeft; startCropTop=c.cropTop; startCropRight=c.cropRight; startCropBottom=c.cropBottom
                } else {
                    val rot=point(g,0f,-g.hh-27f*density)
                    val rotHit=hypot(e.x-rot.first,e.y-rot.second)<=25f*density
                    val corners=arrayOf(-g.hw to -g.hh,g.hw to -g.hh,-g.hw to g.hh,g.hw to g.hh)
                    val nearest=corners.minByOrNull { (x,y)-> val q=point(g,x,y); (e.x-q.first)*(e.x-q.first)+(e.y-q.second)*(e.y-q.second) }
                    val q=nearest?.let { point(g,it.first,it.second) }
                    val resizeHit=q!=null && hypot(e.x-q.first,e.y-q.second)<=26f*density
                    mode=when { rotHit->3; resizeHit->2; abs(p.first)<=g.hw && abs(p.second)<=g.hh->1; else->0 }
                    if(mode==0)return false
                }
                gestureClipId=c.id; downX=e.x; downY=e.y; startX=c.transformX; startY=c.transformY
                startScale=c.transformScale; startRotation=c.rotationDegrees
                startDistance=hypot(e.x-g.cx,e.y-g.cy).coerceAtLeast(1f); startAngle=angle(e.x,e.y,g.cx,g.cy)
                snapX=false; snapY=false; onGestureStart(); parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if(gestureClipId!=c.id || e.pointerCount<2)return mode!=0
                val x1=e.getX(0); val y1=e.getY(0); val x2=e.getX(1); val y2=e.getY(1)
                startMidX=(x1+x2)/2f; startMidY=(y1+y2)/2f
                startDistance=hypot(x2-x1,y2-y1).coerceAtLeast(1f); startAngle=angle(x2,y2,x1,y1)
                startX=c.transformX; startY=c.transformY; startScale=c.transformScale; startRotation=c.rotationDegrees; mode=4; return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(gestureClipId!=c.id || mode==0)return false
                when(mode) {
                    5 -> {
                        val g=geometry(c); val p=local(g,e.x,e.y)
                        val left=((p.first+g.hw)/(2f*g.hw)).coerceIn(0f,0.97f)
                        val right=((g.hw-p.first)/(2f*g.hw)).coerceIn(0f,0.97f)
                        val top=((p.second+g.hh)/(2f*g.hh)).coerceIn(0f,0.97f)
                        val bottom=((g.hh-p.second)/(2f*g.hh)).coerceIn(0f,0.97f)
                        when(cropHandle) {
                            0 -> { c.cropLeft=left.coerceAtMost(1f-c.cropRight-0.03f); c.cropTop=top.coerceAtMost(1f-c.cropBottom-0.03f) }
                            1 -> { c.cropRight=right.coerceAtMost(1f-c.cropLeft-0.03f); c.cropTop=top.coerceAtMost(1f-c.cropBottom-0.03f) }
                            2 -> { c.cropLeft=left.coerceAtMost(1f-c.cropRight-0.03f); c.cropBottom=bottom.coerceAtMost(1f-c.cropTop-0.03f) }
                            3 -> { c.cropRight=right.coerceAtMost(1f-c.cropLeft-0.03f); c.cropBottom=bottom.coerceAtMost(1f-c.cropTop-0.03f) }
                            4 -> c.cropTop=top.coerceAtMost(1f-c.cropBottom-0.03f)
                            5 -> c.cropBottom=bottom.coerceAtMost(1f-c.cropTop-0.03f)
                            6 -> c.cropLeft=left.coerceAtMost(1f-c.cropRight-0.03f)
                            7 -> c.cropRight=right.coerceAtMost(1f-c.cropLeft-0.03f)
                        }
                    }
                    1 -> magnet(c,startX+e.x-downX,startY+e.y-downY)
                    2 -> { val g=geometry(c); val d=hypot(e.x-g.cx,e.y-g.cy).coerceAtLeast(1f); c.transformScale=(startScale*d/startDistance).coerceIn(0.2f,5f) }
                    3 -> { val g=geometry(c); c.rotationDegrees=snapAngle(startRotation+angle(e.x,e.y,g.cx,g.cy)-startAngle) }
                    4 -> if(e.pointerCount>=2) {
                        val x1=e.getX(0); val y1=e.getY(0); val x2=e.getX(1); val y2=e.getY(1)
                        val d=hypot(x2-x1,y2-y1).coerceAtLeast(1f); val mx=(x1+x2)/2f; val my=(y1+y2)/2f
                        c.transformScale=(startScale*d/startDistance).coerceIn(0.2f,5f)
                        c.rotationDegrees=snapAngle(startRotation+angle(x2,y2,x1,y1)-startAngle)
                        magnet(c,startX+mx-startMidX,startY+my-startMidY)
                    }
                }
                onTransformChanged(c); invalidate(); return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if(mode==4) { mode=1; downX=e.getX(0); downY=e.getY(0); startX=c.transformX; startY=c.transformY }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if(gestureClipId!=c.id || mode==0)return false
                mode=0; gestureClipId=null; snapX=false; snapY=false
                parent?.requestDisallowInterceptTouchEvent(false); onTransformChanged(c); onGestureEnd(); invalidate(); return true
            }
        }
        return mode!=0
    }
}
