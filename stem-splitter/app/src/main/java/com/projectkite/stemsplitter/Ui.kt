package com.projectkite.stemsplitter

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small helpers for building the (programmatic) UI. */
object Ui {
    const val BACKGROUND = 0xFF14161B.toInt()
    const val SURFACE = 0xFF1F232B.toInt()
    const val SURFACE_HIGH = 0xFF2A2F3A.toInt()
    const val TEXT = 0xFFF2F4F8.toInt()
    const val TEXT_DIM = 0xFF9AA3B2.toInt()
    const val ACCENT = 0xFF5D9CEC.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()

    private val stemColors = mapOf(
        "vocals" to 0xFFFF7A90.toInt(),
        "drums" to 0xFFFFB454.toInt(),
        "bass" to 0xFF7BD88F.toInt(),
        "guitar" to 0xFF5D9CEC.toInt(),
        "piano" to 0xFFC792EA.toInt(),
        "other" to 0xFF4DD0E1.toInt(),
        StemStore.INSTRUMENTAL to 0xFFB0BEC5.toInt(),
    )

    fun stemColor(source: String) = stemColors[source] ?: ACCENT

    fun Context.dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(strokeWidth, stroke)
    }

    fun Context.text(value: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun Context.button(label: CharSequence, filled: Boolean = true, color: Int = ACCENT, onClick: (View) -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            setTextColor(if (filled) Color.WHITE else color)
            val radius = dp(12).toFloat()
            val shape = if (filled) rounded(color, radius) else rounded(Color.TRANSPARENT, radius, color, dp(1))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(48)
            setOnClickListener(onClick)
        }

    fun Context.card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dp(16).toFloat())
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun Context.vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun View.margins(top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): View {
        val params = (layoutParams as? ViewGroup.MarginLayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(start, top, end, bottom)
        layoutParams = params
        return this
    }

    fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Wraps [content] in a scroll view that respects the system bars (edge-to-edge on Android 15). */
    fun Activity.setScrollingContent(content: LinearLayout) {
        val pad = dp(20)
        content.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(content)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
