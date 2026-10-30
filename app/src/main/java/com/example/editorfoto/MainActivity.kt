
package com.example.editorfoto

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as ACanvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.ColorMatrix as AColorMatrix

// =====================================================================
//  MODELO
// =====================================================================
class Cube(val n: Int, val data: FloatArray)

data class Adj(
    // matriz de color (tiempo real)
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val exposure: Float = 0f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val rGain: Float = 1f,
    val gGain: Float = 1f,
    val bGain: Float = 1f,
    val filter: Int = 0,
    // efectos por píxel
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val black: Float = 0f,
    val white: Float = 1f,
    val gamma: Float = 1f,
    val curveS: Float = 0f,
    val curveM: Float = 0f,
    val curveH: Float = 0f,
    val sharp: Float = 0f,
    val clarity: Float = 0f,
    val blur: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val cube: Cube? = null,
    val cubeStrength: Float = 1f
)

data class Geo(val straighten: Float = 0f, val persV: Float = 0f, val persH: Float = 0f)
data class Snapshot(val bmp: Bitmap, val adj: Adj, val layers: List<Layer> = emptyList(), val frame: Frame = Frame())
data class CropR(val l: Float, val t: Float, val r: Float, val b: Float)
class FilterDef(val name: String, val m: FloatArray?)

