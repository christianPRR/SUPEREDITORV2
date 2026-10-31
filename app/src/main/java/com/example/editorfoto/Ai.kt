package com.example.editorfoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as ACanvas
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

val AI_MODELS = listOf(
    "Económico" to "gemini-2.5-flash-image",
    "Equilibrado" to "gemini-3.1-flash-image-preview",
    "Calidad" to "gemini-3-pro-image-preview"
)

const val AI_KEEP = " Keep everything else unchanged. Do not add text or watermarks. Return only the final edited image."

data class AiRequest(val prompt: String, val expandIdx: Int = -1)

fun padToAspect(b: Bitmap, idx: Int): Bitmap {
    val (aw, ah) = CANVAS_SIZES[idx]
    val target = aw.toFloat() / ah
    val cur = b.width.toFloat() / b.height
    val nw: Int
    val nh: Int
    if (target > cur) { nh = b.height; nw = (nh * target).toInt() } else { nw = b.width; nh = (nw / target).toInt() }
    val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    c.drawColor(0xFF808080.toInt())
    c.drawBitmap(b, (nw - b.width) / 2f, (nh - b.height) / 2f, null)
    return out
}

fun aiErrorMessage(text: String, code: Int): String {
    val base = try {
        JSONObject(text).getJSONObject("error").getString("message")
    } catch (e: Exception) { "HTTP $code" }
    val hint = when (code) {
        400 -> " (revisa la clave API y que el modelo exista)"
        403 -> " (la clave no tiene permiso o falta activar facturación)"
        429 -> " (límite de uso alcanzado, espera un momento)"
        else -> ""
    }
    return base + hint
}

/** Envía la foto + instrucción a Gemini y devuelve la imagen editada. */
suspend fun callGemini(ctx: Context, src: Bitmap, prompt: String, expandIdx: Int): Bitmap = withContext(Dispatchers.IO) {
    val prefs = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
    val key = (prefs.getString("key", "") ?: "").trim()
    if (key.isEmpty()) throw IllegalStateException("Falta la clave API (pestaña IA)")
    val model = (prefs.getString("model", AI_MODELS[1].second) ?: AI_MODELS[1].second).trim()
    val size = prefs.getString("size", "1K") ?: "1K"

    var input = scaleDown(src, 1536)
    if (expandIdx >= 0) input = padToAspect(input, expandIdx)
    val alpha = input.hasAlpha()
    val baos = ByteArrayOutputStream()
    input.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, baos)
    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

    val parts = JSONArray()
        .put(JSONObject().put("text", prompt))
        .put(JSONObject().put("inline_data", JSONObject()
            .put("mime_type", if (alpha) "image/png" else "image/jpeg").put("data", b64)))
    val gen = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
    if (size != "1K" && !model.contains("2.5")) gen.put("imageConfig", JSONObject().put("imageSize", size))
    val body = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        .put("generationConfig", gen)

    val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        .openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 30000
    conn.readTimeout = 180000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("x-goog-api-key", key)
    val text: String
    val code: Int
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    } finally {
        conn.disconnect()
    }
    if (code !in 200..299) throw IllegalStateException(aiErrorMessage(text, code))

    val root = JSONObject(text)
    val partsOut = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    var out: Bitmap? = null
    val msg = StringBuilder()
    if (partsOut != null) {
        for (i in 0 until partsOut.length()) {
            val p = partsOut.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
            if (inl != null) {
                val bytes = Base64.decode(inl.getString("data"), Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bm != null) out = bm
            } else {
                val t = p.optString("text", "")
                if (t.isNotEmpty()) msg.append(t)
            }
        }
    }
    val result = out
    if (result == null) {
        val block = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        throw IllegalStateException(
            when {
                block.isNotEmpty() -> "La solicitud fue bloqueada por la política del modelo ($block)"
                msg.isNotEmpty() -> "El modelo no devolvió imagen: $msg"
                else -> "El modelo no devolvió ninguna imagen. Prueba otra instrucción."
            }
        )
    }
    result
}

