package com.projectkite.stemsplitter

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.projectkite.stemsplitter.Ui.button
import com.projectkite.stemsplitter.Ui.card
import com.projectkite.stemsplitter.Ui.dp
import com.projectkite.stemsplitter.Ui.horizontal
import com.projectkite.stemsplitter.Ui.margins
import com.projectkite.stemsplitter.Ui.rounded
import com.projectkite.stemsplitter.Ui.setScrollingContent
import com.projectkite.stemsplitter.Ui.text
import com.projectkite.stemsplitter.Ui.vertical
import org.json.JSONObject

/** Shows the stems of one split with a mixer (volume / mute / solo) and share buttons. */
class ResultActivity : Activity() {
    private lateinit var split: Split
    private var mixer: StemMixer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var playButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
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
        root.addView(text(split.title, 24f, bold = true))
        root.addView(text(getString(R.string.saved_to, split.title), 13f, Ui.TEXT_DIM).margins(top = dp(4), bottom = dp(16)))

        mixer = try {
            StemMixer(this, mixed.map { it.uri })
        } catch (e: Exception) {
            root.addView(text(getString(R.string.cannot_open, e.message ?: ""), 14f, Ui.ERROR).margins(bottom = dp(16)))
            null
        }

        mixer?.let { m ->
            val transport = card()
            playButton = button(getString(R.string.play)) { togglePlay() }
            transport.addView(playButton)
            seekBar = SeekBar(this).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) timeText.text = timeLabel(m.frameCount * progress / 1000)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                    override fun onStopTrackingTouch(bar: SeekBar) {
                        userSeeking = false
                        m.seek(m.frameCount * bar.progress / 1000)
                    }
                })
            }
            transport.addView(seekBar.margins(top = dp(12)))
            timeText = text(timeLabel(0), 13f, Ui.TEXT_DIM).apply { gravity = Gravity.END }
            transport.addView(timeText)
            root.addView(transport.margins(bottom = dp(16)))
            m.onStateChanged = { playing -> handler.post { playButton.text = getString(if (playing) R.string.pause else R.string.play) } }
        }

        root.addView(text(getString(R.string.stems), 20f, bold = true).margins(bottom = dp(8)))
        for (stem in stems) {
            val index = mixed.indexOf(stem)
            root.addView(stemRow(stem, if (index >= 0) mixer else null, index).margins(bottom = dp(10)))
        }
        root.addView(
            button(getString(R.string.share_all), filled = false) { shareAll(stems) }.margins(top = dp(8)),
        )
        setScrollingContent(root)
    }

    private fun stemRow(stem: Stem, mixer: StemMixer?, index: Int): View {
        val color = Ui.stemColor(stem.source)
        val row = card()
        val header = horizontal()
        val dot = View(this).apply { background = rounded(color, dp(6).toFloat()) }
        header.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        header.addView(text(StemStore.label(stem.source), 17f, bold = true), Ui.weighted())
        if (mixer != null) {
            val mute = toggle("M") { on -> mixer.setMuted(index, on) }
            val solo = toggle("S") { on -> mixer.setSoloed(index, on) }
            header.addView(mute, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(8) })
            header.addView(solo, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(8) })
        }
        header.addView(button(getString(R.string.share), filled = false, color = color) { share(stem) }, Ui.wrap())
        row.addView(header)
        if (mixer != null) {
            row.addView(
                SeekBar(this).apply {
                    max = 100
                    progress = 100
                    progressTintList = android.content.res.ColorStateList.valueOf(color)
                    thumbTintList = android.content.res.ColorStateList.valueOf(color)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            mixer.setGain(index, progress / 100f)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    })
                }.margins(top = dp(8)),
            )
        } else if (stem.source == StemStore.INSTRUMENTAL) {
            row.addView(text(getString(R.string.instrumental_hint), 13f, Ui.TEXT_DIM).margins(top = dp(6)))
        }
        return row
    }

    private fun toggle(label: String, onChange: (Boolean) -> Unit): Button {
        var on = false
        return Button(this).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            fun style() {
                setTextColor(if (on) Ui.BACKGROUND else Ui.TEXT)
                background = rounded(if (on) Ui.ACCENT else Ui.SURFACE_HIGH, dp(10).toFloat())
            }
            style()
            setOnClickListener {
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
        timeText.text = timeLabel(m.position)
    }

    private fun timeLabel(frame: Long): String {
        val total = mixer?.let { it.frameCount / it.sampleRate } ?: 0
        val sampleRate = mixer?.sampleRate ?: SeparationService.SAMPLE_RATE
        return "${Ui.formatTime(frame / sampleRate)} / ${Ui.formatTime(total)}"
    }

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
