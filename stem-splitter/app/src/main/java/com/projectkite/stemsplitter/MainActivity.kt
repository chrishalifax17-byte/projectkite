package com.projectkite.stemsplitter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.projectkite.stemsplitter.Ui.card
import com.projectkite.stemsplitter.Ui.chip
import com.projectkite.stemsplitter.Ui.dp
import com.projectkite.stemsplitter.Ui.horizontal
import com.projectkite.stemsplitter.Ui.icon
import com.projectkite.stemsplitter.Ui.margins
import com.projectkite.stemsplitter.Ui.overline
import com.projectkite.stemsplitter.Ui.pillButton
import com.projectkite.stemsplitter.Ui.pressable
import com.projectkite.stemsplitter.Ui.setScrollingContent
import com.projectkite.stemsplitter.Ui.text
import com.projectkite.stemsplitter.Ui.vertical
import com.projectkite.stemsplitter.Ui.wordmark

class MainActivity : Activity() {
    private var selected: Uri? = null

    private lateinit var pickTitle: TextView
    private lateinit var pickSubtitle: TextView
    private lateinit var pickIcon: FrameLayout
    private lateinit var splitButton: View
    private lateinit var progressCard: LinearLayout
    private lateinit var ring: ProgressRing
    private lateinit var percentText: TextView
    private lateinit var progressTitle: TextView
    private lateinit var progressStage: TextView
    private lateinit var errorText: TextView
    private lateinit var libraryCount: TextView
    private lateinit var historyList: LinearLayout

