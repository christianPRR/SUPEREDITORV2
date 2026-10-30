package com.example.editorfoto

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as ACanvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val TAB_CAPAS = 5

// =====================================================================
//  MODELO
// =====================================================================
data class BrushStroke(val pts: List<Pair<Float, Float>>, val color: Int, val width: Float, val erase: Boolean)
data class BrushCfg(val color: Int = 0xFFE53935.toInt(), val size: Float = 0.02f, val erase: Boolean = false)
data class Frame(val style: Int = 0, val thick: Float = 0.04f, val color: Int = -1)

data class Layer(
    val kind: Int = 0,               // 0 texto/emoji, 1 imagen, 2 dibujo
    val name: String = "Capa",
    val cx: Float = 0.5f, val cy: Float = 0.5f, val scale: Float = 1f, val rot: Float = 0f,
    val opacity: Float = 1f, val blend: Int = 0, val visible: Boolean = true,
    val shadow: Float = 0f, val reflection: Boolean = false,
    val mask: Int = 0, val maskPos: Float = 0.5f, val maskInv: Boolean = false,
    // texto
    val text: String = "", val font: Int = 0, val size: Float = 0.08f, val color: Int = -1,
    val bold: Boolean = true, val italic: Boolean = false, val outline: Boolean = false,
    val bgColor: Int = 0, val bgShape: Int = 0,
    // imagen
    val bmp: Bitmap? = null, val wFrac: Float = 0.4f,
    // dibujo
    val strokes: List<BrushStroke> = emptyList()
)

val FONTS = listOf(
    "Sans" to "sans-serif", "Serif" to "serif", "Mono" to "monospace",
    "Condensada" to "sans-serif-condensed", "Light" to "sans-serif-light",
    "Medium" to "sans-serif-medium", "Black" to "sans-serif-black",
    "Casual" to "casual", "Manuscrita" to "cursive", "Máquina" to "serif-monospace"
)

val BLENDS = listOf(
    "Normal" to BlendMode.SRC_OVER, "Multiplicar" to BlendMode.MULTIPLY, "Pantalla" to BlendMode.SCREEN,
    "Superponer" to BlendMode.OVERLAY, "Oscurecer" to BlendMode.DARKEN, "Aclarar" to BlendMode.LIGHTEN,
    "Sobreexponer" to BlendMode.COLOR_DODGE, "Subexponer" to BlendMode.COLOR_BURN,
    "Luz fuerte" to BlendMode.HARD_LIGHT, "Luz suave" to BlendMode.SOFT_LIGHT,
    "Diferencia" to BlendMode.DIFFERENCE, "Exclusión" to BlendMode.EXCLUSION
)

val FRAME_STYLES = listOf("Ninguno", "Borde", "Polaroid", "Doble", "Redondeado")

val PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A
).map { it.toInt() }

val EMOJIS = listOf(
    "😀", "😍", "😎", "🥳", "😂", "🤩", "😭", "😡", "👍", "🙏",
    "💪", "🎉", "🎂", "🎁", "❤️", "🔥", "⭐", "✨", "🌈", "☀️",
    "🌙", "⚡", "🌸", "🌹", "🍀", "🐶", "🐱", "🦋", "🚀", "✈️",
    "🚗", "🏠", "📷", "🎵", "💎", "👑", "🏆", "⚽", "🍕", "🍔",
    "🍦", "☕", "🎈", "💯", "✅", "❌", "⚠️", "➡️"
)

val COLLAGE_NAMES = listOf("2 lado a lado", "2 apiladas", "2x2", "3 columnas", "1 grande + 2")
val COLLAGE_CELLS = listOf(
    listOf(floatArrayOf(0f, 0f, .5f, 1f), floatArrayOf(.5f, 0f, 1f, 1f)),
    listOf(floatArrayOf(0f, 0f, 1f, .5f), floatArrayOf(0f, .5f, 1f, 1f)),
    listOf(floatArrayOf(0f, 0f, .5f, .5f), floatArrayOf(.5f, 0f, 1f, .5f),
        floatArrayOf(0f, .5f, .5f, 1f), floatArrayOf(.5f, .5f, 1f, 1f)),
    listOf(floatArrayOf(0f, 0f, .333f, 1f), floatArrayOf(.333f, 0f, .667f, 1f), floatArrayOf(.667f, 0f, 1f, 1f)),
    listOf(floatArrayOf(0f, 0f, 1f, .6f), floatArrayOf(0f, .6f, .5f, 1f), floatArrayOf(.5f, .6f, 1f, 1f))
)
val CANVAS_SIZES = listOf(1080 to 1080, 1080 to 1350, 1080 to 1920, 1920 to 1080)
val CANVAS_NAMES = listOf("1:1", "4:5", "9:16", "16:9")