val FILTERS = listOf(
    FilterDef("Original", null),
    FilterDef("Blanco y negro", floatArrayOf(
        .33f, .59f, .11f, 0f, 0f,
        .33f, .59f, .11f, 0f, 0f,
        .33f, .59f, .11f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Noir", floatArrayOf(
        .43f, .77f, .14f, 0f, -45f,
        .43f, .77f, .14f, 0f, -45f,
        .43f, .77f, .14f, 0f, -45f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Sepia", floatArrayOf(
        .393f, .769f, .189f, 0f, 0f,
        .349f, .686f, .168f, 0f, 0f,
        .272f, .534f, .131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Vintage", floatArrayOf(
        .6f, .3f, .1f, 0f, 20f,
        .2f, .7f, .1f, 0f, 10f,
        .2f, .2f, .5f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Desvanecido", floatArrayOf(
        .8f, 0f, 0f, 0f, 35f,
        0f, .8f, 0f, 0f, 35f,
        0f, 0f, .8f, 0f, 40f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Cine", floatArrayOf(
        1.2f, 0f, -.1f, 0f, 0f,
        0f, 1f, .1f, 0f, 0f,
        -.1f, .1f, 1.1f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Cálido", floatArrayOf(
        1.1f, 0f, 0f, 0f, 10f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, .85f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("Frío", floatArrayOf(
        .9f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1.15f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("HDR", floatArrayOf(
        1.3f, -.15f, -.15f, 0f, -15f,
        -.15f, 1.3f, -.15f, 0f, -15f,
        -.15f, -.15f, 1.3f, 0f, -15f,
        0f, 0f, 0f, 1f, 0f))
)

// =====================================================================
//  COLOR (matriz, en tiempo real)
// =====================================================================
fun buildMatrix(a: Adj): AColorMatrix {
    val m = AColorMatrix()
    m.setSaturation(a.saturation)
    val scale = a.contrast * 2f.pow(a.exposure)
    val t = (0.5f - 0.5f * a.contrast) * 255f + a.brightness * 255f
    m.postConcat(AColorMatrix(floatArrayOf(
        scale, 0f, 0f, 0f, t,
        0f, scale, 0f, 0f, t,
        0f, 0f, scale, 0f, t,
        0f, 0f, 0f, 1f, 0f)))
    val tr = a.temperature * 0.2f
    m.postConcat(AColorMatrix(floatArrayOf(
        (1f + tr) * a.rGain, 0f, 0f, 0f, 0f,
        0f, a.gGain, 0f, 0f, 0f,
        0f, 0f, (1f - tr) * a.bGain, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)))
    FILTERS[a.filter].m?.let { m.postConcat(AColorMatrix(it)) }
    return m
}

// =====================================================================
//  EFECTOS POR PÍXEL (niveles, curvas, sombras, nitidez, viñeta, LUT...)
// =====================================================================
fun hasEffects(a: Adj) = a.shadows != 0f || a.highlights != 0f || a.black != 0f || a.white != 1f ||
        a.gamma != 1f || a.curveS != 0f || a.curveM != 0f || a.curveH != 0f || a.sharp > 0f ||
        a.clarity != 0f || a.blur > 0f || a.vignette > 0f || a.grain > 0f || a.cube != null

fun curveAt(v: Float, a: Adj): Float {
    val xs = floatArrayOf(0f, .25f, .5f, .75f, 1f)
    val ys = floatArrayOf(0f, .25f + a.curveS * .2f, .5f + a.curveM * .2f, .75f + a.curveH * .2f, 1f)
    var i = 0
    while (i < 3 && v > xs[i + 1]) i++
    val t = (v - xs[i]) / (xs[i + 1] - xs[i])
    return ys[i] + (ys[i + 1] - ys[i]) * t
}

fun buildLut(a: Adj): IntArray {
    val lut = IntArray(256)
    for (i in 0..255) {
        var v = i / 255f
        v = ((v - a.black) / (a.white - a.black)).coerceIn(0f, 1f)
        v = v.pow(1f / a.gamma)
        v = curveAt(v, a)
        v += a.shadows * 0.3f * (1f - v) * (1f - v)
        v += a.highlights * 0.3f * v * v
        lut[i] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
    return lut
}

fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
    if (r <= 0) return src
    val div = 2 * r + 1
    val tmp = IntArray(w * h)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val p = src[row + i.coerceIn(0, w - 1)]
            sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
        }
        for (x in 0 until w) {
            tmp[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = src[row + min(x + r + 1, w - 1)]
            val rem = src[row + max(x - r, 0)]
            sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
            sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
            sb += (add and 255) - (rem and 255)
        }
    }
    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val p = tmp[i.coerceIn(0, h - 1) * w + x]
            sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
        }
        for (y in 0 until h) {
            out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = tmp[min(y + r + 1, h - 1) * w + x]
            val rem = tmp[max(y - r, 0) * w + x]
            sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
            sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
            sb += (add and 255) - (rem and 255)
        }
    }
    return out
}

fun unsharp(px: IntArray, bl: IntArray, amt: Float): IntArray {
    val out = IntArray(px.size)
    for (i in px.indices) {
        val p = px[i]; val q = bl[i]
        val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
        val qr = (q shr 16) and 255; val qg = (q shr 8) and 255; val qb = q and 255
        val r = (pr + (pr - qr) * amt).toInt().coerceIn(0, 255)
        val g = (pg + (pg - qg) * amt).toInt().coerceIn(0, 255)
        val b = (pb + (pb - qb) * amt).toInt().coerceIn(0, 255)
        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return out
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun sampleCube(c: Cube, r: Int, g: Int, b: Int): Int {
    val n = c.n
    val n1 = n - 1
    val fr = r / 255f * n1; val fg = g / 255f * n1; val fb = b / 255f * n1
    val r0 = fr.toInt(); val g0 = fg.toInt(); val b0 = fb.toInt()
    val r1 = min(r0 + 1, n1); val g1 = min(g0 + 1, n1); val b1 = min(b0 + 1, n1)
    val dr = fr - r0; val dg = fg - g0; val db = fb - b0
    val res = IntArray(3)
    for (ch in 0..2) {
        fun v(ri: Int, gi: Int, bi: Int) = c.data[((bi * n * n) + (gi * n) + ri) * 3 + ch]
        val c00 = lerp(v(r0, g0, b0), v(r1, g0, b0), dr)
        val c10 = lerp(v(r0, g1, b0), v(r1, g1, b0), dr)
        val c01 = lerp(v(r0, g0, b1), v(r1, g0, b1), dr)
        val c11 = lerp(v(r0, g1, b1), v(r1, g1, b1), dr)
        val c0 = lerp(c00, c10, dg)
        val c1 = lerp(c01, c11, dg)
        res[ch] = (lerp(c0, c1, db) * 255f).toInt().coerceIn(0, 255)
    }
    return (res[0] shl 16) or (res[1] shl 8) or res[2]
}

fun applyEffects(src: Bitmap, a: Adj): Bitmap {
    if (!hasEffects(a)) return src
    val w = src.width
    val h = src.height
    val n = w * h
    var px = IntArray(n)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val alpha: IntArray? = if (src.hasAlpha()) IntArray(n) { px[it] ushr 24 } else null

    val useLut = a.black != 0f || a.white != 1f || a.gamma != 1f || a.curveS != 0f ||
            a.curveM != 0f || a.curveH != 0f || a.shadows != 0f || a.highlights != 0f
    val lut = if (useLut) buildLut(a) else null
    val cube = a.cube
    if (lut != null || cube != null) {
        for (i in 0 until n) {
            val p = px[i]
            var r = (p shr 16) and 255
            var g = (p shr 8) and 255
            var b = p and 255
            if (lut != null) { r = lut[r]; g = lut[g]; b = lut[b] }
            if (cube != null) {
                val s = sampleCube(cube, r, g, b)
                val st = a.cubeStrength
                r = (r + (((s shr 16) and 255) - r) * st).toInt().coerceIn(0, 255)
                g = (g + (((s shr 8) and 255) - g) * st).toInt().coerceIn(0, 255)
                b = (b + ((s and 255) - b) * st).toInt().coerceIn(0, 255)
            }
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    if (a.blur > 0f) {
        val rad = (a.blur * w / 60f).toInt().coerceAtLeast(1)
        px = boxBlur(boxBlur(px, w, h, rad), w, h, rad)
    }
    if (a.sharp > 0f) {
        val bl = boxBlur(px, w, h, max(1, w / 700))
        px = unsharp(px, bl, a.sharp * 1.5f)
    }
    if (a.clarity != 0f) {
        val bl = boxBlur(px, w, h, max(2, w / 50))
        px = unsharp(px, bl, a.clarity * 0.8f)
    }
    if (a.vignette > 0f || a.grain > 0f) {
        val rnd = java.util.Random(42)
        val cx = w / 2f
        val cy = h / 2f
        val maxD2 = cx * cx + cy * cy
        for (y in 0 until h) {
            val dy2 = (y - cy) * (y - cy)
            for (x in 0 until w) {
                val idx = y * w + x
                val p = px[idx]
                var f = 1f
                if (a.vignette > 0f) {
                    val d2 = ((x - cx) * (x - cx) + dy2) / maxD2
                    f = (1f - a.vignette * d2 * 1.2f).coerceAtLeast(0f)
                }
                val noise = if (a.grain > 0f) (rnd.nextFloat() - 0.5f) * a.grain * 80f else 0f
                val r = (((p shr 16) and 255) * f + noise).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 255) * f + noise).toInt().coerceIn(0, 255)
                val b = ((p and 255) * f + noise).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    if (alpha != null) for (i in 0 until n) px[i] = (alpha[i] shl 24) or (px[i] and 0xFFFFFF)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

fun histogram(b: Bitmap): IntArray {
    val w = b.width; val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    val hist = IntArray(768)
    for (p in px) {
        hist[(p shr 16) and 255]++
        hist[256 + ((p shr 8) and 255)]++
        hist[512 + (p and 255)]++
    }
    return hist
}

// =====================================================================
//  GEOMETRÍA
// =====================================================================
fun transform(b: Bitmap, f: Matrix.() -> Unit): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply(f), true)

fun cropAspect(b: Bitmap, aw: Int, ah: Int): Bitmap {
    val target = aw.toFloat() / ah
    val cur = b.width.toFloat() / b.height
    var w = b.width
    var h = b.height
    if (cur > target) w = (h * target).toInt() else h = (w / target).toInt()
    return Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
}

fun cropFree(b: Bitmap, c: CropR): Bitmap {
    val x = (c.l * b.width).toInt().coerceIn(0, b.width - 1)
    val y = (c.t * b.height).toInt().coerceIn(0, b.height - 1)
    val w = ((c.r - c.l) * b.width).toInt().coerceIn(1, b.width - x)
    val h = ((c.b - c.t) * b.height).toInt().coerceIn(1, b.height - y)
    return Bitmap.createBitmap(b, x, y, w, h)
}

fun applyGeo(b: Bitmap, g: Geo): Bitmap {
    if (g == Geo()) return b
    var cur = b
    val w = b.width.toFloat()
    val h = b.height.toFloat()
    if (g.persV != 0f || g.persH != 0f) {
        val ti = max(g.persV, 0f) * w * 0.5f
        val bi = max(-g.persV, 0f) * w * 0.5f
        val li = max(g.persH, 0f) * h * 0.5f
        val ri = max(-g.persH, 0f) * h * 0.5f
        val src = floatArrayOf(ti, li, w - ti, ri, w - bi, h - ri, bi, h - li)
        val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        ACanvas(out).drawBitmap(cur, m, Paint(Paint.FILTER_BITMAP_FLAG))
        cur = out
    }
    if (g.straighten != 0f) {
        val rad = Math.toRadians(abs(g.straighten).toDouble())
        val s = (cos(rad) + sin(rad) * max(w / h, h / w)).toFloat()
        val m = Matrix()
        m.setRotate(g.straighten, w / 2f, h / 2f)
        m.postScale(s, s, w / 2f, h / 2f)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        ACanvas(out).drawBitmap(cur, m, Paint(Paint.FILTER_BITMAP_FLAG))
        cur = out
    }
    return cur
}

fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
    val m = max(b.width, b.height)
    if (m <= maxSide) return b
    val k = maxSide.toFloat() / m
    return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
}

// =====================================================================
//  ENTRADA / SALIDA
// =====================================================================
fun loadBitmap(ctx: Context, uri: Uri): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (max(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
    val dec = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dec) } ?: return null
    val orientation = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
    val deg = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (deg == 0f) bmp else transform(bmp) { postRotate(deg) }
}

fun loadExif(ctx: Context, uri: Uri): String {
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val e = ExifInterface(s)
            val l = mutableListOf<String>()
            fun add(name: String, tag: String) { e.getAttribute(tag)?.let { l.add("$name: $it") } }
            add("Marca", ExifInterface.TAG_MAKE)
            add("Modelo", ExifInterface.TAG_MODEL)
            add("Fecha", ExifInterface.TAG_DATETIME)
            add("Exposición (s)", ExifInterface.TAG_EXPOSURE_TIME)
            add("Apertura f/", ExifInterface.TAG_F_NUMBER)
            add("ISO", "ISOSpeedRatings")
            add("Distancia focal", ExifInterface.TAG_FOCAL_LENGTH)
            add("Ancho original", ExifInterface.TAG_IMAGE_WIDTH)
            add("Alto original", ExifInterface.TAG_IMAGE_LENGTH)
            add("Software", ExifInterface.TAG_SOFTWARE)
            if (l.isEmpty()) "Sin datos EXIF" else l.joinToString("\n")
        } ?: "Sin datos EXIF"
    } catch (e: Exception) { "Sin datos EXIF" }
}

fun parseCube(ctx: Context, uri: Uri): Cube? {
    return try {
        var n = 0
        val vals = ArrayList<Float>()
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("TITLE") || t.startsWith("DOMAIN")) continue
                if (t.startsWith("LUT_3D_SIZE")) { n = t.split(Regex("\\s+")).last().toInt(); continue }
                val c = t[0]
                if (c.isDigit() || c == '-' || c == '.') {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 3) { vals.add(parts[0].toFloat()); vals.add(parts[1].toFloat()); vals.add(parts[2].toFloat()) }
                }
            }
        }
        if (n > 1 && vals.size == n * n * n * 3) Cube(n, vals.toFloatArray()) else null
    } catch (e: Exception) { null }
}