    private val observer: (JobState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()

        // Top bar: wordmark + badge.
        val top = horizontal()
        top.addView(wordmark(22f), Ui.weighted())
        top.addView(text(getString(R.string.badge), 11f, Ui.TEXT, 700, 0.08f).apply {
            background = Ui.rounded(Ui.GLASS_STRONG, dp(12).toFloat(), Ui.GLASS_BORDER, dp(1))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        root.addView(top.margins(top = dp(8), bottom = dp(36)))

        // Hero.
        root.addView(text(getString(R.string.hero_title), 34f, Ui.TEXT, 800, -0.02f))
        root.addView(text(getString(R.string.hero_subtitle), 16f, Ui.TEXT_DIM).margins(top = dp(12), bottom = dp(28)))

        // Song picker.
        val pick = card(18)
        val pickRow = horizontal()
        pickIcon = FrameLayout(this).apply {
            background = Ui.gradient(dp(18).toFloat())
            addView(icon(R.drawable.ic_upload, Color.WHITE, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        pickRow.addView(pickIcon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) })
        val pickTexts = vertical()
        pickTitle = text(getString(R.string.choose_song), 17f, Ui.TEXT, 700)
        pickSubtitle = text(getString(R.string.formats), 13f, Ui.TEXT_DIM).apply { maxLines = 1 }
        pickTexts.addView(pickTitle)
        pickTexts.addView(pickSubtitle.margins(top = dp(5)))
        pickRow.addView(pickTexts, Ui.weighted())
        pickRow.addView(icon(R.drawable.ic_chevron, Ui.TEXT_FAINT, 22))
        pick.addView(pickRow)
        pick.pressable(24) { chooseFile() }
        root.addView(pick)

        splitButton = pillButton(getString(R.string.split), R.drawable.ic_sparkle) { startSplit() }
        root.addView(splitButton.margins(top = dp(14)))

        // What you get.
        val chips = vertical()
        val sources = listOf("vocals", "drums", "bass", "guitar", "piano", "other")
        for (row in sources.chunked(3)) {
            val line = horizontal()
            for (source in row) {
                line.addView(chip(StemStore.label(source), Ui.stemColor(source)), Ui.wrap().apply { marginEnd = dp(8) })
            }
            chips.addView(line.margins(bottom = dp(8)))
        }
        root.addView(chips.margins(top = dp(18)))

        // Progress.
        progressCard = card(24).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val ringBox = FrameLayout(this)
        ring = ProgressRing(this)
        ringBox.addView(ring, FrameLayout.LayoutParams(dp(168), dp(168)))
        val ringLabel = vertical().apply { gravity = Gravity.CENTER }
        percentText = text("0%", 36f, Ui.TEXT, 800, -0.02f).apply { gravity = Gravity.CENTER }
        ringLabel.addView(percentText)
        ringLabel.addView(overline(getString(R.string.processing)).apply { gravity = Gravity.CENTER }.margins(top = dp(4)))
        ringBox.addView(ringLabel, FrameLayout.LayoutParams(dp(168), dp(168), Gravity.CENTER))
        progressCard.addView(ringBox, LinearLayout.LayoutParams(dp(168), dp(168)))
        progressTitle = text("", 18f, Ui.TEXT, 700).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        progressCard.addView(progressTitle.margins(top = dp(22)))
        progressStage = text("", 14f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER }
        progressCard.addView(progressStage.margins(top = dp(6), bottom = dp(18)))
        progressCard.addView(
            pillButton(getString(R.string.cancel), R.drawable.ic_close, primary = false, tint = Ui.TEXT_DIM) {
                SeparationJob.cancelRequested = true
                progressStage.text = getString(R.string.cancelling)
            },
            Ui.wrap(),
        )
        root.addView(progressCard.margins(top = dp(20)))

        errorText = text("", 14f, Ui.ERROR).apply {
            visibility = View.GONE
            background = Ui.rounded(0x1FFF6B81, dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(errorText.margins(top = dp(16)))

        // Library.
        val libraryHeader = horizontal()
        libraryHeader.addView(text(getString(R.string.library), 22f, Ui.TEXT, 800, -0.01f), Ui.weighted())
        libraryCount = text("", 13f, Ui.TEXT_FAINT, 600)
        libraryHeader.addView(libraryCount)
        root.addView(libraryHeader.margins(top = dp(36), bottom = dp(14)))
        historyList = vertical()
        root.addView(historyList)

        setScrollingContent(root)
        Ui.animateIn(root)
        handleIncoming(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        SeparationJob.observe(observer)
        renderHistory()
    }

    override fun onStop() {
        SeparationJob.removeObserver(observer)
        super.onStop()
    }

    /** Audio shared to the app from another app ("Share → CKSplitter"). */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) select(uri)
    }

    private fun chooseFile() {
        if (SeparationJob.isRunning) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/mp4", "application/ogg"))
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            select(uri)
        }
    }

    private fun select(uri: Uri) {
        selected = uri
        pickTitle.text = StemStore.displayName(this, uri)
        pickSubtitle.text = getString(R.string.tap_to_change)
        (pickIcon.getChildAt(0) as? android.widget.ImageView)?.setImageResource(R.drawable.ic_music)
        errorText.visibility = View.GONE
        render(SeparationJob.state)
    }

    private fun startSplit() {
        val uri = selected ?: return chooseFile()
        if (SeparationJob.isRunning) return
        errorText.visibility = View.GONE
        SeparationService.start(this, uri)
    }

    private fun render(state: JobState) {
        val running = state is JobState.Running
        splitButton.isEnabled = !running && selected != null
        splitButton.alpha = if (splitButton.isEnabled) 1f else 0.35f
        if (running && progressCard.visibility != View.VISIBLE) {
            progressCard.visibility = View.VISIBLE
            progressCard.alpha = 0f
            progressCard.animate().alpha(1f).setDuration(300).start()
        } else if (!running) {
            progressCard.visibility = View.GONE
        }
        when (state) {
            is JobState.Running -> {
                progressTitle.text = state.title
                percentText.text = "${(state.progress * 100).toInt()}%"
                ring.progress = state.progress
                val eta = state.etaSeconds?.let { " · " + getString(R.string.time_left, Ui.formatTime(it)) } ?: ""
                progressStage.text = if (SeparationJob.cancelRequested) getString(R.string.cancelling) else state.stage + eta
            }
            is JobState.Done -> {
                SeparationJob.update(JobState.Idle)
                renderHistory()
                startActivity(ResultActivity.intent(this, state.split))
            }
            is JobState.Failed -> {
                errorText.text = getString(R.string.failed, state.title, state.message)
                errorText.visibility = View.VISIBLE
                SeparationJob.update(JobState.Idle)
            }
            JobState.Idle -> Unit
        }
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val splits = StemStore.history(this)
        libraryCount.text = if (splits.isEmpty()) "" else resources.getQuantityString(R.plurals.songs, splits.size, splits.size)
        if (splits.isEmpty()) {
            val empty = card(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
            empty.addView(icon(R.drawable.ic_music, Ui.TEXT_FAINT, 32))
            empty.addView(text(getString(R.string.no_splits), 14f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER }.margins(top = dp(10)))
            historyList.addView(empty)
            return
        }
        for (split in splits) historyList.addView(historyRow(split).margins(bottom = dp(10)))
    }

    private fun historyRow(split: Split): View {
        val row = card(14)
        val line = horizontal()
        val art = FrameLayout(this).apply {
            background = Ui.gradient(dp(14).toFloat(), artColors(split.title))
            addView(
                text(split.title.trim().take(1).uppercase(), 20f, Color.WHITE, 800).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER),
            )
        }
        line.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
        val texts = vertical()
        texts.addView(text(split.title, 16f, Ui.TEXT, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val ago = DateUtils.getRelativeTimeSpanString(split.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        texts.addView(text(getString(R.string.stems_count, split.stems.size, ago), 13f, Ui.TEXT_DIM).margins(top = dp(4)))
        val dots = horizontal()
        for (stem in split.stems.sortedBy { StemStore.order(it.source) }) {
            dots.addView(View(this).apply {
                background = Ui.rounded(Ui.stemColor(stem.source), dp(4).toFloat())
            }, LinearLayout.LayoutParams(dp(14), dp(4)).apply { marginEnd = dp(4) })
        }
        texts.addView(dots.margins(top = dp(8)))
        line.addView(texts, Ui.weighted())
        line.addView(icon(R.drawable.ic_chevron, Ui.TEXT_FAINT, 22))
        row.addView(line)
        row.pressable(24) { startActivity(ResultActivity.intent(this, split)) }
        row.setOnLongClickListener { confirmDelete(split); true }
        return row
    }

    /** A stable two-colour "cover" per song. */
    private fun artColors(title: String): IntArray {
        val palette = listOf("vocals", "drums", "bass", "guitar", "piano", "other").map(Ui::stemColor)
        val h = title.hashCode() and 0x7fffffff
        return intArrayOf(palette[h % palette.size], palette[(h / palette.size + 1) % palette.size])
    }

    private fun confirmDelete(split: Split) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(split.title)
            .setMessage(R.string.delete_question)
            .setPositiveButton(R.string.delete_files) { _, _ ->
                StemStore.removeFromHistory(this, split, deleteFiles = true)
                renderHistory()
            }
            .setNeutralButton(R.string.remove_from_list) { _, _ ->
                StemStore.removeFromHistory(this, split, deleteFiles = false)
                renderHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQUEST_PICK = 1
    }
}