// =====================================================================
//  RENDER DE CAPAS
// =====================================================================
fun contrastOf(c: Int): Int {
    val lum = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
    return if (lum > 140) android.graphics.Color.BLACK else android.graphics.Color.WHITE
}

fun typefaceOf(l: Layer): Typeface {
    val style = when {
        l.bold && l.italic -> Typeface.BOLD_ITALIC
        l.bold -> Typeface.BOLD
        l.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(FONTS[l.font.coerceIn(0, FONTS.size - 1)].second, style)
}

fun textSprite(l: Layer, w: Int): Bitmap? {
    if (l.text.isEmpty()) return null
    val ts = (l.size * w).coerceIn(8f, w.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ts; typeface = typefaceOf(l); color = l.color; textAlign = Paint.Align.CENTER
    }
    val lines = l.text.split("\n")
    val fm = paint.fontMetrics
    val lh = fm.descent - fm.ascent
    var tw = 0f
    for (ln in lines) tw = max(tw, paint.measureText(ln))
    val th = lh * lines.size
    val pad = ts * 0.25f + (if (l.outline) ts * 0.05f else 0f)
    var sw = tw + 2 * pad
    var sh = th + 2 * pad
    if (l.bgColor != 0 && l.bgShape == 2) { val m = max(sw, sh); sw = m; sh = m }
    val bw = ceil(sw).toInt().coerceIn(1, 6000)
    val bh = ceil(sh).toInt().coerceIn(1, 6000)
    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    if (l.bgColor != 0) {
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.bgColor }
        val rr = if (l.bgShape == 0) ts * 0.15f else min(sw, sh) / 2f
        c.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), rr, rr, bp)
    }
    val startY = (bh - th) / 2f - fm.ascent
    for ((i, ln) in lines.withIndex()) {
        val y = startY + i * lh
        if (l.outline) {
            val op = Paint(paint).apply {
                style = Paint.Style.STROKE; strokeWidth = ts * 0.1f; color = contrastOf(l.color); strokeJoin = Paint.Join.ROUND
            }
            c.drawText(ln, bw / 2f, y, op)
        }
        c.drawText(ln, bw / 2f, y, paint)
    }
    return bmp
}

fun imageSprite(l: Layer, w: Int): Bitmap? {
    val b = l.bmp ?: return null
    val tw = (l.wFrac * w).toInt().coerceIn(1, 4096)
    val th = (b.height * tw / b.width.toFloat()).toInt().coerceIn(1, 6000)
    return Bitmap.createScaledBitmap(b, tw, th, true).copy(Bitmap.Config.ARGB_8888, true)
}

fun drawSprite(l: Layer, w: Int, h: Int): Bitmap {
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bm)
    for (s in l.strokes) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = max(1f, s.width * w)
            if (s.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) else color = s.color
        }
        if (s.pts.isEmpty()) continue
        if (s.pts.size == 1) {
            c.drawPoint(s.pts[0].first * w, s.pts[0].second * h, p)
        } else {
            val path = Path()
            path.moveTo(s.pts[0].first * w, s.pts[0].second * h)
            for (i in 1 until s.pts.size) path.lineTo(s.pts[i].first * w, s.pts[i].second * h)
            c.drawPath(path, p)
        }
    }
    return bm
}

