package app.lawnchair.mono

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class MonoMode { AUTO, SILHOUETTE, DUOTONE }

object MonoIconGenerator {

    private const val S = 324 // 108dp * 3
    private const val LEGACY_INSET = 0.194f
    private const val ADAPTIVE_RIM = 0.17f

    fun hasMonochrome(icon: AdaptiveIconDrawable): Boolean =
        Build.VERSION.SDK_INT >= 33 && icon.monochrome != null

    /** Копия иконки с добавленным monochrome-слоем, или null если слой уже есть. */
    fun withMonochrome(
        res: Resources,
        icon: AdaptiveIconDrawable,
        mode: MonoMode = MonoMode.AUTO,
    ): AdaptiveIconDrawable? {
        if (Build.VERSION.SDK_INT < 33 || hasMonochrome(icon)) return null
        return try {
            AdaptiveIconDrawable(
                fresh(icon.background),
                fresh(icon.foreground),
                monochromeLayer(res, icon, mode),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Белый слой с альфа-каналом: система красит его в цвет темы. */
    fun monochromeLayer(
        res: Resources,
        icon: Drawable,
        mode: MonoMode = MonoMode.AUTO,
    ): BitmapDrawable {
        val adaptive = icon as? AdaptiveIconDrawable
        val inset = if (adaptive == null) (S * LEGACY_INSET).toInt() else 0
        val fg = if (adaptive != null) {
            render(listOf(adaptive.foreground), 0)
        } else {
            render(listOf(icon), inset)
        }
        val full = if (adaptive != null) {
            render(listOf(adaptive.background, adaptive.foreground), 0)
        } else {
            fg
        }
        val rim = if (adaptive != null) (S * ADAPTIVE_RIM).toInt() + 3 else inset + 3

        val chosen = when {
            mode != MonoMode.AUTO -> mode
            adaptive == null -> MonoMode.DUOTONE
            fg.count { Color.alpha(it) > 200 } > S * S * 0.9 || lumStd(fg) > 0.2f -> MonoMode.DUOTONE
            else -> MonoMode.SILHOUETTE
        }
        val duo = if (chosen == MonoMode.DUOTONE) duotoneMask(full, rim) else null
        val mask = duo ?: FloatArray(S * S) { Color.alpha(fg[it]) / 255f }
        return BitmapDrawable(res, toBitmap(mask, normalize = duo == null))
    }

    private fun duotoneMask(px: IntArray, rim: Int): FloatArray? {
        val n = S * S
        val a = FloatArray(n) { Color.alpha(px[it]) / 255f }
        val l = FloatArray(n) { lum(px[it]) }

        var sum = 0f
        var sumSq = 0f
        var cnt = 0
        fun sample(x: Int, y: Int) {
            val i = y * S + x
            if (a[i] > 0.8f) {
                sum += l[i]
                sumSq += l[i] * l[i]
                cnt++
            }
        }
        for (i in rim until S - rim) {
            sample(i, rim)
            sample(i, S - rim - 1)
            sample(rim, i)
            sample(S - rim - 1, i)
        }
        val avg = if (cnt > 0) sum / cnt else 0.5f
        val std = if (cnt > 0) sqrt(max(0f, sumSq / cnt - avg * avg)) else 1f
        val flat = cnt > 0 && std < 0.06f
        val invert = flat && avg > 0.55f

        fun v(i: Int) = if (invert) 1f - l[i] else l[i]

        val hist = IntArray(256)
        var total = 0
        for (i in 0 until n) {
            if (a[i] > 0.15f) {
                hist[(v(i) * 255f).toInt().coerceIn(0, 255)]++
                total++
            }
        }
        if (total == 0) return null
        fun pct(p: Float): Float {
            var acc = 0
            val target = total * p
            for (b in 0..255) {
                acc += hist[b]
                if (acc >= target) return b / 255f
            }
            return 1f
        }
        val lo = if (flat) (if (invert) 1f - avg else avg) + 0.03f else pct(0.05f)
        val hi = if (flat) pct(0.995f) else pct(0.95f)
        if (hi - lo < 0.10f) return null

        return FloatArray(n) { i ->
            if (a[i] < 0.02f) {
                0f
            } else {
                a[i] * ((v(i) - lo) / (hi - lo)).coerceIn(0f, 1f).pow(0.9f)
            }
        }
    }

    private fun toBitmap(mask: FloatArray, normalize: Boolean): Bitmap {
        val raw = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val out = IntArray(S * S)
        var minX = S
        var minY = S
        var maxX = -1
        var maxY = -1
        for (y in 0 until S) {
            for (x in 0 until S) {
                val m = mask[y * S + x]
                out[y * S + x] = ((m * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
                if (m > 0.1f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        raw.setPixels(out, 0, S, 0, 0, S, S)
        if (!normalize || maxX < 0) return raw

        val w = (maxX - minX + 1).toFloat()
        val h = (maxY - minY + 1).toFloat()
        val k = (S * 0.5f / max(w, h)).coerceIn(0.6f, 1.6f)
        val m = Matrix().apply {
            postTranslate(-(minX + maxX) / 2f, -(minY + maxY) / 2f)
            postScale(k, k)
            postTranslate(S / 2f, S / 2f)
        }
        val result = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(raw, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    private fun render(layers: List<Drawable?>, inset: Int): IntArray {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (layer in layers) {
            val d = fresh(layer) ?: continue
            d.setBounds(inset, inset, S - inset, S - inset)
            d.draw(canvas)
        }
        val px = IntArray(S * S)
        bmp.getPixels(px, 0, S, 0, 0, S, S)
        bmp.recycle()
        return px
    }

    private fun fresh(d: Drawable?): Drawable? =
        d?.constantState?.newDrawable()?.mutate() ?: d

    private fun lum(p: Int) =
        (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f

    private fun lumStd(px: IntArray): Float {
        var sum = 0f
        var sumSq = 0f
        var n = 0
        for (p in px) {
            if (Color.alpha(p) > 200) {
                val l = lum(p)
                sum += l
                sumSq += l * l
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return sqrt(max(0f, sumSq / n - mean * mean))
    }
}
