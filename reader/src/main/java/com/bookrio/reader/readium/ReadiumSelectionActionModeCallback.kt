package com.bookrio.reader.readium

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.util.BaseActionModeCallback

/**
 * Custom selection action mode for the EPUB navigator.
 *
 * Readium 3.0.3 accepts an `ActionMode.Callback` through
 * `EpubNavigatorFragment.Configuration.selectionActionModeCallback`; when set, the WebView
 * no longer builds its own default menu, so this callback recreates Copy/Share and adds
 * Bookiro's Highlight action. `SelectableNavigator.currentSelection()` is the API that hands
 * back the selected Locator (with the selected quote in `text.highlight`).
 *
 * The selection is read asynchronously (the navigator round-trips to the page JS); the action
 * mode is only finished after the selection has been captured.
 */
internal class ReadiumSelectionActionModeCallback(
    private val copyLabel: String,
    private val shareLabel: String,
    private val highlightLabel: String,
    private val scope: CoroutineScope,
    private val selectionProvider: suspend () -> Selection?,
    private val onCopy: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onHighlight: (Selection) -> Unit,
) : BaseActionModeCallback() {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_COPY, MENU_COPY_ORDER, copyLabel)
        menu.add(Menu.NONE, MENU_SHARE, MENU_SHARE_ORDER, shareLabel)
        menu.add(Menu.NONE, MENU_HIGHLIGHT, MENU_HIGHLIGHT_ORDER, highlightLabel)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = item.itemId
        scope.launch {
            val selection = runCatching { selectionProvider() }.getOrNull()
            val text = selection?.locator?.text?.highlight?.trim().orEmpty()
            try {
                when (action) {
                    MENU_COPY -> if (text.isNotEmpty()) onCopy(text)
                    MENU_SHARE -> if (text.isNotEmpty()) onShare(text)
                    MENU_HIGHLIGHT -> if (selection != null && text.isNotEmpty()) onHighlight(selection)
                }
            } finally {
                mode.finish()
            }
        }
        return true
    }

    companion object {
        const val MENU_COPY = 1
        const val MENU_SHARE = 2
        const val MENU_HIGHLIGHT = 3
        private const val MENU_COPY_ORDER = 1
        private const val MENU_SHARE_ORDER = 2
        private const val MENU_HIGHLIGHT_ORDER = 3
    }
}