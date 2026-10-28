package com.projectkite.stemsplitter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.projectkite.stemsplitter.Ui.button
import com.projectkite.stemsplitter.Ui.card
import com.projectkite.stemsplitter.Ui.dp
import com.projectkite.stemsplitter.Ui.horizontal
import com.projectkite.stemsplitter.Ui.margins
import com.projectkite.stemsplitter.Ui.setScrollingContent
import com.projectkite.stemsplitter.Ui.text
import com.projectkite.stemsplitter.Ui.vertical

class MainActivity : Activity() {
    private var selected: Uri? = null

    private lateinit var fileLabel: TextView
    private lateinit var splitButton: View
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressStage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var historyList: LinearLayout

    private val observer: (JobState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical()

        root.addView(text(getString(R.string.app_name), 28f, bold = true))
        root.addView(text(getString(R.string.tagline), 15f, Ui.TEXT_DIM).margins(top = dp(4), bottom = dp(20)))

        // File picker card.
        val pick = card()
        pick.addView(text(getString(R.string.step_choose), 13f, Ui.TEXT_DIM, bold = true))
        fileLabel = text(getString(R.string.no_file), 17f).also { pick.addView(it.margins(top = dp(8), bottom = dp(12))) }
        val buttons = horizontal()
        buttons.addView(button(getString(R.string.choose_file), filled = false) { chooseFile() }, Ui.weighted())
        splitButton = button(getString(R.string.split)) { startSplit() }
        buttons.addView(splitButton, Ui.weighted().apply { marginStart = dp(12) })
        pick.addView(buttons)
        pick.addView(text(getString(R.string.stems_hint), 13f, Ui.TEXT_DIM).margins(top = dp(12)))
        root.addView(pick)

        // Progress card.
        progressCard = card().apply { visibility = View.GONE }
        progressTitle = text("", 17f, bold = true).also { progressCard.addView(it) }
        progressStage = text("", 14f, Ui.TEXT_DIM).also { progressCard.addView(it.margins(top = dp(4))) }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }
        progressCard.addView(progressBar.margins(top = dp(12), bottom = dp(12)))
        progressCard.addView(
            button(getString(R.string.cancel), filled = false, color = Ui.ERROR) {
                SeparationJob.cancelRequested = true
                progressStage.text = getString(R.string.cancelling)
            },
        )
        root.addView(progressCard.margins(top = dp(16)))

        errorText = text("", 14f, Ui.ERROR).apply { visibility = View.GONE }
        root.addView(errorText.margins(top = dp(16)))

        root.addView(text(getString(R.string.your_splits), 20f, bold = true).margins(top = dp(28), bottom = dp(8)))
        historyList = vertical()
        root.addView(historyList)

        setScrollingContent(root)
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

    /** Audio shared to the app from another app ("Share → Stem Splitter"). */
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
        fileLabel.text = StemStore.displayName(this, uri)
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
        splitButton.alpha = if (splitButton.isEnabled) 1f else 0.4f
        progressCard.visibility = if (running) View.VISIBLE else View.GONE
        when (state) {
            is JobState.Running -> {
                progressTitle.text = state.title
                val percent = (state.progress * 100).toInt()
                val eta = state.etaSeconds?.let { " · " + getString(R.string.time_left, Ui.formatTime(it)) } ?: ""
                progressStage.text = if (SeparationJob.cancelRequested) getString(R.string.cancelling) else "${state.stage} · $percent%$eta"
                progressBar.progress = (state.progress * 1000).toInt()
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
        if (splits.isEmpty()) {
            historyList.addView(text(getString(R.string.no_splits), 14f, Ui.TEXT_DIM))
            return
        }
        for (split in splits) {
            val row = card().apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { startActivity(ResultActivity.intent(this@MainActivity, split)) }
                setOnLongClickListener { confirmDelete(split); true }
            }
            row.addView(text(split.title, 16f, bold = true))
            val ago = DateUtils.getRelativeTimeSpanString(split.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val names = split.stems.sortedBy { StemStore.order(it.source) }.joinToString(" · ") { StemStore.label(it.source) }
            row.addView(text("$ago — $names", 13f, Ui.TEXT_DIM).margins(top = dp(4)))
            historyList.addView(row.margins(bottom = dp(10)))
        }
    }

    private fun confirmDelete(split: Split) {
        AlertDialog.Builder(this)
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