fun render(b: Bitmap, adj: Adj, longSide: Int?, whiteBg: Boolean): Bitmap {
    val k = if (longSide == null) 1f else longSide.toFloat() / max(b.width, b.height)
    val w = (b.width * k).toInt().coerceAtLeast(1)
    val h = (b.height * k).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    if (whiteBg) c.drawColor(android.graphics.Color.WHITE)
    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(buildMatrix(adj)) }
    c.drawBitmap(b, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
    return out
}

fun saveToGallery(ctx: Context, bmp: Bitmap, fmt: Int, quality: Int): Pair<Uri, String>? {
    val (ext, mime) = when (fmt) { 0 -> "jpg" to "image/jpeg"; 1 -> "png" to "image/png"; else -> "webp" to "image/webp" }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "foto_${System.currentTimeMillis()}.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EditorFoto")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { os ->
        val cf = when (fmt) {
            0 -> Bitmap.CompressFormat.JPEG
            1 -> Bitmap.CompressFormat.PNG
            else -> if (android.os.Build.VERSION.SDK_INT >= 30)
                (if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY)
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bmp.compress(cf, quality, os)
    }
    ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return uri to mime
}

// =====================================================================
//  UI
// =====================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { EditorScreen() } } }
    }
}

@Composable
fun EditorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var adj by remember { mutableStateOf(Adj()) }
    var geo by remember { mutableStateOf(Geo()) }
    var saved by remember { mutableStateOf<Snapshot?>(null) }
    val undo = remember { mutableStateListOf<Snapshot>() }
    val redo = remember { mutableStateListOf<Snapshot>() }
    var showOriginal by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exifText by remember { mutableStateOf("") }
    var cropMode by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(CropR(0.1f, 0.1f, 0.9f, 0.9f)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val layers = remember { mutableStateListOf<Layer>() }
    var sel by remember { mutableIntStateOf(-1) }
    var frame by remember { mutableStateOf(Frame()) }
    var brushMode by remember { mutableStateOf(false) }
    var brush by remember { mutableStateOf(BrushCfg()) }

    fun commit() {
        val b = bmp ?: return
        saved?.let { undo.add(it) }
        redo.clear()
        saved = Snapshot(b, adj, layers.toList(), frame)
    }
    fun doUndo() {
        val b = bmp ?: return
        if (undo.isEmpty()) return
        redo.add(Snapshot(b, adj, layers.toList(), frame))
        val s = undo.removeAt(undo.lastIndex)
        bmp = s.bmp; adj = s.adj; saved = s; layers.clear(); layers.addAll(s.layers); frame = s.frame
    }
    fun doRedo() {
        val b = bmp ?: return
        if (redo.isEmpty()) return
        undo.add(Snapshot(b, adj, layers.toList(), frame))
        val s = redo.removeAt(redo.lastIndex)
        bmp = s.bmp; adj = s.adj; saved = s; layers.clear(); layers.addAll(s.layers); frame = s.frame
    }
    fun geometry(op: (Bitmap) -> Bitmap) {
        val b = bmp ?: return
        bmp = op(b)
        commit()
    }
    fun newBase(b: Bitmap) {
        bmp = b; adj = Adj(); geo = Geo(); cropMode = false; showOriginal = false
        layers.clear(); sel = -1; frame = Frame(); brushMode = false
        undo.clear(); redo.clear()
        saved = Snapshot(b, adj, layers.toList(), frame)
        exifText = "Imagen creada en la app"
    }
    fun flatten() {
        val b = bmp ?: return
        val a = adj; val g = geo; val ls = layers.toList()
        busy = true
        scope.launch {
            val nb = withContext(Dispatchers.Default) { composeAll(applyEffects(applyGeo(b, g), a), a, ls, Frame(), null) }
            bmp = nb; adj = Adj(); geo = Geo(); layers.clear(); sel = -1; brushMode = false
            busy = false
            commit()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val b = loadBitmap(ctx, it)
            if (b == null) Toast.makeText(ctx, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
            else {
                bmp = b; adj = Adj(); geo = Geo(); cropMode = false; layers.clear(); sel = -1; frame = Frame(); brushMode = false
                undo.clear(); redo.clear(); saved = Snapshot(b, adj, layers.toList(), frame)
                exifText = loadExif(ctx, it)
            }
        }
    }
    val cubePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val c = parseCube(ctx, it)
            if (c == null) Toast.makeText(ctx, "LUT no válida (se necesita .cube 3D)", Toast.LENGTH_LONG).show()
            else { adj = adj.copy(cube = c, cubeStrength = 1f); commit() }
        }
    }

    // Vista previa reducida + efectos en segundo plano
    val small = remember(bmp) { bmp?.let { scaleDown(it, 1024) } }
    var preview by remember(small) { mutableStateOf<Bitmap?>(null) }
    var hist by remember(small) { mutableStateOf<IntArray?>(null) }
    val layersSnap = layers.toList()
    val withFrame = tab != TAB_CAPAS
    LaunchedEffect(small, adj, geo, layersSnap, frame, withFrame) {
        val s = small ?: return@LaunchedEffect
        delay(40)
        val res = withContext(Dispatchers.Default) {
            val e = applyEffects(applyGeo(s, geo), adj)
            val hh = histogram(render(e, adj, 256, false))
            val comp = composeAll(e, adj, layersSnap, if (withFrame) frame else Frame(), null)
            comp to hh
        }
        preview = res.first
        hist = res.second
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                TextButton(onClick = { picker.launch("image/*") }) { Text("Abrir") }
                TextButton(onClick = { doUndo() }, enabled = undo.isNotEmpty()) { Text("Deshacer") }
                TextButton(onClick = { doRedo() }, enabled = redo.isNotEmpty()) { Text("Rehacer") }
                TextButton(onClick = { showOriginal = !showOriginal }, enabled = bmp != null) {
                    Text(if (showOriginal) "Ver editado" else "Ver original")
                }
                Button(onClick = { showExport = true }, enabled = bmp != null) { Text("Exportar") }
            }

            // Vista previa
            Box(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { boxSize = it },
                contentAlignment = Alignment.Center
            ) {
                val s = small
                if (s == null) {
                    Text("Pulsa \"Abrir\" para elegir una foto")
                } else {
                    val shown = if (showOriginal || cropMode) s else (preview ?: s)
                    val img = remember(shown) { shown.asImageBitmap() }
                    Image(
                        bitmap = img, contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (showOriginal || cropMode || preview != null) null
                        else ColorFilter.colorMatrix(ColorMatrix(buildMatrix(adj).array))
                    )
                    if (cropMode && boxSize.width > 0 && boxSize.height > 0) {
                        val bw = s.width.toFloat(); val bh = s.height.toFloat()
                        val sc = min(boxSize.width / bw, boxSize.height / bh)
                        val iw = bw * sc; val ih = bh * sc
                        val ox = (boxSize.width - iw) / 2f; val oy = (boxSize.height - ih) / 2f
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(boxSize, s.width, s.height) {
                                var mode = 0
                                detectDragGestures(
                                    onDragStart = { o ->
                                        val fx = (o.x - ox) / iw
                                        val fy = (o.y - oy) / ih
                                        val c = crop
                                        val thr = 0.1f
                                        fun near(ax: Float, ay: Float) = abs(fx - ax) < thr && abs(fy - ay) < thr
                                        mode = when {
                                            near(c.l, c.t) -> 1
                                            near(c.r, c.t) -> 2
                                            near(c.l, c.b) -> 3
                                            near(c.r, c.b) -> 4
                                            fx in c.l..c.r && fy in c.t..c.b -> 5
                                            else -> 0
                                        }
                                    },
                                    onDrag = { change, d ->
                                        change.consume()
                                        val dx = d.x / iw
                                        val dy = d.y / ih
                                        var l = crop.l; var t = crop.t; var r = crop.r; var b = crop.b
                                        when (mode) {
                                            1 -> { l += dx; t += dy }
                                            2 -> { r += dx; t += dy }
                                            3 -> { l += dx; b += dy }
                                            4 -> { r += dx; b += dy }
                                            5 -> {
                                                val w = r - l; val h = b - t
                                                l = (l + dx).coerceIn(0f, 1f - w); r = l + w
                                                t = (t + dy).coerceIn(0f, 1f - h); b = t + h
                                            }
                                        }
                                        if (mode in 1..4) {
                                            l = l.coerceIn(0f, r - 0.05f)
                                            r = r.coerceIn(l + 0.05f, 1f)
                                            t = t.coerceIn(0f, b - 0.05f)
                                            b = b.coerceIn(t + 0.05f, 1f)
                                        }
                                        if (mode != 0) crop = CropR(l, t, r, b)
                                    }
                                )
                            }
                        ) {
                            val x1 = ox + crop.l * iw; val y1 = oy + crop.t * ih
                            val x2 = ox + crop.r * iw; val y2 = oy + crop.b * ih
                            val shade = Color.Black.copy(alpha = 0.55f)
                            drawRect(shade, Offset(ox, oy), Size(iw, y1 - oy))
                            drawRect(shade, Offset(ox, y2), Size(iw, oy + ih - y2))
                            drawRect(shade, Offset(ox, y1), Size(x1 - ox, y2 - y1))
                            drawRect(shade, Offset(x2, y1), Size(ox + iw - x2, y2 - y1))
                            drawRect(Color.White, Offset(x1, y1), Size(x2 - x1, y2 - y1), style = Stroke(3f))
                            for (pt in listOf(Offset(x1, y1), Offset(x2, y1), Offset(x1, y2), Offset(x2, y2)))
                                drawCircle(Color.White, 14f, pt)
                        }
                    }

                    if (tab == TAB_CAPAS && !cropMode && boxSize.width > 0 && boxSize.height > 0) {
                        val bw2 = s.width.toFloat(); val bh2 = s.height.toFloat()
                        val sc2 = min(boxSize.width / bw2, boxSize.height / bh2)
                        val iw2 = bw2 * sc2; val ih2 = bh2 * sc2
                        val ox2 = (boxSize.width - iw2) / 2f; val oy2 = (boxSize.height - ih2) / 2f
                        Box(
                            Modifier.fillMaxSize().pointerInput(boxSize, s.width, s.height, brushMode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val li = sel
                                    val cur0 = layers.getOrNull(li)
                                    if (cur0 == null) {
                                        do {
                                            val e0 = awaitPointerEvent()
                                        } while (e0.changes.any { it.pressed })
                                        return@awaitEachGesture
                                    }
                                    if (brushMode && cur0.kind == 2) {
                                        val b0 = brush
                                        val pts = mutableListOf(Pair((down.position.x - ox2) / iw2, (down.position.y - oy2) / ih2))
                                        layers[li] = cur0.copy(strokes = cur0.strokes + BrushStroke(pts.toList(), b0.color, b0.size, b0.erase))
                                        do {
                                            val e1 = awaitPointerEvent()
                                            e1.changes.forEach { c1 ->
                                                if (c1.positionChanged()) {
                                                    pts.add(Pair((c1.position.x - ox2) / iw2, (c1.position.y - oy2) / ih2))
                                                    c1.consume()
                                                }
                                            }
                                            layers[li] = cur0.copy(strokes = cur0.strokes + BrushStroke(pts.toList(), b0.color, b0.size, b0.erase))
                                        } while (e1.changes.any { it.pressed })
                                        commit()
                                    } else {
                                        do {
                                            val e2 = awaitPointerEvent()
                                            val pan = e2.calculatePan()
                                            val zoom = e2.calculateZoom()
                                            val rot = e2.calculateRotation()
                                            val l2 = layers.getOrNull(li)
                                            if (l2 != null) {
                                                layers[li] = l2.copy(
                                                    cx = l2.cx + pan.x / iw2, cy = l2.cy + pan.y / ih2,
                                                    scale = (l2.scale * zoom).coerceIn(0.1f, 6f), rot = l2.rot + rot
                                                )
                                            }
                                            e2.changes.forEach { if (it.positionChanged()) it.consume() }
                                        } while (e2.changes.any { it.pressed })
                                        commit()
                                    }
                                }
                            }
                        ) {}
                    }
                }
            }

            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Recortar", "Ajustes", "Detalle", "Color", "Filtros", "Capas", "Diseño", "Info").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            val has = bmp != null
            Column(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (tab) {
                    0 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1:1" to (1 to 1), "4:5" to (4 to 5), "9:16" to (9 to 16), "16:9" to (16 to 9)).forEach { (n, r) ->
                                OutlinedButton(onClick = { geometry { cropAspect(it, r.first, r.second) } }, enabled = has) { Text(n) }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { geometry { transform(it) { postRotate(90f) } } }, enabled = has) { Text("Girar 90°") }
                            OutlinedButton(onClick = { geometry { transform(it) { preScale(-1f, 1f) } } }, enabled = has) { Text("Voltear H") }
                            OutlinedButton(onClick = { geometry { transform(it) { preScale(1f, -1f) } } }, enabled = has) { Text("Voltear V") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!cropMode) {
                                OutlinedButton(onClick = { crop = CropR(0.1f, 0.1f, 0.9f, 0.9f); cropMode = true }, enabled = has) { Text("Recorte libre") }
                            } else {
                                Button(onClick = { val c = crop; geometry { cropFree(it, c) }; cropMode = false }) { Text("Aplicar recorte") }
                                TextButton(onClick = { cropMode = false }) { Text("Cancelar") }
                            }
                        }
                        SliderRow("Enderezar", geo.straighten, -15f..15f, { geo = geo.copy(straighten = it) }, {})
                        SliderRow("Persp. vertical", geo.persV, -0.3f..0.3f, { geo = geo.copy(persV = it) }, {})
                        SliderRow("Persp. horiz.", geo.persH, -0.3f..0.3f, { geo = geo.copy(persH = it) }, {})
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val g = geo; geometry { applyGeo(it, g) }; geo = Geo() }, enabled = has && geo != Geo()) { Text("Aplicar") }
                            TextButton(onClick = { geo = Geo() }, enabled = geo != Geo()) { Text("Reiniciar") }
                        }
                    }
                    1 -> {
                        SliderRow("Brillo", adj.brightness, -0.5f..0.5f, { adj = adj.copy(brightness = it) }, ::commit)
                        SliderRow("Contraste", adj.contrast, 0.5f..1.5f, { adj = adj.copy(contrast = it) }, ::commit)
                        SliderRow("Exposición", adj.exposure, -1f..1f, { adj = adj.copy(exposure = it) }, ::commit)
                        SliderRow("Saturación", adj.saturation, 0f..2f, { adj = adj.copy(saturation = it) }, ::commit)
                        SliderRow("Temperatura", adj.temperature, -1f..1f, { adj = adj.copy(temperature = it) }, ::commit)
                        SliderRow("Sombras", adj.shadows, -1f..1f, { adj = adj.copy(shadows = it) }, ::commit)
                        SliderRow("Luces", adj.highlights, -1f..1f, { adj = adj.copy(highlights = it) }, ::commit)
                        TextButton(onClick = { adj = Adj(); commit() }, enabled = has) { Text("Restablecer todo") }
                    }
                    2 -> {
                        SliderRow("Nitidez", adj.sharp, 0f..1f, { adj = adj.copy(sharp = it) }, ::commit)
                        SliderRow("Claridad", adj.clarity, -1f..1f, { adj = adj.copy(clarity = it) }, ::commit)
                        SliderRow("Desenfoque", adj.blur, 0f..1f, { adj = adj.copy(blur = it) }, ::commit)
                        SliderRow("Viñeta", adj.vignette, 0f..1f, { adj = adj.copy(vignette = it) }, ::commit)
                        SliderRow("Grano", adj.grain, 0f..1f, { adj = adj.copy(grain = it) }, ::commit)
                    }
                    3 -> {
                        Text("Canales RGB", style = MaterialTheme.typography.labelLarge)
                        SliderRow("Rojo", adj.rGain, 0.5f..1.5f, { adj = adj.copy(rGain = it) }, ::commit)
                        SliderRow("Verde", adj.gGain, 0.5f..1.5f, { adj = adj.copy(gGain = it) }, ::commit)
                        SliderRow("Azul", adj.bGain, 0.5f..1.5f, { adj = adj.copy(bGain = it) }, ::commit)
                        Text("Niveles", style = MaterialTheme.typography.labelLarge)
                        SliderRow("Negro", adj.black, 0f..0.45f, { adj = adj.copy(black = it) }, ::commit)
                        SliderRow("Blanco", adj.white, 0.55f..1f, { adj = adj.copy(white = it) }, ::commit)
                        SliderRow("Gamma", adj.gamma, 0.5f..2f, { adj = adj.copy(gamma = it) }, ::commit)
                        Text("Curva", style = MaterialTheme.typography.labelLarge)
                        SliderRow("Sombras", adj.curveS, -1f..1f, { adj = adj.copy(curveS = it) }, ::commit)
                        SliderRow("Medios", adj.curveM, -1f..1f, { adj = adj.copy(curveM = it) }, ::commit)
                        SliderRow("Luces", adj.curveH, -1f..1f, { adj = adj.copy(curveH = it) }, ::commit)
                    }
                    4 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FILTERS.forEachIndexed { i, f ->
                                FilterChip(selected = adj.filter == i, onClick = { adj = adj.copy(filter = i); commit() },
                                    label = { Text(f.name) }, enabled = has)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { cubePicker.launch("*/*") }, enabled = has) { Text("Cargar LUT (.cube)") }
                            if (adj.cube != null) TextButton(onClick = { adj = adj.copy(cube = null); commit() }) { Text("Quitar LUT") }
                        }
                        if (adj.cube != null)
                            SliderRow("Intensidad LUT", adj.cubeStrength, 0f..1f, { adj = adj.copy(cubeStrength = it) }, ::commit)
                    }
                    5 -> LayersPanel(
                        layers, sel, { sel = it }, brushMode, { brushMode = it }, brush, { brush = it },
                        bmp?.width ?: 1, bmp?.height ?: 1, ::commit, ::flatten
                    )
                    6 -> DesignPanel(frame, { frame = it }, ::commit, ::newBase)
                    else -> {
                        Text("Histograma (RGB)", style = MaterialTheme.typography.labelLarge)
                        Histogram(hist, Modifier.fillMaxWidth().height(90.dp).background(Color(0xFF202020)))
                        bmp?.let { Text("Tamaño de trabajo: ${it.width} x ${it.height} px") }
                        Text("EXIF de la foto original", style = MaterialTheme.typography.labelLarge)
                        Text(if (bmp == null) "Abre una foto" else exifText)
                    }
                }
            }
        }

        if (busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x88000000)).pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
    }

    if (showExport && bmp != null) {
        ExportDialog(
            onDismiss = { showExport = false },
            onExport = { fmt, quality, side, share ->
                val src = bmp!!
                val adjNow = adj
                val geoNow = geo
                val layersNow = layers.toList()
                val frameNow = frame
                showExport = false
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.Default) {
                        try {
                            val e = applyEffects(applyGeo(src, geoNow), adjNow)
                            val out = composeAll(e, adjNow, layersNow, frameNow, side, fmt == 0)
                            saveToGallery(ctx, out, fmt, quality)
                        } catch (t: Throwable) { null }
                    }
                    busy = false
                    if (res == null) Toast.makeText(ctx, "Error al guardar", Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(ctx, "Guardada en Pictures/EditorFoto", Toast.LENGTH_SHORT).show()
                        if (share) {
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = res.second
                                putExtra(Intent.EXTRA_STREAM, res.first)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(i, "Compartir"))
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun Histogram(h: IntArray?, modifier: Modifier) {
    Canvas(modifier) {
        if (h == null) return@Canvas
        var mx = 1
        for (c in 0..2) for (i in 1..254) mx = max(mx, h[c * 256 + i])
        val cols = listOf(Color(0x99FF4444), Color(0x9944FF44), Color(0x994488FF))
        val bw = size.width / 256f
        for (c in 0..2) for (i in 0..255) {
            val v = min(h[c * 256 + i].toFloat() / mx, 1f)
            drawLine(cols[c], Offset(i * bw, size.height), Offset(i * bw, size.height - v * size.height), strokeWidth = bw + 0.5f)
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onFinish, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ExportDialog(onDismiss: () -> Unit, onExport: (fmt: Int, quality: Int, side: Int?, share: Boolean) -> Unit) {
    var fmt by remember { mutableIntStateOf(0) }
    var quality by remember { mutableFloatStateOf(90f) }
    var size by remember { mutableIntStateOf(0) }
    val sizes = listOf("Original" to null, "HD" to 1280, "Full HD" to 1920, "2K" to 2560, "4K" to 3840)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Formato")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("JPG", "PNG", "WEBP").forEachIndexed { i, n ->
                        FilterChip(selected = fmt == i, onClick = { fmt = i }, label = { Text(n) })
                    }
                }
                Text("Tamaño (lado mayor)")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEachIndexed { i, s ->
                        FilterChip(selected = size == i, onClick = { size = i }, label = { Text(s.first) })
                    }
                }
                if (fmt != 1) {
                    Text("Calidad: ${quality.toInt()}%")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 10f..100f)
                } else Text("PNG: sin pérdida")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onExport(fmt, quality.toInt(), sizes[size].second, false) }) { Text("Guardar") }
                TextButton(onClick = { onExport(fmt, quality.toInt(), sizes[size].second, true) }) { Text("Compartir") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
