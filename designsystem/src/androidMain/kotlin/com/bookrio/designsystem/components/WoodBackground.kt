package com.bookrio.designsystem.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient as AndroidLinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bookrio.designsystem.theme.LocalWoodPalette
import com.bookrio.designsystem.theme.WoodPalette
import kotlin.math.sin
import kotlin.random.Random

object WoodGrainGenerator {

    data class GrainParams(
        val seed: Int = 42,
        val grainLineCount: Int = 48,
        val knotCount: Int = 3,
        val noiseScale: Float = 5f,
        val amplitude: Float = 0.7f
    )

    fun generateBrush(
        palette: WoodPalette,
        params: GrainParams,
        widthPx: Int,
        heightPx: Int
    ): ShaderBrush {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val rand = Random(params.seed)

        val gradient = AndroidLinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                intArrayOf(
                    palette.baseLight.toArgb(),
                    palette.baseMid.toArgb(),
                    palette.baseDark.toArgb()
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            val bgPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
            }
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)

        repeat(params.grainLineCount) { i ->
            val baseY = (i.toFloat() / params.grainLineCount) * heightPx
            val lineAmplitude = (rand.nextFloat() * 0.8f + 0.25f) * params.amplitude * 16f
            val lineFreq = (rand.nextFloat() * 0.01f + 0.005f) * params.noiseScale
            val tintStrength = (rand.nextFloat() * 0.1f + 0.05f) // extremely subtle
            val isLight = rand.nextBoolean()
            val tint = if (isLight) palette.grainDark else palette.grainMid

            val path = AndroidPath()
            val step = 6
            var first = true
            var x = 0f
            while (x <= widthPx) {
                val phase = sin(x * lineFreq + i * 0.9f) * lineAmplitude
                val noise = (rand.nextFloat() - 0.5f) * 3.2f
                val y = baseY + phase + noise
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += step
            }
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                alpha = (tintStrength * 255).toInt().coerceIn(5, 80)
                style = AndroidPaint.Style.STROKE
                strokeWidth = rand.nextFloat() * 1.5f + 0.5f // thinner lines
                strokeCap = AndroidPaint.Cap.ROUND
                strokeJoin = AndroidPaint.Join.ROUND
                isAntiAlias = true
            }
            canvas.drawPath(path, paint)
        }

        repeat(params.knotCount) {
            val cx = (rand.nextFloat() * 0.7f + 0.15f) * widthPx
            val cy = (rand.nextFloat() * 0.7f + 0.15f) * heightPx
            val rx = (rand.nextFloat() * 24f + 18f).coerceAtMost(widthPx * 0.06f)
            val ry = rx * (rand.nextFloat() * 0.55f + 0.4f)
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 1.2f
                isAntiAlias = true
            }
            repeat(8) { j ->
                val pct = j / 8f
                val scale = 1f - pct * 0.6f
                val alpha = (230 - pct * 170).toInt().coerceIn(30, 230)
                paint.color = colorLerpArgb(palette.knotDark.toArgb(), palette.grainMid.toArgb(), pct)
                paint.alpha = alpha
                canvas.drawOval(
                    cx - rx * scale, cy - ry * scale,
                    cx + rx * scale, cy + ry * scale,
                    paint
                )
            }

            val fill = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = palette.knotCenter.toArgb()
                alpha = 90
                style = AndroidPaint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawOval(
                cx - rx * 0.25f, cy - ry * 0.25f,
                cx + rx * 0.25f, cy + ry * 0.25f, fill
            )
        }

        val vignette = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            shader = AndroidLinearGradient(
                0f, 0f, widthPx.toFloat(), 0f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    colorBlend(palette.knotDark.toArgb(), android.graphics.Color.WHITE, 0.15f, 0x70),
                    android.graphics.Color.TRANSPARENT,
                    colorBlend(palette.baseDark.toArgb(), android.graphics.Color.BLACK, 0.45f, 0x70)
                ),
                floatArrayOf(0f, 0.15f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), vignette)

        repeat(40) {
            val x1 = rand.nextInt(widthPx).toFloat()
            val y1 = rand.nextInt(heightPx).toFloat()
            val len = rand.nextFloat() * 26f + 6f
            val ang = rand.nextFloat() * 0.4f - 0.2f
            val x2 = x1 + kotlin.math.cos(ang) * len
            val y2 = y1 + kotlin.math.sin(ang) * len
            val sp = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = palette.grainDark.toArgb()
                alpha = rand.nextInt(40) + 6
                strokeWidth = 0.6f
                isAntiAlias = true
            }
            canvas.drawLine(x1, y1, x2, y2, sp)
        }

        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return ShaderBrush(shader)
    }

    private fun colorBlend(aArgb: Int, bArgb: Int, t: Float, alpha: Int = 0xFF): Int {
        val r = (android.graphics.Color.red(aArgb) * (1 - t) + android.graphics.Color.red(bArgb) * t).toInt().coerceIn(0, 255)
        val g = (android.graphics.Color.green(aArgb) * (1 - t) + android.graphics.Color.green(bArgb) * t).toInt().coerceIn(0, 255)
        val bl = (android.graphics.Color.blue(aArgb) * (1 - t) + android.graphics.Color.blue(bArgb) * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(alpha, r, g, bl)
    }

    private fun colorLerpArgb(aArgb: Int, bArgb: Int, t: Float): Int {
        val r = (android.graphics.Color.red(aArgb) * (1 - t) + android.graphics.Color.red(bArgb) * t).toInt().coerceIn(0, 255)
        val g = (android.graphics.Color.green(aArgb) * (1 - t) + android.graphics.Color.green(bArgb) * t).toInt().coerceIn(0, 255)
        val bl = (android.graphics.Color.blue(aArgb) * (1 - t) + android.graphics.Color.blue(bArgb) * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(r, g, bl)
    }

    private fun Color.toArgb(): Int {
        val r = (red * 255).toInt().coerceIn(0, 255)
        val g = (green * 255).toInt().coerceIn(0, 255)
        val b = (blue * 255).toInt().coerceIn(0, 255)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(a, r, g, b)
    }
}

@Composable
fun WoodBackground(
    modifier: Modifier = Modifier,
    seed: Int = 42,
    content: @Composable () -> Unit = {}
) {
    val density = LocalDensity.current
    val palette = LocalWoodPalette.current
    val brush = remember(seed, palette) {
        WoodGrainGenerator.generateBrush(
            palette = palette,
            params = WoodGrainGenerator.GrainParams(seed = seed),
            widthPx = with(density) { 420.dp.roundToPx() },
            heightPx = with(density) { 720.dp.roundToPx() }
        )
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush)
        }
        content()
    }
}
