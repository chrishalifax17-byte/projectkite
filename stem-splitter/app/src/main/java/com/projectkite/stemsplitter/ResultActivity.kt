package com.projectkite.stemsplitter

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.projectkite.stemsplitter.Ui.card
import com.projectkite.stemsplitter.Ui.circleButton
import com.projectkite.stemsplitter.Ui.dp
import com.projectkite.stemsplitter.Ui.horizontal
import com.projectkite.stemsplitter.Ui.icon
import com.projectkite.stemsplitter.Ui.margins
import com.projectkite.stemsplitter.Ui.overline
import com.projectkite.stemsplitter.Ui.pillButton
import com.projectkite.stemsplitter.Ui.pressable
import com.projectkite.stemsplitter.Ui.rounded
import com.projectkite.stemsplitter.Ui.setScrollingContent
import com.projectkite.stemsplitter.Ui.slider
import com.projectkite.stemsplitter.Ui.text
import com.projectkite.stemsplitter.Ui.vertical
import org.json.JSONObject

/** Shows the stems of one split with a mixer (volume / mute / solo) and share buttons. */
class ResultActivity : Activity() {
    private lateinit var split: Split
    private var mixer: StemMixer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var playIcon: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var totalText: TextView
    private var userSeeking = false

    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        split = Split.fromJson(JSONObject(intent.getStringExtra(EXTRA_SPLIT)!!))
        val stems = split.stems.sortedBy { StemStore.order(it.source) }
        val mixed = stems.filter { it.source != StemStore.INSTRUMENTAL }

        val root = vertical()

        // Top bar.
        val top = horizontal()
        top.addView(circleButton(R.drawable.ic_back, 44, primary = false) { finish() })
        top.addView(overline(getString(R.string.mixer)).apply { gravity = Gravity.CENTER }, Ui.weighted())
        top.addView(circleButton(R.drawable.ic_share, 44, primary = false) { shareAll(stems) })
        root.addView(top.margins(top = dp(8), bottom = dp(28)))

        root.addView(text(split.title, 30f, Ui.TEXT, 800, -0.02f))
        root.addView(
            text(getString(R.string.saved_to, split.title), 13f, Ui.TEXT_DIM).margins(top = dp(8), bottom = dp(24)),
        )

        mixer = try {
            StemMixer(this, mixed.map { it.uri })
        } catch (e: Exception) {
            root.addView(text(getString(R.string.cannot_open, e.message ?: ""), 14f, Ui.ERROR).margins(bottom = dp(16)))
            null
        }

        mixer?.let { m -> root.addView(transport(m).margins(bottom = dp(28))) }

        val header = horizontal()
        header.addView(text(getString(R.string.stems), 22f, Ui.TEXT, 800, -0.01f), Ui.weighted())
        if (mixer != null) header.addView(text(getString(R.string.mixer_hint), 12f, Ui.TEXT_FAINT, 600))
        root.addView(header.margins(bottom = dp(14)))

