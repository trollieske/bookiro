package com.bookrio.reader.pageturn

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.*

/**
 * High-precision vertical strips for smooth 60 FPS 3D mesh deformation.
 */
private const val STRIP_COUNT = 42

/**
 * Ultra-realistic 3D Page Curl Rendering Engine (Apple iBooks / Physical Book Feel).
 *
 * Simulates real 3D paper mechanics:
 * - Dynamic 3D Cylinder Arc & Backside Leaf Deformation.
 * - Corner Peeling Angle (diagonal fold line matching physical thumb drag).
 * - Real 3D Perspective Depth & Vertical Page Lift.
 * - Dynamic Ambient Occlusion & Soft Drop Shadows cast under the lifted leaf.
 * - Surface Specular Highlights & Paper Grain Crease Shading.
 * - Book Spine Depth Shadowing.
 */
fun DrawScope.drawPageCurlEffect(
    currentBitmap: Bitmap,
    nextBitmap: Bitmap?,
    backsideBitmap: Bitmap?,
    leftBitmap: Bitmap? = null,
    foldFraction: Float,
    direction: TurnDirection,
    doublePage: Boolean,
    paperColorInt: Int = 0xFFFBF7EE.toInt(),
) {
    drawIntoCanvas { composeCanvas ->
        val nativeCanvas = composeCanvas.nativeCanvas

        val canvasW = size.width
        val canvasH = size.height

        val zoneLeft  = if (doublePage) canvasW / 2f else 0f
        val zoneWidth = if (doublePage) canvasW / 2f else canvasW

        val f = foldFraction.coerceIn(0.001f, 0.999f)

        // ── 1. Book Spine Shadow (Base Depth) ──────────────────────────────────
        val spineW = if (doublePage) 36f else 28f
        val spineX = if (doublePage) canvasW / 2f else 0f

        // ── 2. Revealed Background Page ───────────────────────────────────────
        val bgPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
        if (nextBitmap != null) {
            nativeCanvas.drawBitmap(
                nextBitmap,
                android.graphics.Rect(0, 0, nextBitmap.width, nextBitmap.height),
                RectF(zoneLeft, 0f, zoneLeft + zoneWidth, canvasH),
                bgPaint,
            )
        } else {
            val paperPaint = AndroidPaint().apply { color = paperColorInt }
            nativeCanvas.drawRect(zoneLeft, 0f, zoneLeft + zoneWidth, canvasH, paperPaint)
        }

        // Draw spine shadow over revealed background
        val spineGrad = if (doublePage) {
            LinearGradient(
                spineX - spineW / 2f, 0f, spineX + spineW / 2f, 0f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(70, 0, 0, 0),
                    android.graphics.Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f, 0f, spineW, 0f,
                intArrayOf(
                    android.graphics.Color.argb(85, 0, 0, 0),
                    android.graphics.Color.TRANSPARENT,
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        val spinePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { shader = spineGrad }
        if (doublePage) {
            nativeCanvas.drawRect(spineX - spineW / 2f, 0f, spineX + spineW / 2f, canvasH, spinePaint)
        } else {
            nativeCanvas.drawRect(0f, 0f, spineW, canvasH, spinePaint)
        }

        // ── 3. Geometry Setup: Fold Line & Corner Angle ───────────────────────
        // Total paper length peeled from rest position
        val peeledLen = zoneWidth * f

        // Base fold crease X on flat surface
        val foldBaseX = if (direction == TurnDirection.FORWARD) {
            zoneLeft + zoneWidth * (1f - f)
        } else {
            zoneLeft + zoneWidth * f
        }

        // Organic corner diagonal angle: bottom-right corner peels faster than top-right
        val cornerOffset = (1f - f) * 0.08f * zoneWidth
        val foldTopX = if (direction == TurnDirection.FORWARD) foldBaseX + cornerOffset else foldBaseX - cornerOffset
        val foldBotX = if (direction == TurnDirection.FORWARD) foldBaseX - cornerOffset else foldBaseX + cornerOffset

        // Dynamic Cylinder Radius R(f): starts small at corner, expands in mid-flight, flattens at end
        val maxR = zoneWidth * 0.22f
        val cylinderR = (zoneWidth * 0.04f + maxR * sin(PI * f).toFloat()).coerceAtLeast(12f)

        // Arc wrap capacity before paper flattens on backside
        val arcLen = PI.toFloat() * cylinderR

        // ── 4. Cast Drop Shadow (Ambient Occlusion under lifted leaf) ─────────
        val shadowWidth = (cylinderR * 1.8f + 30f).coerceAtMost(zoneWidth * 0.45f)
        val shadowAlpha = (sin(PI * f).toFloat() * 110).toInt().coerceIn(0, 130)

        if (shadowAlpha > 5 && shadowWidth > 5f) {
            val (sFrom, sTo, colors) = if (direction == TurnDirection.FORWARD) {
                Triple(
                    foldBaseX - shadowWidth * 0.4f,
                    foldBaseX + shadowWidth * 0.8f,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.argb(shadowAlpha, 0, 0, 0),
                        android.graphics.Color.argb((shadowAlpha * 0.4f).toInt(), 0, 0, 0),
                        android.graphics.Color.TRANSPARENT,
                    ),
                )
            } else {
                Triple(
                    foldBaseX - shadowWidth * 0.8f,
                    foldBaseX + shadowWidth * 0.4f,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.argb((shadowAlpha * 0.4f).toInt(), 0, 0, 0),
                        android.graphics.Color.argb(shadowAlpha, 0, 0, 0),
                        android.graphics.Color.TRANSPARENT,
                    ),
                )
            }
            val dropGrad = LinearGradient(
                sFrom, 0f, sTo, 0f,
                colors,
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
            nativeCanvas.drawRect(
                minOf(sFrom, sTo), 0f, maxOf(sFrom, sTo), canvasH,
                AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { shader = dropGrad },
            )
        }

        // ── 5. Static (Flat Un-peeled) Portion of Current Page ────────────────
        val staticLeft  = if (direction == TurnDirection.FORWARD) zoneLeft else foldBaseX
        val staticRight = if (direction == TurnDirection.FORWARD) foldBaseX else zoneLeft + zoneWidth

        if (staticRight > staticLeft + 1f) {
            nativeCanvas.save()
            nativeCanvas.clipRect(staticLeft, 0f, staticRight, canvasH)

            val srcLeft  = ((staticLeft - zoneLeft) / zoneWidth * currentBitmap.width).toInt()
            val srcRight = ((staticRight - zoneLeft) / zoneWidth * currentBitmap.width).toInt()
            nativeCanvas.drawBitmap(
                currentBitmap,
                android.graphics.Rect(srcLeft.coerceAtLeast(0), 0, srcRight.coerceAtMost(currentBitmap.width), currentBitmap.height),
                RectF(staticLeft, 0f, staticRight, canvasH),
                AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG),
            )
            nativeCanvas.restore()
        }

        // ── 6. 3D Deformed Leaf (Cylinder Arc & Backside) Mesh Strips ──────────
        val bmpW = currentBitmap.width
        val bmpH = currentBitmap.height
        val bsideBmp = backsideBitmap ?: currentBitmap

        // Source slice bounds on current bitmap corresponding to peeled section
        val bmpPeeledStart = if (direction == TurnDirection.FORWARD) {
            ((foldBaseX - zoneLeft) / zoneWidth * bmpW).toInt().coerceIn(0, bmpW)
        } else 0

        val bmpPeeledEnd = if (direction == TurnDirection.FORWARD) {
            bmpW
        } else {
            ((foldBaseX - zoneLeft) / zoneWidth * bmpW).toInt().coerceIn(0, bmpW)
        }

        val bmpPeeledW = (bmpPeeledEnd - bmpPeeledStart).coerceAtLeast(1)

        val stripCount = GpuDeviceProfile.currentQualityTier.stripCount

        for (i in 0 until stripCount) {
            val t0   = i.toFloat() / stripCount
            val t1   = (i + 1).toFloat() / stripCount
            val tMid = (t0 + t1) / 2f

            // Distance along peeled paper leaf
            val s0   = t0 * peeledLen
            val s1   = t1 * peeledLen
            val sMid = tMid * peeledLen

            // 3D Angle θ along cylinder arc (0 = fold crease, π = fully flipped backward)
            val thetaMid = if (sMid <= arcLen) (sMid / cylinderR) else PI.toFloat()
            val cosMid   = cos(thetaMid).toFloat()
            val sinMid   = sin(thetaMid).toFloat()
            val isFront  = cosMid >= 0f

            // Compute 3D Perspective Lift Z and Y-perspective contraction
            val zLift = cylinderR * (1f - cosMid)
            val perspectiveScale = 1f - (zLift / (canvasH * 3.5f)).coerceIn(0f, 0.12f)

            val yTop = (1f - perspectiveScale) * canvasH * 0.4f
            val yBot = canvasH - yTop

            // Compute 2D Projected Canvas X coordinates for Top and Bottom of strip
            fun calcProjX(s: Float, foldX: Float): Float {
                return if (s <= arcLen) {
                    val theta = s / cylinderR
                    if (direction == TurnDirection.FORWARD) {
                        foldX - cylinderR * sin(theta).toFloat()
                    } else {
                        foldX + cylinderR * sin(theta).toFloat()
                    }
                } else {
                    val extra = s - arcLen
                    val arcEndX = if (direction == TurnDirection.FORWARD) {
                        foldX - cylinderR * sin(PI.toFloat())
                    } else {
                        foldX + cylinderR * sin(PI.toFloat())
                    }
                    if (direction == TurnDirection.FORWARD) {
                        arcEndX - extra
                    } else {
                        arcEndX + extra
                    }
                }
            }

            val pX0_top = calcProjX(s0, foldTopX)
            val pX1_top = calcProjX(s1, foldTopX)

            val pX0_bot = calcProjX(s0, foldBotX)
            val pX1_bot = calcProjX(s1, foldBotX)

            // Source bitmap pixel coordinates
            val srcBitmap: Bitmap
            val srcX0: Int
            val srcX1: Int

            if (isFront) {
                srcBitmap = currentBitmap
                srcX0 = (bmpPeeledStart + t0 * bmpPeeledW).toInt().coerceIn(0, bmpW)
                srcX1 = (bmpPeeledStart + t1 * bmpPeeledW).toInt().coerceIn(0, bmpW)
            } else {
                srcBitmap = bsideBmp
                val bsW = bsideBmp.width
                srcX0 = (bsW * (1f - t0)).toInt().coerceIn(0, bsW)
                srcX1 = (bsW * (1f - t1)).toInt().coerceIn(0, bsW)
            }

            if (abs(srcX1 - srcX0) < 1) continue

            // 4-point Poly-to-Poly Quad Mapping for exact 3D Perspective trapezoids
            val matrix = Matrix()
            val srcPts = floatArrayOf(
                srcX0.toFloat(), 0f,
                srcX1.toFloat(), 0f,
                srcX0.toFloat(), srcBitmap.height.toFloat(),
                srcX1.toFloat(), srcBitmap.height.toFloat(),
            )
            val dstPts = floatArrayOf(
                pX0_top, yTop,
                pX1_top, yTop,
                pX0_bot, yBot,
                pX1_bot, yBot,
            )

            if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) continue

            // ── Surface Lighting & Shading Model ───────────────────────────────
            nativeCanvas.save()

            // Exact quad trapezoid clip path to isolate strip rendering and eliminate Adreno GPU seam tearing
            val quadClipPath = Path().apply {
                moveTo(pX0_top, yTop)
                lineTo(pX1_top + 0.5f, yTop)
                lineTo(pX1_bot + 0.5f, yBot)
                lineTo(pX0_bot, yBot)
                close()
            }
            nativeCanvas.clipPath(quadClipPath)

            nativeCanvas.concat(matrix)

            if (!isFront) {
                // Fill strip with 100% solid paper base color so peeling page is never glass/transparent
                val paperFillPaint = AndroidPaint().apply {
                    color = paperColorInt
                    style = AndroidPaint.Style.FILL
                }
                nativeCanvas.drawRect(
                    0f, 0f, srcBitmap.width.toFloat(), srcBitmap.height.toFloat(),
                    paperFillPaint
                )
            }

            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
            nativeCanvas.drawBitmap(srcBitmap, 0f, 0f, paint)

            if (isFront) {
                // Front surface specular highlight & curve shadow
                val spec = sin(2f * thetaMid.coerceIn(0f, PI.toFloat() / 2f)).coerceIn(0f, 1f) * 0.28f
                val shadow = (1f - sinMid).coerceIn(0f, 1f) * 0.18f

                if (spec > 0.02f) {
                    val specColor = android.graphics.Color.argb((spec * 255).toInt(), 255, 255, 255)
                    nativeCanvas.drawRect(
                        0f, 0f, srcBitmap.width.toFloat(), srcBitmap.height.toFloat(),
                        AndroidPaint().apply { color = specColor },
                    )
                }
                if (shadow > 0.02f) {
                    val shadowColor = android.graphics.Color.argb((shadow * 255).toInt(), 0, 0, 0)
                    nativeCanvas.drawRect(
                        0f, 0f, srcBitmap.width.toFloat(), srcBitmap.height.toFloat(),
                        AndroidPaint().apply { color = shadowColor },
                    )
                }
            } else {
                // Backside leaf: Warm paper curvature shading (soft warm shade for paper texture depth)
                val bsideShadow = 0.05f + (1f - sinMid).coerceIn(0f, 1f) * 0.15f
                val paperOverlay = android.graphics.Color.argb((bsideShadow * 255).toInt(), 30, 22, 10)

                nativeCanvas.drawRect(
                    0f, 0f, srcBitmap.width.toFloat(), srcBitmap.height.toFloat(),
                    AndroidPaint().apply { color = paperOverlay },
                )
            }

            nativeCanvas.restore()
        }

        // ── 7. Crease Specular Line & Edge Highlight ───────────────────────────
        val specW = 12f
        val specGrad = LinearGradient(
            foldBaseX - specW, 0f, foldBaseX + specW, 0f,
            intArrayOf(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb(90, 255, 255, 255),
                android.graphics.Color.argb(40, 0, 0, 0),
                android.graphics.Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        nativeCanvas.drawRect(
            foldBaseX - specW, 0f, foldBaseX + specW, canvasH,
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { shader = specGrad },
        )
    }
}
