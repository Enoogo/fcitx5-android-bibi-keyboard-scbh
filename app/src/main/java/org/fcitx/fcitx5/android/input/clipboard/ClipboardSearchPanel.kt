/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.min

/**
 * Search bar of the clipboard page.
 *
 * Instead of drawing a keyboard of its own, the panel *borrows* the one of the IME: it sits
 * right above [KeyboardWindow] and installs a [FcitxInputMethodService.TextInputSink], so
 * everything the engine commits lands in [query] instead of the app being edited. Layout,
 * height, dictionary, word frequency and the candidate bar are therefore literally the ones
 * of the normal keyboard — Chinese input works exactly as it does in the editor.
 *
 * Note: every view is created lazily. A component is constructed *before* it is registered
 * in the dependency scope, so touching `context`/`theme` from a constructor would fail.
 */
class ClipboardSearchPanel : UniqueViewComponent<ClipboardSearchPanel, View>(),
    InputBroadcastReceiver, FcitxInputMethodService.TextInputSink {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val preedit: PreeditComponent by manager.must()

    private val prefs = AppPrefs.getInstance()
    private val clipboardReturnAfterPaste by prefs.clipboard.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius
    private val keyBorder by ThemeManager.prefs.keyBorder

    /* ------------------------------- state ------------------------------- */

    var query: String = ""
        private set

    private var active = false

    val isActive: Boolean
        get() = active

    /** composition text as reported by the engine (client preedit takes precedence) */
    private var clientPreedit: FormattedText = FormattedText.Empty
    private var panelPreedit: FormattedText = FormattedText.Empty

    private val composing: FormattedText
        get() = if (!clientPreedit.isEmpty()) clientPreedit else panelPreedit

    private val queryFlow = MutableStateFlow("")
    private var queryJob: Job? = null

    /* -------------------------------- view ------------------------------- */

    private val queryText by lazy {
        TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(theme.keyTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(context.dp(32), 0, context.dp(4), 0)
            isSingleLine = true
        }
    }

    private val clearButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_close_24, theme).apply {
            contentDescription = context.getString(R.string.clipboard_search_clear)
            visibility = View.GONE
            setOnClickListener { setQuery("") }
        }
    }

    private val searchIcon by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_search_24)
            imageTintList = ColorStateList.valueOf(theme.altKeyTextColor.alpha(0.6f))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(context.dp(1), context.dp(1), context.dp(1), context.dp(1))
            isClickable = false
        }
    }

    private val searchBox by lazy {
        FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = context.dp(17).toFloat()
                setColor(theme.clipboardEntryColor)
            }
            addView(
                searchIcon,
                FrameLayout.LayoutParams(
                    context.dp(16), context.dp(16), Gravity.START or Gravity.CENTER_VERTICAL
                ).apply { leftMargin = context.dp(9) }
            )
            addView(queryText, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                clearButton,
                FrameLayout.LayoutParams(context.dp(32), MATCH_PARENT, Gravity.END)
            )
        }
    }

    private val backButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_arrow_back_24, theme).apply {
            contentDescription = context.getString(R.string.clipboard_search_back)
            setOnClickListener { exitToList() }
        }
    }

    private val header by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(2), 0, context.dp(6), 0)
            addView(backButton, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            addView(
                searchBox,
                LinearLayout.LayoutParams(0, context.dp(34), 1f).apply {
                    leftMargin = context.dp(2)
                }
            )
        }
    }

    private val noResultHint by lazy {
        TextView(context).apply {
            text = context.getString(R.string.clipboard_search_no_result)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor.alpha(0.7f))
            visibility = View.GONE
        }
    }

    private val resultList by lazy {
        RecyclerView(context).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            addItemDecoration(SpacesItemDecoration(context.dp(4)))
            setAdapter(this@ClipboardSearchPanel.adapter)
        }
    }

    /**
     * Height of the result list. The panel is stacked on top of the keyboard, so it must not
     * eat the whole screen: a fifth of it, three rows of entries at most.
     */
    private val listHeight: Int
        get() = min(context.dp(150), (context.resources.displayMetrics.heightPixels * 0.2f).toInt())

    override val view: View by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(theme.backgroundColor) }
            visibility = View.GONE
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(44)))
            addView(
                FrameLayout(context).apply {
                    addView(resultList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(noResultHint, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                },
                LinearLayout.LayoutParams(MATCH_PARENT, listHeight)
            )
            if (keyBorder) {
                // a hairline between the panel and the keyboard
                addView(
                    View(context).apply { setBackgroundColor(theme.dividerColor) },
                    LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1))
                )
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    cancelQuery()
                }
            })
        }
    }

    /* ------------------------------- data -------------------------------- */

    private val pager by lazy {
        Pager(PagingConfig(pageSize = 16)) { ClipboardManager.allEntries() }
    }

    private val adapter by lazy {
        buildClipboardAdapter(
            service = service,
            context = context,
            theme = theme,
            entryRadius = context.dp(clipboardEntryRadius.toFloat()),
            maskSensitive = clipboardMaskSensitive,
            onPaste = { paste(it) },
            onDeleted = { }
        ).apply {
            addLoadStateListener { state ->
                val empty = state.append.endOfPaginationReached && itemCount < 1
                noResultHint.visibility =
                    if (empty && query.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /* ----------------------------- lifecycle ----------------------------- */

    /** take over the keyboard and start a fresh search */
    fun start() {
        if (active) return
        // the host app may still have a composition pending: drop it (and the app side
        // composing range) before we take the keyboard over
        service.discardComposing()
        service.textInputSink = this
        preedit.suppressed = true
        active = true
        view.visibility = View.VISIBLE
        service.postFcitxJob { reset() }
        windowManager.attachWindow(KeyboardWindow)
        clientPreedit = FormattedText.Empty
        panelPreedit = FormattedText.Empty
        setQuery("")
        startQuerying()
    }

    /** leave search mode and go back to the clipboard list */
    fun exitToList() {
        dismiss()
        windowManager.attachWindow(ClipboardWindow())
    }

    /** hide the panel and give the keyboard back to the editor */
    fun dismiss() {
        if (!active) return
        active = false
        view.visibility = View.GONE
        service.textInputSink = null
        cancelQuery()
        noResultHint.visibility = View.GONE
        // reset the engine (drops preedit without committing) and only then stop hiding the
        // floating preedit row, otherwise a stale composition would flash on screen
        service.postFcitxJob {
            reset()
            // fcitx jobs may run off the main thread, ui state belongs to the main thread
            service.lifecycleScope.launch {
                if (!active) preedit.suppressed = false
            }
        }
    }

    private fun paste(entry: ClipboardEntry) {
        val text = entry.text
        dismiss()
        service.commitText(text)
        if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
    }

    /* --------------------------- text input ------------------------------ */

    private fun setQuery(newQuery: String) {
        query = newQuery
        renderQuery()
        queryFlow.value = newQuery
    }

    private fun renderQuery() {
        val composing = composing
        val builder = SpannableStringBuilder(query)
        if (!composing.isEmpty()) {
            builder.append(composing.toSpannedString(theme.genericActiveBackgroundColor))
        } else if (builder.isNotEmpty()) {
            // a caret, so that it is obvious where the next character lands
            val caretStart = builder.length
            builder.append("|")
            builder.setSpan(
                ForegroundColorSpan(theme.keyTextColor.alpha(0.45f)),
                caretStart, caretStart + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        queryText.text = builder
        queryText.hint = if (builder.isEmpty()) {
            context.getString(R.string.clipboard_search_hint)
        } else {
            null
        }
        val clearVisible = query.isNotEmpty()
        clearButton.visibility = if (clearVisible) View.VISIBLE else View.GONE
        // keep the text clear of the clear button
        queryText.setPadding(
            context.dp(32), 0, context.dp(if (clearVisible) 34 else 4), 0
        )
    }

    override fun commit(text: String): Boolean {
        if (!active) return false
        setQuery(query + text)
        return true
    }

    override fun backspace(): Boolean {
        if (!active) return false
        if (query.isEmpty()) return true
        setQuery(query.substring(0, query.offsetByCodePoints(query.length, -1)))
        return true
    }

    override fun enter(): Boolean {
        if (!active) return false
        exitToList()
        return true
    }

    override fun arrow(keyCode: Int): Boolean = active

    override fun deleteSurrounding(before: Int, after: Int): Boolean = active

    override fun cancel() = dismiss()

    /* ----------------------------- broadcast ----------------------------- */

    override fun onClientPreeditUpdate(data: FormattedText) {
        if (!active) return
        clientPreedit = data
        renderQuery()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (!active) return
        panelPreedit = data.preedit
        renderQuery()
    }

    override fun onWindowAttached(window: InputWindow) {
        // any other window (picker, clipboard list, ...) means the user left the search
        if (active && window !is KeyboardWindow) dismiss()
    }

    /* ------------------------------ paging ------------------------------- */

    private fun startQuerying() {
        cancelQuery()
        queryJob = service.lifecycleScope.launch {
            queryFlow.collectLatest { q ->
                if (q.isEmpty()) {
                    pager.flow.collect { adapter.submitData(it) }
                } else {
                    // debounce keystrokes
                    delay(80)
                    val result = withContext(Dispatchers.Default) {
                        ClipboardManager.searchEntries(q)
                    }
                    adapter.submitData(PagingData.from(result))
                }
            }
        }
    }

    private fun cancelQuery() {
        queryJob?.cancel()
        queryJob = null
    }
}
