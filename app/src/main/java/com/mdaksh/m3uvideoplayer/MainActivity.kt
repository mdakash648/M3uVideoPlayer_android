package com.mdaksh.m3uvideoplayer

import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.mdaksh.m3uvideoplayer.data.work.PlaylistUpdateScheduler
import com.mdaksh.m3uvideoplayer.domain.usecase.GetPlaylistsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var getPlaylistsUseCase: GetPlaylistsUseCase
    @Inject lateinit var updateScheduler: PlaylistUpdateScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Kick off a one-time refresh for any playlist set to "On application start".
        lifecycleScope.launch {
            updateScheduler.runStartupSyncs(getPlaylistsUseCase().first())
        }
    }

    /**
     * promt3 — global D-pad bridge between the shared header ([R.id.toolbar]) and whatever content
     * the current fragment shows ([R.id.navHostFragment]). Every list screen lives in this single
     * Activity under the same toolbar, so wiring the traversal here fixes it for all of them at
     * once (Home, Playlist, Folder, Folder Content, Search, Settings).
     *
     * Handled here (before normal dispatch) rather than per-layout because the header's action
     * views are generated at runtime and can't be referenced by static `nextFocusUp/Down` IDs.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (bridgeFocusUpToHeader()) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (bridgeFocusDownToContent()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * ACTION_2 — UP from the very top content row moves focus into the header. We only bridge when
     * FocusFinder finds nothing focusable above the current view *within the content region* (i.e.
     * we're genuinely on the top row); ordinary row-to-row navigation is left to the framework.
     */
    private fun bridgeFocusUpToHeader(): Boolean {
        val focused = currentFocus ?: return false
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar) ?: return false
        val content = findViewById<ViewGroup>(R.id.navHostFragment) ?: return false
        if (isDescendantOf(focused, toolbar)) return false   // already in the header
        if (!isDescendantOf(focused, content)) return false  // focus isn't in the content region
        val above = FocusFinder.getInstance().findNextFocus(content, focused, View.FOCUS_UP)
        if (above != null) return false                      // still an attached row above us
        // A scrolled list has off-screen rows above that aren't attached (so FocusFinder can't see
        // them); let the list scroll up instead of jumping to the header until it's truly at top.
        if (canScrollUpWithin(focused, content)) return false
        
        // Find the first focusable child in the toolbar to target directly.
        val firstChild = findFirstFocusableHeaderControl(toolbar)
        return if (firstChild != null) {
            firstChild.requestFocus()
        } else {
            // Fallback: request focus on the toolbar itself, which might help Android find a child.
            toolbar.requestFocus()
        }
    }

    private fun findFirstFocusableHeaderControl(toolbar: MaterialToolbar): View? {
        // Navigation button (back) is usually the leftmost.
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ImageButton && child.isFocusable) return child
        }
        // Then check the menu.
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView) {
                for (j in 0 until child.childCount) {
                    val item = child.getChildAt(j)
                    if (item.isFocusable) return item
                }
            }
        }
        return null
    }

    /** True if any scroll container between [focused] and [content] (inclusive) can still scroll up. */
    private fun canScrollUpWithin(focused: View, content: View): Boolean {
        var view: View? = focused
        while (view != null) {
            if (view.canScrollVertically(-1)) return true
            if (view === content) break
            view = view.parent as? View
        }
        return false
    }

    /**
     * ACTION_3 (Arrow DOWN) — from any header control, drop focus back into the first row of the
     * active content below.
     */
    private fun bridgeFocusDownToContent(): Boolean {
        val focused = currentFocus ?: return false
        val toolbar = findViewById<View>(R.id.toolbar) ?: return false
        val content = findViewById<View>(R.id.navHostFragment) ?: return false
        if (!isDescendantOf(focused, toolbar)) return false  // only when currently in the header
        return content.requestFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }
}