fun applyMask(s: Bitmap, l: Layer) {
    val w = s.width.toFloat()
    val h = s.height.toFloat()
    val opaque = -16777216
    val clear = 0
    val c1 = if (l.maskInv) clear else opaque
    val c2 = if (l.maskInv) opaque else clear
    val pos = l.maskPos
    val p1 = (pos - 0.15f).coerceIn(0f, 1f)
    val p2 = (pos + 0.15f).coerceIn(0f, 1f)
    val sh: Shader = when (l.mask) {
        1 -> LinearGradient(0f, 0f, w, 0f, intArrayOf(c1, c1, c2, c2), floatArrayOf(0f, p1, p2, 1f), Shader.TileMode.CLAMP)
        2 -> LinearGradient(0f, 0f, 0f, h, intArrayOf(c1, c1, c2, c2), floatArrayOf(0f, p1, p2, 1f), Shader.TileMode.CLAMP)
        else -> {
            val r1 = (pos * 0.7f).coerceIn(0f, 0.9f)
            RadialGradient(w / 2f, h / 2f, max(w, h) / 2f, intArrayOf(c1, c1, c2),
                floatArrayOf(0f, r1, min(r1 + 0.3f, 1f)), Shader.TileMode.CLAMP)
        }
    }
    val p = Paint().apply { shader = sh; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    ACanvas(s).drawRect(0f, 0f, w, h, p)
}

fun spriteOf(l: Layer, w: Int, h: Int): Bitmap? {
    val s = when (l.kind) {
        0 -> textSprite(l, w)
        1 -> imageSprite(l, w)
        else -> drawSprite(l, w, h)
    } ?: return null
    if (l.mask != 0) applyMask(s, l)
    return s
}

fun reflectionOf(s: Bitmap): Bitmap {
    val m = Matrix().apply { setScale(1f, -1f) }
    val f = Bitmap.createBitmap(s, 0, 0, s.width, s.height, m, true).copy(Bitmap.Config.ARGB_8888, true)
    val p = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(0f, 0f, 0f, f.height.toFloat(), 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }
    ACanvas(f).drawRect(0f, 0f, f.width.toFloat(), f.height.toFloat(), p)
    return f
}

fun composeLayer(c: ACanvas, l: Layer, w: Int, h: Int) {
    val s = spriteOf(l, w, h) ?: return
    val cxp = l.cx * w
    val cyp = l.cy * h
    val m = Matrix()
    m.setTranslate(cxp - s.width / 2f, cyp - s.height / 2f)
    m.postRotate(l.rot, cxp, cyp)
    m.postScale(l.scale, l.scale, cxp, cyp)
    val alpha = (l.opacity * 255f).toInt().coerceIn(0, 255)
    if (l.shadow > 0f) {
        val r = max(1f, l.shadow * 0.02f * w)
        val off = IntArray(2)
        val sh = s.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) }, off)
        val mm = Matrix(m)
        mm.preTranslate(off[0] + r * 0.6f, off[1] + r * 0.6f)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.argb((l.opacity * 180f).toInt().coerceIn(0, 255), 0, 0, 0) }
        c.drawBitmap(sh, mm, sp)
    }
    if (l.reflection) {
        val rf = reflectionOf(s)
        val mm = Matrix(m)
        mm.preTranslate(0f, s.height.toFloat())
        c.drawBitmap(rf, mm, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
    }
    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.alpha = alpha
        blendMode = BLENDS[l.blend.coerceIn(0, BLENDS.size - 1)].second
    }
    c.drawBitmap(s, m, p)
}

fun applyFrame(b: Bitmap, f: Frame): Bitmap {
    if (f.style == 0) return b
    val t = (f.thick * b.width).toInt().coerceAtLeast(2)
    val bottom = if (f.style == 2) (t * 3.2f).toInt() else t
    val out = Bitmap.createBitmap(b.width + 2 * t, b.height + t + bottom, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    c.drawColor(f.color)
    if (f.style == 4) {
        val path = Path()
        path.addRoundRect(RectF(t.toFloat(), t.toFloat(), (t + b.width).toFloat(), (t + b.height).toFloat()), t * 1.2f, t * 1.2f, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        c.drawBitmap(b, t.toFloat(), t.toFloat(), null)
        c.restore()
    } else {
        c.drawBitmap(b, t.toFloat(), t.toFloat(), null)
    }
    if (f.style == 3) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(2f, t * 0.1f); color = contrastOf(f.color)
        }
        val i = t * 0.4f
        c.drawRect(i, i, out.width - i, out.height - i, p)
    }
    return out
}

/** Foto base (con ajustes de color) + capas + marco. */
fun composeAll(base: Bitmap, adj: Adj, layers: List<Layer>, frame: Frame, longSide: Int?, whiteBg: Boolean = false): Bitmap {
    val b = render(base, adj, longSide, whiteBg)
    if (layers.isNotEmpty()) {
        val c = ACanvas(b)
        for (l in layers) if (l.visible) composeLayer(c, l, b.width, b.height)
    }
    return applyFrame(b, frame)
}

