package ua.aniua.downloader.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import ua.aniua.downloader.R
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.service.DownloadQueueManager
import ua.aniua.downloader.ui.adapter.DownloadsAdapter

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var queueManager: DownloadQueueManager

    private val updateListener: (DownloadTask) -> Unit = { task ->
        runOnUiThread {
            adapter.updateTask(task)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_downloads)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rv = findViewById<RecyclerView>(R.id.rv_downloads)
        rv.layoutManager = LinearLayoutManager(this)

        queueManager = DownloadQueueManager.getInstance(this)
        adapter = DownloadsAdapter { task ->
            queueManager.cancelTask(task.id)
        }
        rv.adapter = adapter

        adapter.setTasks(queueManager.getAllTasks())
        queueManager.addListener(updateListener)
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