        for (stem in stems) {
            val index = mixed.indexOf(stem)
            root.addView(stemRow(stem, if (index >= 0) mixer else null, index).margins(bottom = dp(10)))
        }
        root.addView(
            pillButton(getString(R.string.share_all), R.drawable.ic_share, primary = false) { shareAll(stems) }
                .margins(top = dp(14)),
        )
        setScrollingContent(root)
        Ui.animateIn(root)
    }

    private fun transport(m: StemMixer): View {
        val box = card(20)
        val row = horizontal()
        val play = circleButton(R.drawable.ic_play, 68, primary = true) { togglePlay() }
        playIcon = (play.getChildAt(0) as ImageView)
        row.addView(play)
        val info = vertical()
        info.addView(overline(getString(R.string.full_mix)))
        info.addView(text(getString(R.string.stems_playing, m.trackCount), 15f, Ui.TEXT, 600).margins(top = dp(6)))
        row.addView(info, Ui.weighted().apply { marginStart = dp(18) })
        box.addView(row)

        seekBar = slider(Ui.ACCENT, gradientFill = true).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsedText.text = timeLabel(m.frameCount * progress / 1000)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    m.seek(m.frameCount * bar.progress / 1000)
                }
            })
        }
        box.addView(seekBar.margins(top = dp(18)))
        val times = horizontal()
        elapsedText = text(timeLabel(0), 12f, Ui.TEXT_DIM, 600)
        totalText = text(timeLabel(m.frameCount), 12f, Ui.TEXT_DIM, 600).apply { gravity = Gravity.END }
        times.addView(elapsedText, Ui.weighted())
        times.addView(totalText, Ui.weighted())
        box.addView(times.margins(start = dp(9), end = dp(9)))
        m.onStateChanged = { playing ->
            handler.post { playIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play) }
        }
        return box
    }

    private fun stemRow(stem: Stem, mixer: StemMixer?, index: Int): View {
        val color = Ui.stemColor(stem.source)
        val row = card(16)
        val header = horizontal()
        val badge = FrameLayout(this).apply {
            background = rounded((color and 0x00FFFFFF) or 0x26000000, dp(12).toFloat())
            addView(
                text(StemStore.label(stem.source).take(1), 16f, color, 800).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER),
            )
        }
        header.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val names = vertical()
        names.addView(text(StemStore.label(stem.source), 16f, Ui.TEXT, 700))
        if (stem.source == StemStore.INSTRUMENTAL) {
            names.addView(text(getString(R.string.instrumental_hint), 12f, Ui.TEXT_DIM).margins(top = dp(3)))
        }
        header.addView(names, Ui.weighted())
        if (mixer != null) {
            header.addView(toggle("M", Ui.TEXT_DIM) { on -> mixer.setMuted(index, on) }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(6) })
            header.addView(toggle("S", color) { on -> mixer.setSoloed(index, on) }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginEnd = dp(6) })
        }
        val share = FrameLayout(this).apply {
            addView(icon(R.drawable.ic_share, Ui.TEXT_DIM, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable(17) { share(stem) }
        }
        header.addView(share, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(header)
        if (mixer != null) {
            row.addView(
                slider(color).apply {
                    max = 100
                    progress = 100
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            mixer.setGain(index, progress / 100f)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    })
                }.margins(top = dp(10)),
            )
        }
        return row
    }

    private fun toggle(label: String, activeColor: Int, onChange: (Boolean) -> Unit): TextView {
        var on = false
        return text(label, 13f, Ui.TEXT_DIM, 800).apply {
            gravity = Gravity.CENTER
            fun style() {
                setTextColor(if (on) Color.BLACK else Ui.TEXT_DIM)
                background = if (on) rounded(activeColor, dp(10).toFloat()) else rounded(Ui.GLASS, dp(10).toFloat(), Ui.GLASS_BORDER, dp(1))
            }
            style()
            pressable(10) {
                on = !on
                style()
                onChange(on)
            }
        }
    }

    private fun togglePlay() {
        val m = mixer ?: return
        if (m.isPlaying) m.pause() else m.play()
    }

    private fun updatePosition() {
        val m = mixer ?: return
        if (userSeeking || m.frameCount == 0L) return
        seekBar.progress = (m.position * 1000 / m.frameCount).toInt()
        elapsedText.text = timeLabel(m.position)
    }

    private fun timeLabel(frame: Long) = Ui.formatTime(frame / SeparationService.SAMPLE_RATE)

    private fun share(stem: Stem) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, stem.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(StemStore.label(stem.source), stem.uri)
        startActivity(Intent.createChooser(intent, StemStore.label(stem.source)))
    }

    private fun shareAll(stems: List<Stem>) {
        val uris = ArrayList<Uri>(stems.map { it.uri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("audio/wav")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(split.title, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        startActivity(Intent.createChooser(intent, split.title))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        mixer?.close()
        mixer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SPLIT = "split"

        fun intent(context: Context, split: Split): Intent =
            Intent(context, ResultActivity::class.java)
                .putExtra(EXTRA_SPLIT, split.toJson().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
