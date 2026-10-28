package com.projectkite.stemsplitter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** CKSplitter's design system: colours, type and the (programmatic) components. */
object Ui {
    const val BACKGROUND = 0xFF0B0B12.toInt()
    const val BACKGROUND_TOP = 0xFF16112A.toInt()
    const val GLASS = 0x14FFFFFF
    const val GLASS_STRONG = 0x22FFFFFF
    const val GLASS_BORDER = 0x1FFFFFFF
    const val TEXT = 0xFFF5F3FF.toInt()
    const val TEXT_DIM = 0xFFA19CB8.toInt()
    const val TEXT_FAINT = 0xFF6E6985.toInt()
    const val ERROR = 0xFFFF6B81.toInt()

    /** Brand gradient: violet → magenta → pink. */
    val GRADIENT = intArrayOf(0xFF7C5CFF.toInt(), 0xFFC04DFF.toInt(), 0xFFFF5C8A.toInt())
    val ACCENT = GRADIENT[0]

    private val stemColors = mapOf(
        "vocals" to 0xFFFF5C8A.toInt(),
        "drums" to 0xFFFFB547.toInt(),
        "bass" to 0xFF3DDC97.toInt(),
        "guitar" to 0xFF4EA8FF.toInt(),
        "piano" to 0xFFB57BFF.toInt(),
        "other" to 0xFF2DD4E6.toInt(),
        StemStore.INSTRUMENTAL to 0xFFE2DEF5.toInt(),
    )

    fun stemColor(source: String) = stemColors[source] ?: ACCENT

    fun Context.dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    fun font(weight: Int): Typeface = Typeface.create(Typeface.DEFAULT, weight, false)

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(strokeWidth, stroke)
    }

    fun gradient(radius: Float, colors: IntArray = GRADIENT) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

    fun Context.text(
        value: CharSequence,
        sizeSp: Float = 15f,
        color: Int = TEXT,
        weight: Int = 400,
        spacing: Float = 0f,
    ) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = font(weight)
        letterSpacing = spacing
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    /** Small all-caps section label. */
    fun Context.overline(value: CharSequence, color: Int = TEXT_FAINT) =
        text(value.toString().uppercase(), 11f, color, 700, 0.14f)

    /** "CK" in the brand gradient followed by "Splitter". */
    fun Context.wordmark(sizeSp: Float): TextView {
        val view = text("", sizeSp, TEXT, 800, -0.02f)
        val word = SpannableString("CKSplitter")
        word.setSpan(GradientSpan(GRADIENT), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = word
        return view
    }

    /** Draws its span with a horizontal gradient shader. */
    private class GradientSpan(private val colors: IntArray) : ForegroundColorSpan(colors[0]) {
        override fun updateDrawState(paint: android.text.TextPaint) {
            paint.shader = LinearGradient(0f, 0f, paint.textSize * 1.4f, 0f, colors, null, Shader.TileMode.CLAMP)
        }
    }

    fun Context.icon(res: Int, tint: Int = TEXT, sizeDp: Int = 24): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Frosted-glass card. */
    fun Context.card(padding: Int = 20): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(GLASS, dp(24).toFloat(), GLASS_BORDER, dp(1))
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    /** Makes a view tappable with a ripple and a soft press-scale. */
    fun View.pressable(radiusDp: Int, onClick: (View) -> Unit) {
        val mask = rounded(Color.WHITE, context.dp(radiusDp).toFloat())
        foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask)
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    /** Pill button: brand gradient when [primary], glass outline otherwise. */
    fun Context.pillButton(
        label: CharSequence,
        iconRes: Int? = null,
        primary: Boolean = true,
        tint: Int = TEXT,
        onClick: (View) -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(56)
        setPadding(dp(22), dp(14), dp(22), dp(14))
        val radius = dp(28).toFloat()
        background = if (primary) gradient(radius) else rounded(GLASS, radius, GLASS_BORDER, dp(1))
        if (primary) elevation = dp(8).toFloat()
        if (iconRes != null) {
            addView(icon(iconRes, if (primary) Color.WHITE else tint, 20).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
            })
        }
        addView(text(label, 16f, if (primary) Color.WHITE else tint, 600))
        pressable(28, onClick)
    }

    /** Round icon button. */
    fun Context.circleButton(iconRes: Int, sizeDp: Int, primary: Boolean, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = if (primary) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, GRADIENT).apply { shape = GradientDrawable.OVAL }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GLASS)
                    setStroke(dp(1), GLASS_BORDER)
                }
            }
            if (primary) elevation = dp(10).toFloat()
            val iconSize = (sizeDp * 0.45f).toInt()
            addView(icon(iconRes, Color.WHITE, iconSize), FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            pressable(sizeDp / 2, onClick)
        }

    /** Small rounded label, e.g. a stem name. */
    fun Context.chip(label: CharSequence, color: Int): TextView = text(label, 12f, color, 600).apply {
        background = rounded((color and 0x00FFFFFF) or 0x1F000000, dp(14).toFloat())
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    /** Slim slider with a coloured fill and a white thumb. */
    fun Context.slider(color: Int, gradientFill: Boolean = false): SeekBar = SeekBar(this).apply {
        val radius = dp(3).toFloat()
        val track = rounded(0x26FFFFFF, radius)
        val fill = if (gradientFill) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GRADIENT).apply { cornerRadius = radius }
        } else {
            rounded(color, radius)
        }
        val layers = LayerDrawable(arrayOf<Drawable>(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        progressDrawable = layers
        minHeight = dp(6)
        maxHeight = dp(6)
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dp(18), dp(18))
        }
        splitTrack = false
        background = null
        setPadding(dp(9), dp(10), dp(9), dp(10))
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

    /**
     * Shows [content] edge to edge over the brand backdrop (dark gradient with a
     * violet glow), padded for the system bars.
     */
    fun Activity.setScrollingContent(content: LinearLayout) {
        val pad = dp(20)
        content.setPadding(pad, dp(12), pad, dp(32))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = Backdrop()
            addView(content)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    /** Fades and slides the children of [container] in, one after another. */
    fun animateIn(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 40f
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(40L * i).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Window background: vertical dark gradient plus a soft radial glow at the top. */
    private class Backdrop : Drawable() {
        private val base = Paint()
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            // Keep the glow pinned to the top of the screen, not the scroll content.
            val h = minOf(b.height(), (b.width() * 2.2f).toInt()).toFloat()
            base.shader = LinearGradient(0f, 0f, 0f, h, BACKGROUND_TOP, BACKGROUND, Shader.TileMode.CLAMP)
            canvas.drawRect(b, base)
            glow.shader = android.graphics.RadialGradient(
                b.width() * 0.85f, 0f, b.width() * 0.9f,
                intArrayOf(0x557C5CFF, 0x22C04DFF, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, b.width().toFloat(), b.width() * 0.9f, glow)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
}

/** Circular progress ring with a gradient arc, used while separating. */
class ProgressRing(context: Context) : View(context) {
    private val stroke = context.resources.displayMetrics.density * 10
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0x1AFFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            animator?.cancel()
            animator = ValueAnimator.ofFloat(shown, field).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shown = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val half = stroke / 2
        oval.set(half, half, w - half, h - half)
        val colors = intArrayOf(Ui.GRADIENT[0], Ui.GRADIENT[1], Ui.GRADIENT[2], Ui.GRADIENT[0])
        arc.shader = SweepGradient(w / 2f, h / 2f, colors, null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f, w / 2f, h / 2f) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(oval, 0f, 360f, false, track)
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown, false, arc)
    }
}