fun mergeDown(layers: List<Layer>, i: Int, w: Int, h: Int): List<Layer> {
    if (i <= 0 || i >= layers.size) return layers
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bm)
    if (layers[i - 1].visible) composeLayer(c, layers[i - 1], w, h)
    if (layers[i].visible) composeLayer(c, layers[i], w, h)
    val merged = Layer(kind = 1, name = "Combinada", bmp = bm, wFrac = 1f)
    val out = layers.toMutableList()
    out[i - 1] = merged
    out.removeAt(i)
    return out
}

fun buildCollage(imgs: List<Bitmap>, layout: Int, w: Int, h: Int, gap: Float, bg: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(bg)
    val c = ACanvas(out)
    val g = gap * w / 2f
    val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    COLLAGE_CELLS[layout].forEachIndexed { i, cell ->
        val r = RectF(cell[0] * w + g, cell[1] * h + g, cell[2] * w - g, cell[3] * h - g)
        val b = imgs.getOrNull(i) ?: return@forEachIndexed
        val sc = max(r.width() / b.width, r.height() / b.height)
        val sw = r.width() / sc
        val sh = r.height() / sc
        val sx = (b.width - sw) / 2f
        val sy = (b.height - sh) / 2f
        c.drawBitmap(b, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), r, p)
    }
    return out
}

// =====================================================================
//  UI
// =====================================================================
@Composable
fun ColorRow(selected: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (allowNone) TextButton(onClick = { onPick(0) }) { Text(if (selected == 0) "● Sin" else "Sin") }
        PALETTE.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Color(0xFF2196F3) else Color.Gray, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp))
        Switch(checked = v, onCheckedChange = onChange)
    }
}

@Composable
fun ChipsRow(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, n -> FilterChip(selected = selected == i, onClick = { onPick(i) }, label = { Text(n) }) }
    }
}

