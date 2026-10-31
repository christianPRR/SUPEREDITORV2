package com.example.editorfoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as ACanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val TAB_RETOQUE = 7

class SubjectMask(val src: Bitmap, val a: ByteArray)

suspend fun <T> Task<T>.awaitT(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

// =====================================================================
//  SEGMENTACIÓN (persona / objeto)
// =====================================================================
suspend fun detectSubject(bmp: Bitmap, objects: Boolean): SubjectMask {
    val w = bmp.width
    val h = bmp.height
    val image = InputImage.fromBitmap(bmp, 0)
    val conf = FloatArray(w * h)
    if (objects) {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            val res = seg.process(image).awaitT()
            val fb = res.foregroundConfidenceMask ?: throw IllegalStateException("Sin máscara")
            val n = min(conf.size, fb.limit())
            for (i in 0 until n) conf[i] = fb.get(i)
        } finally { seg.close() }
    } else {
        val seg = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        try {
            val res = seg.process(image).awaitT()
            val mw = res.width
            val mh = res.height
            val bb = res.buffer
            bb.rewind()
            val tmp = FloatArray(mw * mh)
            for (i in tmp.indices) tmp[i] = bb.getFloat()
            for (y in 0 until h) for (x in 0 until w) conf[y * w + x] = tmp[(y * mh / h) * mw + (x * mw / w)]
        } finally { seg.close() }
    }
    var g = IntArray(w * h)
    for (i in g.indices) {
        val t = ((conf[i] - 0.35f) / 0.5f).coerceIn(0f, 1f)
        val a = (t * t * (3f - 2f * t) * 255f).toInt()
        g[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
    }
    g = boxBlur(g, w, h, max(1, w / 700))
    val bytes = ByteArray(w * h) { (g[it] and 255).toByte() }
    return SubjectMask(bmp, bytes)
}

fun cutout(b: Bitmap, m: SubjectMask): Bitmap {
    val w = b.width; val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val a = (px[i] ushr 24) * (m.a[i].toInt() and 255) / 255
        px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

fun fillCrop(c: ACanvas, bg: Bitmap, w: Int, h: Int) {
    val sc = max(w / bg.width.toFloat(), h / bg.height.toFloat())
    val sw = w / sc
    val sh = h / sc
    val sx = (bg.width - sw) / 2f
    val sy = (bg.height - sh) / 2f
    c.drawBitmap(bg, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
}

fun replaceBg(b: Bitmap, m: SubjectMask, bg: Bitmap?, color: Int): Bitmap {
    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    if (bg != null) fillCrop(c, bg, b.width, b.height) else c.drawColor(color)
    c.drawBitmap(cutout(b, m), 0f, 0f, null)
    return out
}

fun blurBg(b: Bitmap, m: SubjectMask, strength: Float): Bitmap {
    val w = b.width; val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    val r = max(2, (strength * w / 35f).toInt())
    val bl = boxBlur(boxBlur(px, w, h, r), w, h, r)
    for (i in px.indices) {
        val a = m.a[i].toInt() and 255
        if (a == 255) continue
        val p = px[i]; val q = bl[i]
        val k = a / 255f
        val rr = lerp(((q shr 16) and 255).toFloat(), ((p shr 16) and 255).toFloat(), k).toInt()
        val gg = lerp(((q shr 8) and 255).toFloat(), ((p shr 8) and 255).toFloat(), k).toInt()
        val bb = lerp((q and 255).toFloat(), (p and 255).toFloat(), k).toInt()
        px[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

// ---------- Cielo ----------
fun skyBitmap(preset: Int, w: Int, h: Int, photo: Bitmap?): Bitmap {
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bm)
    if (photo != null) { fillCrop(c, photo, w, h); return bm }
    val cols = when (preset) {
        1 -> intArrayOf(0xFF2B2D6E.toInt(), 0xFFD6546A.toInt(), 0xFFFFB35C.toInt())
        2 -> intArrayOf(0xFF02030E.toInt(), 0xFF0B1A40.toInt(), 0xFF1D3466.toInt())
        3 -> intArrayOf(0xFF6E7881.toInt(), 0xFF9AA3AB.toInt(), 0xFFD3D8DC.toInt())
        else -> intArrayOf(0xFF1565C0.toInt(), 0xFF64B5F6.toInt(), 0xFFBBDEFB.toInt())
    }
    val p = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h * 0.75f, cols, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    val rnd = java.util.Random(7)
    if (preset == 2) {
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        for (i in 0 until 160) {
            sp.alpha = 120 + rnd.nextInt(135)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, (0.6f + rnd.nextFloat() * 2f) * w / 1080f, sp)
        }
    }
    if (preset == 3 || preset == 0) {
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val n = if (preset == 3) 70 else 14
        for (i in 0 until n) {
            val rad = w * (0.04f + rnd.nextFloat() * 0.11f)
            cp.alpha = if (preset == 3) 40 else 70
            cp.maskFilter = BlurMaskFilter(rad * 0.6f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, rad, cp)
        }
    }
    return bm
}

fun replaceSky(b: Bitmap, preset: Int, photo: Bitmap?, subject: SubjectMask?): Bitmap? {
    val w = b.width; val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    fun isSky(i: Int): Boolean {
        val p = px[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl))
        val blueish = bl > r + 12 && bl >= g - 6 && bl > 80
        val cloud = mn > 150 && (mx - mn) < 30
        return blueish || cloud
    }
    val maxRow = (h * 0.75f).toInt()
    val visited = BooleanArray(w * h)
    val stack = IntArray(w * h)
    var sp = 0
    for (x in 0 until w) if (isSky(x)) { visited[x] = true; stack[sp++] = x }
    var count = 0
    while (sp > 0) {
        val i = stack[--sp]
        count++
        val x = i % w; val y = i / w
        if (x > 0) { val j = i - 1; if (!visited[j] && isSky(j)) { visited[j] = true; stack[sp++] = j } }
        if (x < w - 1) { val j = i + 1; if (!visited[j] && isSky(j)) { visited[j] = true; stack[sp++] = j } }
        if (y > 0) { val j = i - w; if (!visited[j] && isSky(j)) { visited[j] = true; stack[sp++] = j } }
        if (y < maxRow) { val j = i + w; if (!visited[j] && isSky(j)) { visited[j] = true; stack[sp++] = j } }
    }
    if (count < w * h * 0.02) return null
    var g = IntArray(w * h)
    for (i in g.indices) {
        val a = if (visited[i]) 255 else 0
        g[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
    }
    val rad = max(2, w / 200)
    g = boxBlur(boxBlur(g, w, h, rad), w, h, rad)
    val sky = skyBitmap(preset, w, h, photo)
    val sp2 = IntArray(w * h)
    sky.getPixels(sp2, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        var a = g[i] and 255
        if (subject != null) a = a * (255 - (subject.a[i].toInt() and 255)) / 255
        if (a == 0) continue
        val k = a / 255f
        val p = px[i]; val q = sp2[i]
        val rr = lerp(((p shr 16) and 255).toFloat(), ((q shr 16) and 255).toFloat(), k).toInt()
        val gg = lerp(((p shr 8) and 255).toFloat(), ((q shr 8) and 255).toFloat(), k).toInt()
        val bb = lerp((p and 255).toFloat(), (q and 255).toFloat(), k).toInt()
        px[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

// =====================================================================
//  RETOQUE: borrar / curar / clonar
// =====================================================================
fun fillUnknown(px: IntArray, cw: Int, ch: Int, unknown: BooleanArray) {
    val n = cw * ch
    val known = BooleanArray(n) { !unknown[it] }
    val queued = BooleanArray(n)
    var frontier = ArrayList<Int>()
    for (i in 0 until n) {
        if (!unknown[i]) continue
        val x = i % cw; val y = i / cw
        val hasKnown = (x > 0 && known[i - 1]) || (x < cw - 1 && known[i + 1]) ||
                (y > 0 && known[i - cw]) || (y < ch - 1 && known[i + cw])
        if (hasKnown) { frontier.add(i); queued[i] = true }
    }
    while (frontier.isNotEmpty()) {
        val newVals = IntArray(frontier.size)
        for (k in frontier.indices) {
            val i = frontier[k]
            val x = i % cw; val y = i / cw
            var sr = 0; var sg = 0; var sb = 0; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                val j = ny * cw + nx
                if (known[j]) {
                    val p = px[j]
                    sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255; cnt++
                }
            }
            newVals[k] = if (cnt > 0) (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt) else px[i]
        }
        val next = ArrayList<Int>()
        for (k in frontier.indices) { px[frontier[k]] = newVals[k]; known[frontier[k]] = true }
        for (i in frontier) {
            val x = i % cw; val y = i / cw
            if (x > 0) { val j = i - 1; if (unknown[j] && !queued[j]) { queued[j] = true; next.add(j) } }
            if (x < cw - 1) { val j = i + 1; if (unknown[j] && !queued[j]) { queued[j] = true; next.add(j) } }
            if (y > 0) { val j = i - cw; if (unknown[j] && !queued[j]) { queued[j] = true; next.add(j) } }
            if (y < ch - 1) { val j = i + cw; if (unknown[j] && !queued[j]) { queued[j] = true; next.add(j) } }
        }
        frontier = next
    }
}

fun inpaintStroke(b: Bitmap, pts: List<Pair<Float, Float>>, sizeFrac: Float): Bitmap {
    val w = b.width; val h = b.height
    val radius = max(3f, sizeFrac * w / 2f)
    var minX = w.toFloat(); var minY = h.toFloat(); var maxX = 0f; var maxY = 0f
    for (p in pts) {
        val x = p.first * w; val y = p.second * h
        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
    }
    val m = (radius + 12f).toInt()
    val l = (minX - m).toInt().coerceIn(0, w - 1)
    val t = (minY - m).toInt().coerceIn(0, h - 1)
    val r = (maxX + m).toInt().coerceIn(l + 1, w)
    val bt = (maxY + m).toInt().coerceIn(t + 1, h)
    val cw = r - l; val ch = bt - t
    val mb = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    val c = ACanvas(mb)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = radius * 2f
    }
    if (pts.size == 1) c.drawPoint(pts[0].first * w - l, pts[0].second * h - t, paint)
    else {
        val path = Path()
        path.moveTo(pts[0].first * w - l, pts[0].second * h - t)
        for (i in 1 until pts.size) path.lineTo(pts[i].first * w - l, pts[i].second * h - t)
        c.drawPath(path, paint)
    }
    val mp = IntArray(cw * ch)
    mb.getPixels(mp, 0, cw, 0, 0, cw, ch)
    val unknown = BooleanArray(cw * ch) { (mp[it] ushr 24) > 30 }
    val px = IntArray(cw * ch)
    b.getPixels(px, 0, cw, l, t, cw, ch)
    fillUnknown(px, cw, ch, unknown)
    val bl = boxBlur(px, cw, ch, 2)
    val rnd = java.util.Random(3)
    for (i in px.indices) if (unknown[i]) {
        val q = bl[i]
        val nz = rnd.nextInt(7) - 3
        val rr = (((q shr 16) and 255) + nz).coerceIn(0, 255)
        val gg = (((q shr 8) and 255) + nz).coerceIn(0, 255)
        val bb = ((q and 255) + nz).coerceIn(0, 255)
        px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    val out = b.copy(Bitmap.Config.ARGB_8888, true)
    out.setPixels(px, 0, cw, l, t, cw, ch)
    return out
}

fun cloneStroke(b: Bitmap, pts: List<Pair<Float, Float>>, sizeFrac: Float, off: Pair<Float, Float>): Bitmap {
    val w = b.width; val h = b.height
    val radius = max(3f, sizeFrac * w / 2f)
    val src = IntArray(w * h)
    b.getPixels(src, 0, w, 0, 0, w, h)
    val dst = src.copyOf()
    val offX = (off.first * w).toInt()
    val offY = (off.second * h).toInt()
    val step = max(1f, radius / 3f)
    val stamps = ArrayList<FloatArray>()
    stamps.add(floatArrayOf(pts[0].first * w, pts[0].second * h))
    for (i in 1 until pts.size) {
        val x0 = pts[i - 1].first * w; val y0 = pts[i - 1].second * h
        val x1 = pts[i].first * w; val y1 = pts[i].second * h
        val n = ceil(hypot(x1 - x0, y1 - y0) / step).toInt().coerceAtLeast(1)
        for (k in 1..n) { val t = k / n.toFloat(); stamps.add(floatArrayOf(lerp(x0, x1, t), lerp(y0, y1, t))) }
    }
    for (s in stamps) {
        val cx = s[0]; val cy = s[1]
        val xa = (cx - radius).toInt().coerceAtLeast(0)
        val xb = (cx + radius).toInt().coerceAtMost(w - 1)
        val ya = (cy - radius).toInt().coerceAtLeast(0)
        val yb = (cy + radius).toInt().coerceAtMost(h - 1)
        for (yy in ya..yb) for (xx in xa..xb) {
            val d = sqrt((xx - cx) * (xx - cx) + (yy - cy) * (yy - cy))
            if (d > radius) continue
            var wgt = 1f - d / radius
            wgt = wgt * wgt * (3f - 2f * wgt)
            val sx = xx + offX; val sy = yy + offY
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
            val q = src[sy * w + sx]
            val idx = yy * w + xx
            val p = dst[idx]
            val rr = lerp(((p shr 16) and 255).toFloat(), ((q shr 16) and 255).toFloat(), wgt).toInt()
            val gg = lerp(((p shr 8) and 255).toFloat(), ((q shr 8) and 255).toFloat(), wgt).toInt()
            val bb = lerp((p and 255).toFloat(), (q and 255).toFloat(), wgt).toInt()
            dst[idx] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(dst, 0, w, 0, 0, w, h)
    return out
}

// =====================================================================
//  ROSTRO: piel, dientes, ojos rojos
// =====================================================================
class Region(val r: Rect, val px: IntArray)

fun regionOf(b: Bitmap, face: Face): Region {
    val bb = face.boundingBox
    val mx = (bb.width() * 0.15f).toInt()
    val my = (bb.height() * 0.15f).toInt()
    val l = (bb.left - mx).coerceIn(0, b.width - 1)
    val t = (bb.top - my).coerceIn(0, b.height - 1)
    val rr = (bb.right + mx).coerceIn(l + 1, b.width)
    val bt = (bb.bottom + my).coerceIn(t + 1, b.height)
    val r = Rect(l, t, rr, bt)
    val px = IntArray(r.width() * r.height())
    b.getPixels(px, 0, r.width(), l, t, r.width(), r.height())
    return Region(r, px)
}

fun writeRegion(b: Bitmap, reg: Region): Bitmap {
    val out = b.copy(Bitmap.Config.ARGB_8888, true)
    out.setPixels(reg.px, 0, reg.r.width(), reg.r.left, reg.r.top, reg.r.width(), reg.r.height())
    return out
}

fun pathOf(poly: List<PointF>, ox: Int, oy: Int): Path {
    val p = Path()
    poly.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x - ox, pt.y - oy) else p.lineTo(pt.x - ox, pt.y - oy) }
    p.close()
    return p
}

fun polyMask(cw: Int, ch: Int, add: List<List<PointF>>, sub: List<List<PointF>>, ox: Int, oy: Int, feather: Int): ByteArray {
    val bm = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bm)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f
    }
    for (poly in add) c.drawPath(pathOf(poly, ox, oy), fill)
    val clr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); style = Paint.Style.FILL
    }
    for (poly in sub) c.drawPath(pathOf(poly, ox, oy), clr)
    val px = IntArray(cw * ch)
    bm.getPixels(px, 0, cw, 0, 0, cw, ch)
    var g = IntArray(cw * ch) { val a = px[it] ushr 24; (0xFF shl 24) or (a shl 16) or (a shl 8) or a }
    if (feather > 0) g = boxBlur(g, cw, ch, feather)
    return ByteArray(cw * ch) { (g[it] and 255).toByte() }
}

fun skinSmooth(b: Bitmap, face: Face, s: Float): Bitmap? {
    val poly = face.getContour(FaceContour.FACE)?.points ?: return null
    val reg = regionOf(b, face)
    val cw = reg.r.width(); val ch = reg.r.height()
    val subs = mutableListOf<List<PointF>>()
    face.getContour(FaceContour.LEFT_EYE)?.points?.let { subs.add(it) }
    face.getContour(FaceContour.RIGHT_EYE)?.points?.let { subs.add(it) }
    val up = face.getContour(FaceContour.UPPER_LIP_TOP)?.points
    val lo = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
    if (up != null && lo != null) subs.add(up + lo.reversed())
    val mask = polyMask(cw, ch, listOf(poly), subs, reg.r.left, reg.r.top, max(2, cw / 40))
    val rad = max(2, (cw * 0.012f * (0.5f + s)).toInt())
    val bl = boxBlur(boxBlur(reg.px, cw, ch, rad), cw, ch, rad)
    val px = reg.px
    for (i in px.indices) {
        val m = (mask[i].toInt() and 255) / 255f
        if (m == 0f) continue
        val p = px[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * bb
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * bb
        if (cb < 77f || cb > 127f || cr < 133f || cr > 173f) continue
        val k = m * s * 0.9f
        val q = bl[i]
        val nr = lerp(r.toFloat(), ((q shr 16) and 255).toFloat(), k).toInt()
        val ng = lerp(g.toFloat(), ((q shr 8) and 255).toFloat(), k).toInt()
        val nb = lerp(bb.toFloat(), (q and 255).toFloat(), k).toInt()
        px[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
    return writeRegion(b, reg)
}

fun whiten(b: Bitmap, face: Face, s: Float): Bitmap? {
    val up = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
    val lo = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return null
    val reg = regionOf(b, face)
    val cw = reg.r.width(); val ch = reg.r.height()
    val mask = polyMask(cw, ch, listOf(up + lo.reversed()), emptyList(), reg.r.left, reg.r.top, max(1, cw / 200))
    val px = reg.px
    for (i in px.indices) {
        val m = (mask[i].toInt() and 255) / 255f
        if (m == 0f) continue
        val p = px[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
        val lum = 0.299f * r + 0.587f * g + 0.114f * bb
        val sat = max(r, max(g, bb)) - min(r, min(g, bb))
        if (lum < 80f || sat > 110) continue
        val k = m * s
        val gray = (r + g + bb) / 3f
        fun f(c: Int): Int {
            var v = c + (gray - c) * 0.5f * k
            v += (255f - v) * 0.3f * k
            return v.toInt().coerceIn(0, 255)
        }
        px[i] = (p and 0xFF000000.toInt()) or (f(r) shl 16) or (f(g) shl 8) or f(bb)
    }
    return writeRegion(b, reg)
}

fun redEye(b: Bitmap, face: Face): Bitmap? {
    val le = face.getContour(FaceContour.LEFT_EYE)?.points
    val re = face.getContour(FaceContour.RIGHT_EYE)?.points
    val eyes = listOfNotNull(le, re)
    if (eyes.isEmpty()) return null
    val reg = regionOf(b, face)
    val cw = reg.r.width(); val ch = reg.r.height()
    val mask = polyMask(cw, ch, eyes, emptyList(), reg.r.left, reg.r.top, 1)
    val px = reg.px
    for (i in px.indices) {
        val m = (mask[i].toInt() and 255) / 255f
        if (m == 0f) continue
        val p = px[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
        if (r > 60 && r > g * 1.5f && r > bb * 1.5f) {
            val target = ((g + bb) / 2f * 0.8f)
            val nr = lerp(r.toFloat(), target, m).toInt()
            px[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (g shl 8) or bb
        }
    }
    return writeRegion(b, reg)
}

/** kind: 0 piel, 1 dientes, 2 ojos rojos. Devuelve null si no hay rostro. */
suspend fun faceEdit(b: Bitmap, kind: Int, strength: Float): Bitmap? {
    val opts = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .build()
    val det = FaceDetection.getClient(opts)
    val faces = try { det.process(InputImage.fromBitmap(b, 0)).awaitT() } finally { det.close() }
    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
    return withContext(Dispatchers.Default) {
        when (kind) {
            0 -> skinSmooth(b, face, strength)
            1 -> whiten(b, face, strength)
            else -> redEye(b, face)
        }
    }
}

// =====================================================================
//  PROYECTOS EDITABLES (.efp = zip con base.png + project.json + capas)
// =====================================================================
class Project(val bmp: Bitmap, val adj: Adj, val geo: Geo, val frame: Frame, val layers: List<Layer>)

fun adjToJson(a: Adj) = JSONObject().apply {
    put("brightness", a.brightness.toDouble()); put("contrast", a.contrast.toDouble())
    put("exposure", a.exposure.toDouble()); put("saturation", a.saturation.toDouble())
    put("temperature", a.temperature.toDouble()); put("rGain", a.rGain.toDouble())
    put("gGain", a.gGain.toDouble()); put("bGain", a.bGain.toDouble()); put("filter", a.filter)
    put("shadows", a.shadows.toDouble()); put("highlights", a.highlights.toDouble())
    put("black", a.black.toDouble()); put("white", a.white.toDouble()); put("gamma", a.gamma.toDouble())
    put("curveS", a.curveS.toDouble()); put("curveM", a.curveM.toDouble()); put("curveH", a.curveH.toDouble())
    put("sharp", a.sharp.toDouble()); put("clarity", a.clarity.toDouble()); put("blur", a.blur.toDouble())
    put("vignette", a.vignette.toDouble()); put("grain", a.grain.toDouble())
}

fun adjFromJson(o: JSONObject) = Adj(
    brightness = o.optDouble("brightness", 0.0).toFloat(), contrast = o.optDouble("contrast", 1.0).toFloat(),
    exposure = o.optDouble("exposure", 0.0).toFloat(), saturation = o.optDouble("saturation", 1.0).toFloat(),
    temperature = o.optDouble("temperature", 0.0).toFloat(), rGain = o.optDouble("rGain", 1.0).toFloat(),
    gGain = o.optDouble("gGain", 1.0).toFloat(), bGain = o.optDouble("bGain", 1.0).toFloat(),
    filter = o.optInt("filter", 0).coerceIn(0, FILTERS.size - 1),
    shadows = o.optDouble("shadows", 0.0).toFloat(), highlights = o.optDouble("highlights", 0.0).toFloat(),
    black = o.optDouble("black", 0.0).toFloat(), white = o.optDouble("white", 1.0).toFloat(),
    gamma = o.optDouble("gamma", 1.0).toFloat(), curveS = o.optDouble("curveS", 0.0).toFloat(),
    curveM = o.optDouble("curveM", 0.0).toFloat(), curveH = o.optDouble("curveH", 0.0).toFloat(),
    sharp = o.optDouble("sharp", 0.0).toFloat(), clarity = o.optDouble("clarity", 0.0).toFloat(),
    blur = o.optDouble("blur", 0.0).toFloat(), vignette = o.optDouble("vignette", 0.0).toFloat(),
    grain = o.optDouble("grain", 0.0).toFloat()
)

fun layerToJson(l: Layer, imgFile: String?) = JSONObject().apply {
    put("kind", l.kind); put("name", l.name)
    put("cx", l.cx.toDouble()); put("cy", l.cy.toDouble()); put("scale", l.scale.toDouble()); put("rot", l.rot.toDouble())
    put("opacity", l.opacity.toDouble()); put("blend", l.blend); put("visible", l.visible)
    put("shadow", l.shadow.toDouble()); put("reflection", l.reflection)
    put("mask", l.mask); put("maskPos", l.maskPos.toDouble()); put("maskInv", l.maskInv)
    put("text", l.text); put("font", l.font); put("size", l.size.toDouble()); put("color", l.color)
    put("bold", l.bold); put("italic", l.italic); put("outline", l.outline)
    put("bgColor", l.bgColor); put("bgShape", l.bgShape); put("wFrac", l.wFrac.toDouble())
    if (imgFile != null) put("img", imgFile)
    val arr = JSONArray()
    for (s in l.strokes) {
        val o = JSONObject()
        o.put("c", s.color); o.put("w", s.width.toDouble()); o.put("e", s.erase)
        val pa = JSONArray()
        for (pt in s.pts) { pa.put(pt.first.toDouble()); pa.put(pt.second.toDouble()) }
        o.put("p", pa)
        arr.put(o)
    }
    put("strokes", arr)
}

fun saveProject(ctx: Context, uri: Uri, bmp: Bitmap, adj: Adj, geo: Geo, frame: Frame, layers: List<Layer>) {
    val os = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo escribir")
    ZipOutputStream(os).use { zos ->
        zos.putNextEntry(ZipEntry("base.png"))
        bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
        zos.closeEntry()
        val root = JSONObject()
        root.put("version", 1)
        root.put("adj", adjToJson(adj))
        root.put("geo", JSONObject().put("straighten", geo.straighten.toDouble())
            .put("persV", geo.persV.toDouble()).put("persH", geo.persH.toDouble()))
        root.put("frame", JSONObject().put("style", frame.style).put("thick", frame.thick.toDouble()).put("color", frame.color))
        val la = JSONArray()
        layers.forEachIndexed { i, l ->
            var name: String? = null
            l.bmp?.let {
                name = "layer_$i.png"
                zos.putNextEntry(ZipEntry(name!!))
                it.compress(Bitmap.CompressFormat.PNG, 100, zos)
                zos.closeEntry()
            }
            la.put(layerToJson(l, name))
        }
        root.put("layers", la)
        zos.putNextEntry(ZipEntry("project.json"))
        zos.write(root.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}

fun loadProject(ctx: Context, uri: Uri): Project? {
    return try {
        val files = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) { files[e.name] = z.readBytes(); e = z.nextEntry }
            }
        }
        val baseBytes = files["base.png"] ?: return null
        val base = BitmapFactory.decodeByteArray(baseBytes, 0, baseBytes.size) ?: return null
        val root = JSONObject(String(files["project.json"] ?: return null, Charsets.UTF_8))
        val adj = adjFromJson(root.getJSONObject("adj"))
        val go = root.getJSONObject("geo")
        val geo = Geo(go.optDouble("straighten", 0.0).toFloat(), go.optDouble("persV", 0.0).toFloat(), go.optDouble("persH", 0.0).toFloat())
        val fo = root.getJSONObject("frame")
        val frame = Frame(fo.optInt("style", 0), fo.optDouble("thick", 0.04).toFloat(), fo.optInt("color", -1))
        val la = root.getJSONArray("layers")
        val layers = ArrayList<Layer>()
        for (i in 0 until la.length()) {
            val o = la.getJSONObject(i)
            val img = o.optString("img", "")
            val lb = if (img.isNotEmpty()) files[img]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
            val strokes = ArrayList<BrushStroke>()
            val sa = o.optJSONArray("strokes")
            if (sa != null) for (k in 0 until sa.length()) {
                val so = sa.getJSONObject(k)
                val pa = so.getJSONArray("p")
                val pts = ArrayList<Pair<Float, Float>>()
                var j = 0
                while (j + 1 < pa.length()) { pts.add(Pair(pa.getDouble(j).toFloat(), pa.getDouble(j + 1).toFloat())); j += 2 }
                strokes.add(BrushStroke(pts, so.optInt("c", -1), so.optDouble("w", 0.02).toFloat(), so.optBoolean("e", false)))
            }
            layers.add(Layer(
                kind = o.optInt("kind", 0), name = o.optString("name", "Capa"),
                cx = o.optDouble("cx", 0.5).toFloat(), cy = o.optDouble("cy", 0.5).toFloat(),
                scale = o.optDouble("scale", 1.0).toFloat(), rot = o.optDouble("rot", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 1.0).toFloat(), blend = o.optInt("blend", 0),
                visible = o.optBoolean("visible", true), shadow = o.optDouble("shadow", 0.0).toFloat(),
                reflection = o.optBoolean("reflection", false), mask = o.optInt("mask", 0),
                maskPos = o.optDouble("maskPos", 0.5).toFloat(), maskInv = o.optBoolean("maskInv", false),
                text = o.optString("text", ""), font = o.optInt("font", 0), size = o.optDouble("size", 0.08).toFloat(),
                color = o.optInt("color", -1), bold = o.optBoolean("bold", true), italic = o.optBoolean("italic", false),
                outline = o.optBoolean("outline", false), bgColor = o.optInt("bgColor", 0), bgShape = o.optInt("bgShape", 0),
                bmp = lb, wFrac = o.optDouble("wFrac", 0.4).toFloat(), strokes = strokes
            ))
        }
        Project(base, adj, geo, frame, layers)
    } catch (e: Exception) { null }
}

// =====================================================================
//  PANELES UI
// =====================================================================
@Composable
fun RetouchPanel(
    mode: Int, onMode: (Int) -> Unit, size: Float, onSize: (Float) -> Unit,
    cloneReady: Boolean, onResetClone: () -> Unit,
    onSkin: (Float) -> Unit, onTeeth: () -> Unit, onRedEye: () -> Unit
) {
    var skin by remember { mutableFloatStateOf(0.6f) }
    Text("Herramientas sobre la foto", style = MaterialTheme.typography.labelLarge)
    ChipsRow(listOf("Borrar / Curar", "Clonar"), mode, onMode)
    SliderRow("Tamaño pincel", size, 0.01f..0.15f, onSize, {})
    if (mode == 0) {
        Text("Pinta sobre la mancha, imperfección u objeto y suelta. Rellena con el entorno: va bien con objetos pequeños sobre fondos sencillos. Para personas u objetos grandes hará falta IA generativa.")
    } else {
        Text(if (cloneReady) "Origen fijado. Pinta para copiar esa zona." else "1) Toca el punto de origen. 2) Pinta donde quieras copiarlo.")
        TextButton(onClick = onResetClone) { Text("Cambiar origen") }
    }
    Text("Retoque de rostro (rostro principal de la foto)", style = MaterialTheme.typography.labelLarge)
    SliderRow("Intensidad", skin, 0.1f..1f, { skin = it }, {})
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSkin(skin) }) { Text("Suavizar piel") }
        Button(onClick = onTeeth) { Text("Blanquear dientes") }
        Button(onClick = onRedEye) { Text("Ojos rojos") }
    }
}

@Composable
fun BackgroundPanel(
    hasMask: Boolean, onDetect: (Boolean) -> Unit, onRemove: () -> Unit, onColor: (Int) -> Unit,
    onPhoto: (Bitmap) -> Unit, onBlur: (Float) -> Unit, onSky: (Int, Bitmap?) -> Unit
) {
    val ctx = LocalContext.current
    var blurS by remember { mutableFloatStateOf(0.5f) }
    var skyIdx by remember { mutableIntStateOf(0) }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> loadBitmap(ctx, u)?.let { onPhoto(scaleDown(it, 2560)) } }
    }
    val skyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> loadBitmap(ctx, u)?.let { onSky(-1, scaleDown(it, 2560)) } }
    }
    Text("1) Detectar sujeto", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onDetect(false) }) { Text("Persona (sin conexión)") }
        Button(onClick = { onDetect(true) }) { Text("Persona u objeto") }
    }
    Text(if (hasMask) "✓ Sujeto detectado" else "Aún no hay sujeto detectado")
    Text("2) Fondo", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRemove) { Text("Quitar fondo (PNG)") }
        OutlinedButton(onClick = { bgPicker.launch("image/*") }) { Text("Reemplazar por foto") }
    }
    Text("Reemplazar por color")
    ColorRow(-2, false) { onColor(it) }
    SliderRow("Desenfoque", blurS, 0.1f..1f, { blurS = it }, {})
    Button(onClick = { onBlur(blurS) }) { Text("Desenfocar fondo") }
    Text("Cambiar cielo (detección automática)", style = MaterialTheme.typography.labelLarge)
    ChipsRow(listOf("Despejado", "Atardecer", "Noche", "Nublado"), skyIdx) { skyIdx = it }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSky(skyIdx, null) }) { Text("Aplicar cielo") }
        OutlinedButton(onClick = { skyPicker.launch("image/*") }) { Text("Cielo desde foto") }
    }
}

@Composable
fun ProjectPanel(onSave: (Uri) -> Unit, onOpen: (Uri) -> Unit, onBatch: (List<Uri>) -> Unit) {
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(onSave) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onOpen) }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) onBatch(uris) }
    Text("Proyecto editable", style = MaterialTheme.typography.labelLarge)
    Text("Guarda la foto con sus capas, ajustes y marco para seguir editando después.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { saver.launch("proyecto.efp") }) { Text("Guardar proyecto") }
        OutlinedButton(onClick = { opener.launch(arrayOf("*/*")) }) { Text("Abrir proyecto") }
    }
    Text("Edición por lotes", style = MaterialTheme.typography.labelLarge)
    Text("Aplica a varias fotos los ajustes de color, filtros, efectos, capas y marco actuales, y las guarda en JPG.")
    Button(onClick = { multi.launch("image/*") }) { Text("Elegir fotos y procesar") }
}
