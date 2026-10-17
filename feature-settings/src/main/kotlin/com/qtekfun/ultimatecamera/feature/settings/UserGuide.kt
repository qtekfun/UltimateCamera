package com.qtekfun.ultimatecamera.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * The user guide (docs/user-guide/{en,es}.md, D67) opened in the system browser from the GitHub repository: no network
 * code of our own, nothing bundled that can go stale against the published docs.
 */
object UserGuide {
    private const val BASE = "https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/"

    /** The guide in the app's language: Spanish for any `es*` locale, English otherwise. */
    fun url(languageTag: String): String = BASE + (if (languageTag.lowercase().startsWith("es")) "es" else "en") + ".md"

    /** Opens [url] in the browser; false when the phone has none (the row then does nothing, no crash). */
    fun open(context: Context, languageTag: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(languageTag))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Settings › Help › User guide. */
@Composable
internal fun HelpSection() {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    SettingsSection(stringResource(R.string.settings_section_help), R.drawable.ic_set_help) {
        SettingsNavItem(
            title = stringResource(R.string.settings_help_open),
            description = stringResource(R.string.settings_help_description),
            onClick = { UserGuide.open(context, if (locales.isEmpty) "en" else locales[0].toLanguageTag()) }
        )
    }
}
