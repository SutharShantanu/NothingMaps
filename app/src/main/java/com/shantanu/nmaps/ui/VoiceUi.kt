package com.shantanu.nmaps.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.core.content.ContextCompat
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Listening screen for voice search. Uses the on-device recogniser when there is one, with a
 * dot-matrix level meter that follows your voice; otherwise hands off to the system's voice dialog.
 */
@Composable
fun VoiceOverlay(onResult: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var attempt by remember { mutableIntStateOf(0) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) error = "Allow the microphone to search by voice."
    }
    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text.isNullOrBlank()) onClose() else onResult(text)
    }
    val inApp = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    LaunchedEffect(Unit) {
        if (!granted && inApp) permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(inApp) {
        if (!inApp) runCatching { systemDialog.launch(recognizerIntent()) }.onFailure { error = "Voice search isn't available on this phone." }
    }

    if (inApp && granted) {
        DisposableEffect(attempt) {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { error = null }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { level = 0f }
                override fun onError(code: Int) {
                    error = when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap to try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs a connection here. Tap to try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to search by voice."
                        else -> "Voice search stopped. Tap to try again."
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) error = "Didn't catch that. Tap to try again." else onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            sr.startListening(recognizerIntent())
            onDispose { sr.destroy() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(p.background.copy(alpha = 0.97f))
            .pressable(androidx.compose.foundation.shape.RoundedCornerShape(0.dp), "Tap to try again") {
                if (error != null) { error = null; partial = ""; attempt++ }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            VoiceMeter(if (error == null) level else 0f)
            Spacer(Modifier.height(32.dp))
            NText(
                error ?: partial.ifEmpty { "Listening…" },
                (if (partial.isNotEmpty() && error == null) NType.title else NType.body).copy(textAlign = TextAlign.Center),
                color = if (error != null) p.muted else p.content,
            )
            Spacer(Modifier.height(12.dp))
            NText("Try “Navigate home”, “Find petrol nearby” or a place name.", NType.caption.copy(textAlign = TextAlign.Center), color = p.muted)
        }
        Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
            RoundButton(Glyphs.Close, "Cancel voice search", onClose, Modifier.size(56.dp), iconModifier = Modifier.size(14.dp))
        }
    }
}

private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?")

/** Columns of dots that rise with your voice, red at the peaks — a Glyph-style level meter. */
@Composable
private fun VoiceMeter(level: Float) {
    val p = LocalPalette.current
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "meter").animateFloat(
        0f, 6.28f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "t",
    )
    val shown by androidx.compose.animation.core.animateFloatAsState(level, label = "level")
    Canvas(Modifier.size(width = 180.dp, height = 90.dp)) {
        val cols = 11
        val rows = 7
        val pitch = size.width / cols
        val r = pitch * 0.3f
        for (c in 0 until cols) {
            // A resting ripple, lifted by the voice level and highest in the middle.
            val shape = 1f - abs(c - cols / 2) / (cols / 2f) * 0.6f
            val lit = (1 + (shown * shape * rows) + 0.6f * (1 + sin(t + c * 0.7f))).toInt().coerceIn(1, rows)
            for (row in 0 until rows) {
                val color = when {
                    row >= lit -> p.line
                    row == lit - 1 && lit >= rows - 1 -> p.red
                    else -> p.content
                }
                drawCircle(color, r, Offset(pitch * (c + 0.5f), size.height - (size.height / rows) * (row + 0.5f)))
            }
        }
    }
}
