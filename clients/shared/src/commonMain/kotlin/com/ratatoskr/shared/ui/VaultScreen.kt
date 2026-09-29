package com.ratatoskr.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.Roles
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch

/** The main screen once unlocked: header (brand + role badge + lock),
 * search/export/import/new-entry toolbar, and the entry list. Mirrors
 * #app-screen in static/index.html. Sharing and the admin users panel are
 * intentionally not here yet -- deferred fast-follows per the client dev
 * plan, not an oversight. `onExport`/`onImport` are null when the platform
 * has no file picker wired up yet, which hides those buttons. */
@Composable
fun VaultScreen(
    appState: AppState,
    onNewEntry: () -> Unit,
    onEditEntry: (EntryOut) -> Unit,
    onOpenGenerator: () -> Unit,
    onExport: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(RatatoskrColors.Bg)) {
        // ---------- header ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = RatatoskrColors.Hairline)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ratatoskr",
                style = MaterialTheme.typography.titleMedium,
                color = RatatoskrColors.Text,
                modifier = Modifier.weight(1f),
            )
            if (appState.role.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .border(1.dp, RatatoskrColors.Verdigris, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(appState.role, style = MaterialTheme.typography.labelSmall, color = RatatoskrColors.Verdigris)
                }
                Row(modifier = Modifier.padding(start = 12.dp)) {
                    GhostButton("Generate", onClick = onOpenGenerator)
                    GhostButton("Lock", onClick = { scope.launch { appState.lock() } }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        // ---------- toolbar ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RatatoskrTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search",
                modifier = Modifier.weight(1f),
            )
            // Visitors are read-only: no import or new entry, same as showAppScreen() in app.js
            val isVisitor = appState.role == Roles.VISITOR
            onExport?.let { CautionButton("Export\u2026", onClick = it) }
            if (!isVisitor) {
                onImport?.let { GhostButton("Import", onClick = it) }
                PrimaryButton("+ New entry", onClick = onNewEntry)
            }
        }

        ErrorText(appState.errorMessage, modifier = Modifier.padding(horizontal = 20.dp))

        // ---------- entry list ----------
        val filtered = appState.entries.filter {
            query.isBlank() || it.site.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true)
        }

        if (appState.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet. Entries you can see will appear here.",
                    color = RatatoskrColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    EntryRow(entry, onClick = { onEditEntry(entry) })
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: EntryOut, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(width = 1.dp, color = RatatoskrColors.Hairline)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.site, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = RatatoskrColors.Text)
                if (!entry.can_write) {
                    Text(
                        "  read-only",
                        style = MaterialTheme.typography.labelSmall,
                        color = RatatoskrColors.DangerBright,
                    )
                }
                if (entry.totp_secret.isNotBlank()) {
                    Text(
                        "  2FA",
                        style = MaterialTheme.typography.labelSmall,
                        color = RatatoskrColors.BrassBright,
                    )
                }
            }
            if (entry.username.isNotBlank()) {
                Text(entry.username, style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted)
            }
        }
    }
}
