package com.example.editorfoto

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.pow
import android.graphics.ColorMatrix as AColorMatrix

// ---------- Modelo ----------
data class Adj(
    val brightness: Float = 0f,   // -0.5..0.5
    val contrast: Float = 1f,     // 0.5..1.5
    val exposure: Float = 0f,     // -1..1 (EV)
    val saturation: Float = 1f,   // 0..2
    val temperature: Float = 0f,  // -1..1
    val filter: Int = 0
)

data class Snapshot(val bmp: Bitmap, val adj: Adj)
class FilterDef(val name: String, val m: FloatArray?)

val FILTERS = listOf(
    FilterDef("Original", null),
    FilterDef("Blanco y negro", floatArrayOf(
        .33f, .59f, .11f, 0f, 0f,
        .33f, .59f, .11f, 0f, 0f,
        .33f, .59f, .11f, 0f, 0f,
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
    FilterDef("Cine", floatArrayOf(
        1.2f, 0f, -.1f, 0f, 0f,
        0f, 1f, .1f, 0f, 0f,
        -.1f, .1f, 1.1f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f)),
    FilterDef("HDR", floatArrayOf(
        1.3f, -.15f, -.15f, 0f, -15f,
        -.15f, 1.3f, -.15f, 0f, -15f,
        -.15f, -.15f, 1.3f, 0f, -15f,
        0f, 0f, 0f, 1f, 0f))
)

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
        1f + tr, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f - tr, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)))
    FILTERS[a.filter].m?.let { m.postConcat(AColorMatrix(it)) }
    return m
}

// ---------- Operaciones de imagen ----------
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

fun render(b: Bitmap, adj: Adj, longSide: Int?, whiteBg: Boolean): Bitmap {
    val k = if (longSide == null) 1f else longSide.toFloat() / max(b.width, b.height)
    val w = (b.width * k).toInt().coerceAtLeast(1)
    val h = (b.height * k).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
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

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { EditorScreen() } } }
    }
}

@Composable
fun EditorScreen() {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var adj by remember { mutableStateOf(Adj()) }
    var saved by remember { mutableStateOf<Snapshot?>(null) }
    val undo = remember { mutableStateListOf<Snapshot>() }
    val redo = remember { mutableStateListOf<Snapshot>() }
    var showOriginal by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }

    fun commit() {
        val b = bmp ?: return
        saved?.let { undo.add(it) }
        redo.clear()
        saved = Snapshot(b, adj)
    }
    fun doUndo() {
        val b = bmp ?: return
        if (undo.isEmpty()) return
        redo.add(Snapshot(b, adj))
        val s = undo.removeAt(undo.lastIndex)
        bmp = s.bmp; adj = s.adj; saved = s
    }
    fun doRedo() {
        val b = bmp ?: return
        if (redo.isEmpty()) return
        undo.add(Snapshot(b, adj))
        val s = redo.removeAt(redo.lastIndex)
        bmp = s.bmp; adj = s.adj; saved = s
    }
    fun geometry(op: (Bitmap) -> Bitmap) {
        val b = bmp ?: return
        bmp = op(b)
        commit()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val b = loadBitmap(ctx, it)
            if (b == null) Toast.makeText(ctx, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
            else {
                bmp = b; adj = Adj(); undo.clear(); redo.clear(); saved = Snapshot(b, adj)
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Barra superior
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
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) {
                Text("Pulsa \"Abrir\" para elegir una foto")
            } else {
                val img = remember(b) { b.asImageBitmap() }
                Image(
                    bitmap = img, contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = if (showOriginal) null else ColorFilter.colorMatrix(ColorMatrix(buildMatrix(adj).array))
                )
            }
        }

        // Pestañas
        TabRow(selectedTabIndex = tab) {
            listOf("Recortar", "Ajustes", "Filtros").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 230.dp).padding(8.dp)) {
            when (tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1:1" to (1 to 1), "4:5" to (4 to 5), "9:16" to (9 to 16), "16:9" to (16 to 9)).forEach { (n, r) ->
                            OutlinedButton(onClick = { geometry { cropAspect(it, r.first, r.second) } }, enabled = bmp != null) { Text(n) }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { geometry { transform(it) { postRotate(90f) } } }, enabled = bmp != null) { Text("Girar 90°") }
                        OutlinedButton(onClick = { geometry { transform(it) { preScale(-1f, 1f) } } }, enabled = bmp != null) { Text("Voltear H") }
                        OutlinedButton(onClick = { geometry { transform(it) { preScale(1f, -1f) } } }, enabled = bmp != null) { Text("Voltear V") }
                    }
                }
                1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    SliderRow("Brillo", adj.brightness, -0.5f..0.5f, { adj = adj.copy(brightness = it) }, ::commit)
                    SliderRow("Contraste", adj.contrast, 0.5f..1.5f, { adj = adj.copy(contrast = it) }, ::commit)
                    SliderRow("Exposición", adj.exposure, -1f..1f, { adj = adj.copy(exposure = it) }, ::commit)
                    SliderRow("Saturación", adj.saturation, 0f..2f, { adj = adj.copy(saturation = it) }, ::commit)
                    SliderRow("Temperatura", adj.temperature, -1f..1f, { adj = adj.copy(temperature = it) }, ::commit)
                }
                else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILTERS.forEachIndexed { i, f ->
                        FilterChip(selected = adj.filter == i, onClick = { adj = adj.copy(filter = i); commit() },
                            label = { Text(f.name) }, enabled = bmp != null)
                    }
                }
            }
        }
    }

    if (showExport && bmp != null) {
        ExportDialog(
            onDismiss = { showExport = false },
            onExport = { fmt, quality, side, share ->
                val src = bmp!!
                val out = render(src, adj, side, whiteBg = fmt == 0)
                val res = saveToGallery(ctx, out, fmt, quality)
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
                showExport = false
            }
        )
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(100.dp))
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