@Composable
fun AiPanel(enabled: Boolean, onRun: (AiRequest) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ai", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", AI_MODELS[1].second) ?: AI_MODELS[1].second) }
    var size by remember { mutableStateOf(prefs.getString("size", "1K") ?: "1K") }
    var instr by remember { mutableStateOf("") }
    var expandAsp by remember { mutableIntStateOf(0) }
    var expr by remember { mutableIntStateOf(0) }
    val sizes = listOf("1K", "2K", "4K")
    val exprs = listOf(
        "Sonrisa suave" to "a gentle natural smile",
        "Sonrisa amplia" to "a big happy smile",
        "Serio" to "a calm, serious, neutral expression",
        "Sorprendido" to "a surprised expression"
    )
    val templates = listOf(
        "Quitar persona u objeto" to "Elimina [describe qué quitar] y rellena la zona de forma natural.",
        "Añadir objeto" to "Añade [qué objeto] en [dónde] de forma realista.",
        "Cambiar ropa" to "Cambia la ropa de la persona por [descripción de la ropa].",
        "Cambiar color" to "Cambia el color de [objeto] a [color].",
        "Cambiar escenario" to "Cambia el fondo por [descripción del lugar], manteniendo a la persona igual."
    )

    fun run(p: String, expand: Int = -1) {
        if (key.isBlank()) {
            Toast.makeText(ctx, "Primero pega tu clave API de Gemini arriba", Toast.LENGTH_LONG).show()
            return
        }
        onRun(AiRequest(p + AI_KEEP, expand))
    }

    Text("Conexión con Gemini", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = key,
        onValueChange = { key = it; prefs.edit().putString("key", it).apply() },
        label = { Text("Clave API (Google AI Studio)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Text("Se guarda solo en este celular. Se obtiene en aistudio.google.com/apikey y los modelos de imagen requieren facturación activa en tu cuenta de Google. Cada edición envía la foto a Google y tiene un costo aproximado de US$0.04 a US$0.15 (verifica los precios actuales).")
    ChipsRow(AI_MODELS.map { it.first }, AI_MODELS.indexOfFirst { it.second == model }) { i ->
        model = AI_MODELS[i].second; prefs.edit().putString("model", model).apply()
    }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it; prefs.edit().putString("model", it).apply() },
        label = { Text("Modelo (editable si Google cambia los nombres)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text("Resolución de salida (2K y 4K cuestan más y no aplican al modelo Económico)")
    ChipsRow(sizes, sizes.indexOf(size).coerceAtLeast(0)) { i -> size = sizes[i]; prefs.edit().putString("size", size).apply() }

    Text("Edición generativa", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        templates.forEach { (n, t) -> AssistChip(onClick = { instr = t }, label = { Text(n) }) }
    }
    OutlinedTextField(
        value = instr, onValueChange = { instr = it },
        label = { Text("Instrucción (en español)") },
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = enabled && instr.isNotBlank(), onClick = {
        run("Edit this photo following this instruction (written in Spanish): $instr.")
    }) { Text("Aplicar instrucción") }

    Text("Expandir foto", style = MaterialTheme.typography.labelLarge)
    ChipsRow(CANVAS_NAMES, expandAsp) { expandAsp = it }
    Button(enabled = enabled, onClick = {
        run("This image has flat gray empty areas around it. Extend the scene naturally to fill those areas completely (outpainting), keeping the original central content unchanged.", expandAsp)
    }) { Text("Expandir al formato elegido") }

    Text("Mejoramiento", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { run("Enhance this photo: improve sharpness, detail, dynamic range and color naturally and remove compression artifacts, without changing the content.") }) { Text("Mejorar calidad") }
        OutlinedButton(enabled = enabled, onClick = { run("Remove noise and grain from this photo while preserving fine detail and texture.") }) { Text("Reducir ruido") }
        OutlinedButton(enabled = enabled, onClick = { run("Upscale this image to a higher resolution, adding realistic fine detail and sharpness without changing the content.") }) { Text("Aumentar resolución") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { run("Restore this old photograph: remove scratches, dust, stains, creases and fading, repair damaged areas and improve contrast and sharpness while staying realistic and faithful to the original.") }) { Text("Restaurar foto antigua") }
        OutlinedButton(enabled = enabled, onClick = { run("Colorize this black and white photograph with natural, historically plausible colors.") }) { Text("Colorear B/N") }
        OutlinedButton(enabled = enabled, onClick = { run("Enhance the faces in this photo: make them sharper and clearer and restore facial detail, keeping each person's identity and natural look unchanged.") }) { Text("Mejorar rostros") }
    }
    Text("Si eliges 1K, \"Aumentar resolución\" no sube el tamaño: usa 2K o 4K.")

    Text("Retratos", style = MaterialTheme.typography.labelLarge)
    ChipsRow(exprs.map { it.first }, expr) { expr = it }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled, onClick = {
            run("Change the person's facial expression to ${exprs[expr].second}, keeping the same identity, face shape, hair, clothes, pose and background.")
        }) { Text("Cambiar expresión") }
        OutlinedButton(enabled = enabled, onClick = { run("Improve the lighting on the person's face: soft, flattering, balanced light, without changing identity or the rest of the scene.") }) { Text("Iluminar rostro") }
        OutlinedButton(enabled = enabled, onClick = { run("Do a subtle natural portrait retouch: even out skin, remove blemishes and imperfections, keep skin texture realistic and do not change facial features.") }) { Text("Retoque facial") }
        OutlinedButton(enabled = enabled, onClick = { run("Apply a professional shallow depth-of-field portrait effect: keep the subject sharp and blur the background smoothly like a large-aperture lens.") }) { Text("Desenfoque pro") }
    }
    Text("Úsalo con fotos tuyas o con permiso de las personas que aparecen. El resultado reemplaza la foto base y se puede deshacer; las ediciones con IA suelen devolver ~1K de resolución salvo que elijas 2K o 4K.")
}
