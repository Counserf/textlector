package com.nedmah.textlector.ui.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nedmah.textlector.common.platform.logging.TtsDiagnosticLog
import com.nedmah.textlector.common.platform.tts.BookProcessingState

@Composable
fun BookProcessingPanel(
    state: BookProcessingState,
    onStartAudioGeneration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.totalParagraphs <= 0) return
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Подготовка озвучки", style = MaterialTheme.typography.titleSmall)

            ProcessingStage(
                title = "Разметка",
                done = state.markupDone,
                total = state.totalParagraphs,
                currentIndex = state.markupCurrentIndex,
                readyIndices = state.markupReadyIndices,
                running = state.markupRunning,
            )

            ProcessingStage(
                title = "Озвучка",
                done = state.audioDone,
                total = state.totalParagraphs,
                currentIndex = state.audioCurrentIndex,
                readyIndices = state.audioReadyIndices,
                running = state.audioRunning,
            )

            if (state.audioWaitingForMarkup) {
                Text(
                    "Генерация поставлена в очередь и начнётся после завершения разметки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!state.audioComplete && !state.audioRunning) {
                Button(onClick = onStartAudioGeneration, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.audioDone > 0) "Продолжить генерацию озвучки" else "Сгенерировать озвучку")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { copied = TtsDiagnosticLog.copyToClipboard() }) {
                    Text(if (copied) "Лог скопирован" else "Скопировать TTS-лог")
                }
                TextButton(onClick = {
                    TtsDiagnosticLog.clear()
                    copied = false
                }) {
                    Text("Очистить лог")
                }
            }
        }
    }
}

@Composable
private fun ProcessingStage(
    title: String,
    done: Int,
    total: Int,
    currentIndex: Int?,
    readyIndices: Set<Int>,
    running: Boolean,
) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text("$done/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        val readyText = summarizeIndices(readyIndices)
        if (readyText.isNotEmpty()) {
            Text("Готово: $readyText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (running && currentIndex != null) {
            Text(
                "Сейчас: отрывок ${currentIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (done >= total && total > 0) {
            Text("Готово полностью", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun summarizeIndices(indices: Set<Int>, maxRanges: Int = 5): String {
    if (indices.isEmpty()) return ""
    val sorted = indices.sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = sorted.first()
    var end = start
    for (value in sorted.drop(1)) {
        if (value == end + 1) {
            end = value
        } else {
            ranges += start to end
            start = value
            end = value
        }
    }
    ranges += start to end

    val shown = ranges.take(maxRanges).joinToString(", ") { (a, b) ->
        if (a == b) "${a + 1}" else "${a + 1}–${b + 1}"
    }
    return if (ranges.size > maxRanges) "$shown…" else shown
}