package ua.aniua.downloader.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import ua.aniua.downloader.databinding.ActivityDownloadsBinding
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.service.DownloadQueueManager
import ua.aniua.downloader.ui.adapter.DownloadsAdapter

class DownloadsActivity : AppCompatActivity(), DownloadsAdapter.OnDownloadActionListener {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter
    private lateinit var queueManager: DownloadQueueManager

    private val updateListener: (DownloadTask) -> Unit = { task ->
        if (!isFinishing && !isDestroyed) {
            adapter.updateTask(task)
            updateEmptyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarDownloads)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        (binding.rvDownloads.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        queueManager = DownloadQueueManager.getInstance(this)
        adapter = DownloadsAdapter(this)
        binding.rvDownloads.adapter = adapter

        binding.btnClearFinished.setOnClickListener {
            queueManager.clearFinished()
            refreshTaskList()
        }

        refreshTaskList()
        queueManager.addListener(updateListener)
    }

    private fun refreshTaskList() {
        adapter.setTasks(queueManager.getAllTasks())
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.itemCount == 0
        binding.layoutEmptyDownloads.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDownloads.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onCancel(task: DownloadTask) {
        queueManager.cancelTask(task.id)
    }

    override fun onRetry(task: DownloadTask) {
        queueManager.retryTask(task.id)
    }

    override fun onPlay(task: DownloadTask) {
        val path = task.savedFilePath ?: return
        val uriString = if (path.startsWith("/")) "file://$path" else path
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, uriString)
            putExtra(PlayerActivity.EXTRA_TITLE, "${task.mediaTitle} - Серія ${task.episodeNumber}")
        }
        startActivity(intent)
    }

    override fun onRemove(task: DownloadTask) {
        queueManager.removeTask(task.id)
        adapter.removeTaskById(task.id)
        updateEmptyState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.removeListener(updateListener)
    }
}
