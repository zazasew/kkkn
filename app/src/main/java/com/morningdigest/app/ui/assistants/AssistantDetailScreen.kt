package com.morningdigest.app.ui.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.facts.AssistantReportBuilder
import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.prefs.MascotCharacter
import com.morningdigest.app.ui.mascot.MascotIllustration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dedicated per-character report screen - a fuller version of that
 * analyst's briefing (multiple headlines/movers, not just one line), with
 * its own refresh button. Still no chat, no interaction beyond refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDetailScreen(characterId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MorningDigestApp).container
    val character = MascotCharacter.fromId(characterId) ?: MascotCharacter.PANDA
    var report by remember { mutableStateOf<DigestReport?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(characterId) {
        report = container.digestRepository.getLatestReport()
    }

    fun refresh() {
        scope.launch {
            isRefreshing = true
            runCatching {
                val settings = container.settingsRepository.currentSettings()
                when (character) {
                    MascotCharacter.PANDA -> container.digestRepository.refreshWeatherSection(settings)
                    MascotCharacter.OWL -> container.digestRepository.refreshWorldNewsSection(settings)
                    MascotCharacter.BULL, MascotCharacter.BEAR, MascotCharacter.FOX ->
                        container.digestRepository.refreshMarketsSection(settings)
                    MascotCharacter.CAT -> container.digestRepository.refreshBusinessSection(settings)
                }
            }.getOrNull()?.let { report = it }
            isRefreshing = false
        }
    }

    val accent = Color(character.accentColorArgb)
    val lines = remember(report, character) { AssistantReportBuilder.buildDetailLines(character, report) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(character.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(character.role, style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MascotIllustration(character, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(character.description, style = MaterialTheme.typography.bodyMedium)
                        report?.let {
                            Spacer(Modifier.size(2.dp))
                            Text(
                                "Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestampMillis))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(lines) { line ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
