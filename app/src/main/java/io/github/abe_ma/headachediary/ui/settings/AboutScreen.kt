package io.github.abe_ma.headachediary.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.abe_ma.headachediary.BuildConfig
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.ui.openUriSafely

const val TAG_OPEN_METEO_LINK = "about_open_meteo_link"

/** FR-106, NFR-100, NFR-103: 帰属と外部送信の説明。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, versionName: String = BuildConfig.VERSION_NAME) {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.about_link_open_meteo)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(stringResource(R.string.about_section_data))
            Text(stringResource(R.string.about_data_local), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_data_sent), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_data_backup), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_data_export), style = MaterialTheme.typography.bodyLarge)

            Heading(stringResource(R.string.about_section_weather))
            TextButton(
                onClick = { uriHandler.openUriSafely(url) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_OPEN_METEO_LINK),
            ) { Text(stringResource(R.string.about_weather_attribution), style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(R.string.about_weather_license), style = MaterialTheme.typography.bodyMedium)

            Heading(stringResource(R.string.about_section_notice))
            Text(stringResource(R.string.about_notice), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }.padding(top = 8.dp),
    )
}
