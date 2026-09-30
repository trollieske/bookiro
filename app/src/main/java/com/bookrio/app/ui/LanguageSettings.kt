package com.bookrio.app.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookrio.R
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import androidx.core.os.LocaleListCompat

/**
 * Native per-app language support (AndroidX AppCompat).
 *
 * - system  -> AppCompatDelegate.getEmptyLocaleList() (system default)
 * - "xx"    -> LocaleListCompat.forLanguageTags(tag)
 *
 * Activity recreation is handled automatically by AppCompat on API < 33 and by
 * the Android system on API 33+ (see res/xml/locale_config.xml).
 */
object ShelfLanguages {
    /** Exact BCP-47 tags supported by Bookiro (must mirror res/xml/locale_config.xml). */
    val tags: List<String> = listOf("en", "nb", "da", "sv", "fr", "de", "es", "ru", "uk")

    @StringRes
    fun displayNameRes(tag: String): Int = when (tag) {
        "en" -> R.string.lang_name_en
        "nb" -> R.string.lang_name_nb
        "da" -> R.string.lang_name_da
        "sv" -> R.string.lang_name_sv
        "fr" -> R.string.lang_name_fr
        "de" -> R.string.lang_name_de
        "es" -> R.string.lang_name_es
        "ru" -> R.string.lang_name_ru
        "uk" -> R.string.lang_name_uk
        else -> R.string.lang_name_en
    }
}

/** Null = system default; otherwise a supported BCP-47 tag. */
fun currentAppLanguageTag(): String? {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val first = tags.split(',').firstOrNull { it.isNotBlank() }
    return first?.takeIf { it in ShelfLanguages.tags }
}

fun systemLanguageDisplayName(): String {
    val locales = android.content.res.Resources.getSystem().configuration.locales
    val loc = if (locales.isEmpty) java.util.Locale.getDefault() else locales[0]
    val name = loc.getDisplayName(loc)
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
}

fun applyAppLanguage(tag: String?) {
    AppCompatDelegate.setApplicationLocales(
        if (tag == null) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
    )
}

/**
 * Plain dark HUD language picker used by both Settings and the Welcome screen.
 * Options are shown in their own language; the first row is always
 * "System default" with the effective device language as a secondary line.
 * No flags, no cards — a flat list matching the Shelf black/lime aesthetic.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(onDismiss: () -> Unit) {
    val activeTag = remember { currentAppLanguageTag() }
    val systemName = remember { systemLanguageDisplayName() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OmarchyColors.Panel,
        contentColor = OmarchyColors.Fg,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.language_picker_title),
                style = ShelfTypography.LabelMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = OmarchyColors.Dim,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            LanguagePickerRow(
                selected = activeTag == null,
                primary = stringResource(R.string.lang_system_default),
                secondary = stringResource(R.string.lang_system_default_desc, systemName),
                onClick = { applyAppLanguage(null); onDismiss() }
            )
            ShelfLanguages.tags.forEach { tag ->
                LanguagePickerRow(
                    selected = activeTag == tag,
                    primary = stringResource(ShelfLanguages.displayNameRes(tag)),
                    onClick = { applyAppLanguage(tag); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun LanguagePickerRow(
    selected: Boolean,
    primary: String,
    secondary: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                style = ShelfTypography.BodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (secondary != null) {
                Text(
                    secondary,
                    style = ShelfTypography.BodySmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = OmarchyColors.Accent,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}