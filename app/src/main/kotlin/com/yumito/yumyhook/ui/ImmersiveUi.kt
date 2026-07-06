package com.yumito.yumyhook.ui

import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 沉浸式顶/底 inset，避免系统栏遮挡内容。 */
object ImmersiveUi {

    @Suppress("DEPRECATION")
    fun apply(activity: AppCompatActivity, appBar: View, vararg scrollTargets: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        applyTopInset(appBar)
        scrollTargets.forEach { applyBottomInset(it) }
    }

    private fun applyTopInset(target: View) {
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    private fun applyBottomInset(target: View) {
        val baseBottom = target.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottom + nav.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }
}
