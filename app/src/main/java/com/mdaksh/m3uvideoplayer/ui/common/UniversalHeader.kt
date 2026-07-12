package com.mdaksh.m3uvideoplayer.ui.common

import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.mdaksh.m3uvideoplayer.R

/**
 * Configures the shared activity toolbar ([R.id.toolbar]) as the app's *universal header*:
 * Title + Search + View filter + Sort + Settings, rendered identically on every screen.
 *
 * All five components always render. A `null` callback leaves that control visible but inert
 * (e.g. Search / View / Sort on the form screens) — intentional, so the unified header shows up
 * on every screen while only doing something where it makes sense. [showBack] swaps the leading
 * slot for a back arrow that pops the nav stack (used by the detail/form screens).
 *
 * Centralises the toolbar boilerplate every fragment previously duplicated.
 */
fun Fragment.setupUniversalHeader(
    title: String,
    showBack: Boolean = false,
    showSearch: Boolean = true,
    showViewMode: Boolean = true,
    showSort: Boolean = true,
    showSettings: Boolean = true,
    onQueryChange: ((String) -> Unit)? = null,
    onViewMode: (() -> Unit)? = null,
    onSort: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val toolbar = requireActivity().findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.title = title

    if (showBack) {
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    } else {
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
    }

    toolbar.menu.clear()
    toolbar.inflateMenu(R.menu.menu_universal)

    val searchItem = toolbar.menu.findItem(R.id.action_search)
    searchItem.isVisible = showSearch
    if (showSearch) {
        (searchItem.actionView as SearchView).apply {
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    onQueryChange?.invoke(newText.orEmpty())
                    return true
                }
            })
        }
        // Expanding search frees the whole bar for the input (title hidden); collapsing restores the
        // title and clears any active query so the full list returns.
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                toolbar.title = null
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                toolbar.title = title
                onQueryChange?.invoke("")
                return true
            }
        })
    }

    toolbar.menu.findItem(R.id.action_view_mode).isVisible = showViewMode
    toolbar.menu.findItem(R.id.action_sort).isVisible = showSort
    toolbar.menu.findItem(R.id.action_settings).isVisible = showSettings

    // Every action stays clickable; a missing callback is simply a no-op (still "handled" so the
    // event isn't bubbled), keeping the icon visible-but-inert on screens it doesn't apply to.
    toolbar.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_view_mode -> { onViewMode?.invoke(); true }
            R.id.action_sort -> { onSort?.invoke(); true }
            R.id.action_settings -> { onSettings?.invoke(); true }
            else -> false
        }
    }

    // promt3 ACTION_1 — guarantee every header control (back arrow, search, view filter, sort,
    // settings) can take D-pad focus AND shows a visible focus ring so the remote's landing spot is
    // obvious. The action-item views are created asynchronously after inflateMenu(), so enforce it
    // on every layout pass to catch menu items and expanded SearchViews.
    toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        toolbar.markHeaderControlsFocusable()
    }
    toolbar.markHeaderControlsFocusable()
}

/**
 * promt3 — walks the toolbar for its actionable controls: the navigation (back) button and each
 * action-menu item (Search / View / Sort / Settings). The title TextView is intentionally skipped
 * so focus never parks on it.
 */
private fun MaterialToolbar.markHeaderControlsFocusable() {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is ActionMenuView) {
            for (j in 0 until child.childCount) {
                child.getChildAt(j).markAsHeaderControl()
            }
        } else if (child is ImageButton) {
            child.markAsHeaderControl() // Navigation / back button
        } else if (child is SearchView) {
            // Expanded search view: mark its internal buttons/edittext
            child.markSearchViewChildrenFocusable()
        }
    }
}

/**
 * Recursively find and mark focusable controls within a SearchView (e.g. search_button, 
 * search_close_btn, search_src_text).
 */
private fun View.markSearchViewChildrenFocusable() {
    if (this is android.widget.EditText || this is android.widget.ImageButton) {
        markAsHeaderControl()
    }
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).markSearchViewChildrenFocusable()
        }
    }
}

/**
 * promt3 — makes a single header control D-pad focusable/clickable and paints the visible focus
 * ring. On TVs, we use a LayerDrawable to stack the focus ring on top of the existing background
 * (preserving any ripple) because the 'foreground' attribute is often ignored on Toolbar children.
 */
private fun View.markAsHeaderControl() {
    isFocusable = true
    isClickable = true
    
    // Check if we've already wrapped the background in our focus-aware LayerDrawable.
    if (tag == "header_focus_set") return
    
    val focusDrawable = ContextCompat.getDrawable(context, R.drawable.bg_header_focus) ?: return
    val existingBg = background
    
    background = if (existingBg != null) {
        LayerDrawable(arrayOf(existingBg, focusDrawable))
    } else {
        focusDrawable
    }
    
    tag = "header_focus_set"
}