@Composable
fun LayersPanel(
    layers: SnapshotStateList<Layer>, sel: Int, onSel: (Int) -> Unit,
    brushMode: Boolean, onBrushMode: (Boolean) -> Unit,
    brush: BrushCfg, onBrush: (BrushCfg) -> Unit,
    baseW: Int, baseH: Int, onCommit: () -> Unit, onFlatten: () -> Unit
) {
    val ctx = LocalContext.current
    var dlg by remember { mutableIntStateOf(0) }   // 1 nuevo texto, 2 editar texto, 3 emojis
    var draft by remember { mutableStateOf("") }
    val l = layers.getOrNull(sel)

    fun add(n: Layer) { layers.add(n); onSel(layers.lastIndex); onCommit() }
    fun upd(f: (Layer) -> Layer) { if (sel in layers.indices) layers[sel] = f(layers[sel]) }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val b = loadBitmap(ctx, it)
            if (b == null) Toast.makeText(ctx, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
            else add(Layer(kind = 1, name = "Imagen", bmp = scaleDown(b, 1500), wFrac = 0.4f))
        }
    }

    // ---- Añadir ----
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { draft = ""; dlg = 1 }) { Text("+ Texto") }
        OutlinedButton(onClick = { dlg = 3 }) { Text("+ Emoji") }
        OutlinedButton(onClick = { imgPicker.launch("image/*") }) { Text("+ Imagen") }
        OutlinedButton(onClick = {
            add(Layer(kind = 0, name = "Marca de agua", text = "© Mi marca", size = 0.045f, cx = 0.8f, cy = 0.94f,
                opacity = 0.55f, shadow = 0.3f))
        }) { Text("+ Marca de agua") }
        OutlinedButton(onClick = {
            add(Layer(kind = 0, name = "Logo", text = "LOGO", size = 0.09f, font = 6, bgColor = 0xFF1E88E5.toInt(), bgShape = 2))
        }) { Text("+ Logo") }
        OutlinedButton(onClick = {
            add(Layer(kind = 0, name = "Banner", text = "TU TÍTULO AQUÍ", size = 0.07f, cy = 0.85f, bgColor = 0xFFE53935.toInt(), bgShape = 0))
        }) { Text("+ Banner") }
        Button(onClick = {
            if (layers.getOrNull(sel)?.kind != 2) { layers.add(Layer(kind = 2, name = "Dibujo")); onSel(layers.lastIndex) }
            onBrushMode(true)
        }) { Text("Pincel") }
    }

    if (brushMode) {
        Text("Pincel activo: dibuja con el dedo sobre la foto", style = MaterialTheme.typography.labelLarge)
        ColorRow(brush.color, false) { onBrush(brush.copy(color = it, erase = false)) }
        SliderRow("Tamaño", brush.size, 0.003f..0.08f, { onBrush(brush.copy(size = it)) }, {})
        SwitchRow("Borrador", brush.erase) { onBrush(brush.copy(erase = it)) }
        Button(onClick = { onBrushMode(false) }) { Text("Listo") }
    } else {
        if (layers.isEmpty()) {
            Text("Sin capas. Añade texto, emoji, imagen o dibujo. Con la capa seleccionada, arrastra sobre la foto para moverla y usa dos dedos para escalar y girar.")
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                layers.forEachIndexed { i, ly ->
                    FilterChip(selected = i == sel, onClick = { onSel(i) },
                        label = { Text((if (!ly.visible) "✕ " else "") + "${i + 1} ${ly.name}") })
                }
            }
        }
        if (l != null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = {
                    if (sel < layers.lastIndex) {
                        val a = layers[sel]; layers[sel] = layers[sel + 1]; layers[sel + 1] = a
                        onSel(sel + 1); onCommit()
                    }
                }) { Text("↑ Subir") }
                TextButton(onClick = {
                    if (sel > 0) {
                        val a = layers[sel]; layers[sel] = layers[sel - 1]; layers[sel - 1] = a
                        onSel(sel - 1); onCommit()
                    }
                }) { Text("↓ Bajar") }
                TextButton(onClick = { upd { it.copy(visible = !it.visible) }; onCommit() }) { Text(if (l.visible) "Ocultar" else "Mostrar") }
                TextButton(onClick = { add(l.copy(name = l.name + " copia", cx = l.cx + 0.03f, cy = l.cy + 0.03f)) }) { Text("Duplicar") }
                TextButton(onClick = { layers.removeAt(sel); onSel(layers.lastIndex); onCommit() }) { Text("Eliminar") }
                TextButton(enabled = sel > 0, onClick = {
                    val k = min(1f, 1600f / max(baseW, baseH))
                    val nl = mergeDown(layers.toList(), sel, max(1, (baseW * k).toInt()), max(1, (baseH * k).toInt()))
                    layers.clear(); layers.addAll(nl); onSel(sel - 1); onCommit()
                }) { Text("Combinar ↓") }
            }
        }
        if (layers.isNotEmpty()) TextButton(onClick = onFlatten) { Text("Aplanar todo en la foto") }

        if (l != null) {
            SliderRow("Opacidad", l.opacity, 0f..1f, { v -> upd { it.copy(opacity = v) } }, onCommit)
            SliderRow("Escala", l.scale, 0.1f..4f, { v -> upd { it.copy(scale = v) } }, onCommit)
            SliderRow("Rotación", l.rot, -180f..180f, { v -> upd { it.copy(rot = v) } }, onCommit)
            SliderRow("Sombra", l.shadow, 0f..1f, { v -> upd { it.copy(shadow = v) } }, onCommit)
            SwitchRow("Reflejo", l.reflection) { v -> upd { it.copy(reflection = v) }; onCommit() }
            Text("Modo de fusión", style = MaterialTheme.typography.labelLarge)
            ChipsRow(BLENDS.map { it.first }, l.blend) { i -> upd { it.copy(blend = i) }; onCommit() }
            Text("Máscara de capa", style = MaterialTheme.typography.labelLarge)
            ChipsRow(listOf("Sin máscara", "Horizontal", "Vertical", "Radial"), l.mask) { i -> upd { it.copy(mask = i) }; onCommit() }
            if (l.mask != 0) {
                SliderRow("Posición", l.maskPos, 0.05f..0.95f, { v -> upd { it.copy(maskPos = v) } }, onCommit)
                SwitchRow("Invertir", l.maskInv) { v -> upd { it.copy(maskInv = v) }; onCommit() }
            }
            if (l.kind == 0) {
                Button(onClick = { draft = l.text; dlg = 2 }) { Text("Editar texto") }
                Text("Tipografía", style = MaterialTheme.typography.labelLarge)
                ChipsRow(FONTS.map { it.first }, l.font) { i -> upd { it.copy(font = i) }; onCommit() }
                SliderRow("Tamaño", l.size, 0.02f..0.3f, { v -> upd { it.copy(size = v) } }, onCommit)
                Text("Color del texto", style = MaterialTheme.typography.labelLarge)
                ColorRow(l.color, false) { c -> upd { it.copy(color = c) }; onCommit() }
                SwitchRow("Negrita", l.bold) { v -> upd { it.copy(bold = v) }; onCommit() }
                SwitchRow("Cursiva", l.italic) { v -> upd { it.copy(italic = v) }; onCommit() }
                SwitchRow("Contorno", l.outline) { v -> upd { it.copy(outline = v) }; onCommit() }
                Text("Fondo del texto", style = MaterialTheme.typography.labelLarge)
                ColorRow(l.bgColor, true) { c -> upd { it.copy(bgColor = c) }; onCommit() }
                if (l.bgColor != 0) ChipsRow(listOf("Rectángulo", "Píldora", "Círculo"), l.bgShape) { i -> upd { it.copy(bgShape = i) }; onCommit() }
            }
            if (l.kind == 1) {
                SliderRow("Ancho", l.wFrac, 0.05f..1.5f, { v -> upd { it.copy(wFrac = v) } }, onCommit)
            }
        }
    }

    if (dlg == 1 || dlg == 2) {
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(if (dlg == 1) "Nuevo texto" else "Editar texto") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Texto (admite varias líneas)") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (dlg == 1) { if (draft.isNotBlank()) add(Layer(kind = 0, name = "Texto", text = draft)) }
                    else { upd { it.copy(text = draft) }; onCommit() }
                    dlg = 0
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { dlg = 0 }) { Text("Cancelar") } }
        )
    }
    if (dlg == 3) {
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text("Emojis y stickers") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EMOJIS.chunked(5).forEach { rowItems ->
                        Row {
                            rowItems.forEach { e ->
                                TextButton(onClick = { add(Layer(kind = 0, name = "Emoji", text = e, size = 0.18f)); dlg = 0 },
                                    contentPadding = PaddingValues(4.dp)) { Text(e, fontSize = 26.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dlg = 0 }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun DesignPanel(frame: Frame, onFrame: (Frame) -> Unit, onCommit: () -> Unit, onNewBase: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var asp by remember { mutableIntStateOf(0) }
    var bgc by remember { mutableIntStateOf(-1) }
    var lay by remember { mutableIntStateOf(0) }
    var gap by remember { mutableFloatStateOf(0.02f) }

    val multi = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            val need = COLLAGE_CELLS[lay].size
            val imgs = uris.take(need).mapNotNull { u -> loadBitmap(ctx, u)?.let { scaleDown(it, 1400) } }
            if (imgs.isEmpty()) Toast.makeText(ctx, "No se pudieron abrir las fotos", Toast.LENGTH_SHORT).show()
            else {
                val (w, h) = CANVAS_SIZES[asp]
                onNewBase(buildCollage(imgs, lay, w, h, gap, bgc))
            }
        }
    }

    Text("Marco y borde", style = MaterialTheme.typography.labelLarge)
    ChipsRow(FRAME_STYLES, frame.style) { i -> onFrame(frame.copy(style = i)); onCommit() }
    if (frame.style != 0) {
        SliderRow("Grosor", frame.thick, 0.01f..0.12f, { onFrame(frame.copy(thick = it)) }, onCommit)
        ColorRow(frame.color, false) { c -> onFrame(frame.copy(color = c)); onCommit() }
    }
    Text("Lienzo nuevo (banners y afiches)", style = MaterialTheme.typography.labelLarge)
    ChipsRow(CANVAS_NAMES, asp) { asp = it }
    ColorRow(bgc, false) { bgc = it }
    Button(onClick = {
        val (w, h) = CANVAS_SIZES[asp]
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(bgc)
        onNewBase(b)
    }) { Text("Crear lienzo") }
    Text("Collage (usa el formato y color de arriba)", style = MaterialTheme.typography.labelLarge)
    ChipsRow(COLLAGE_NAMES, lay) { lay = it }
    SliderRow("Espacio", gap, 0f..0.06f, { gap = it }, {})
    Button(onClick = { multi.launch("image/*") }) { Text("Elegir fotos y crear collage") }
}
