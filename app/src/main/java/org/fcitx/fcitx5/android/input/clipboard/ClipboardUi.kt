/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(ctx.dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    /* ------------------------------- search ------------------------------- */

    /**
     * Entry point of the search mode: a rounded box that looks like an input field, but the
     * actual typing happens on the real keyboard (see [ClipboardSearchPanel]).
     */
    val searchBox = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(17).toFloat()
            setColor(theme.clipboardEntryColor)
        }
        isClickable = true
        contentDescription = ctx.getString(R.string.clipboard_search_hint)
        addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_search_24)
                imageTintList = ColorStateList.valueOf(theme.altKeyTextColor.alpha(0.6f))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = false
            },
            FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = ctx.dp(9) }
        )
        addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.clipboard_search_hint)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(theme.altKeyTextColor.alpha(0.55f))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ctx.dp(32), 0, ctx.dp(8), 0)
                isSingleLine = true
                isClickable = false
            },
            FrameLayout.LayoutParams(matchParent, matchParent)
        )
    }

    /* ---------------------------- title bar part ---------------------------- */

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val extension = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        addView(
            searchBox,
            LinearLayout.LayoutParams(0, ctx.dp(34), 1f).apply { marginEnd = ctx.dp(2) }
        )
        addView(deleteAllButton, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
    }

    private fun setExtensionContentShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        searchBox.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setExtensionContentShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setExtensionContentShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setExtensionContentShown(false)
            }
        }
    }

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }
}
