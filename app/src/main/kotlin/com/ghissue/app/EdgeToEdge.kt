package com.ghissue.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Targeting Android 15+ makes full-screen activities draw under the status
 * and navigation bars; pad [this] by their size on top of its own padding.
 */
fun View.padForSystemBars() {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = start + bars.left,
            top = top + bars.top,
            right = end + bars.right,
            bottom = bottom + bars.bottom
        )
        insets
    }
}
