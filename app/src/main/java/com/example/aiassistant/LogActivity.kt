package com.example.aiassistant

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class LogActivity : AppCompatActivity() {

    companion object {
        /** Cap to avoid loading an unbounded number of rows into memory. */
        private const val LOG_PAGE_SIZE = 200
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var listView: ListView
    private var logs = listOf<LogEntity>()

    private data class RowViews(
        val title: TextView,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title row
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        titleRow.addView(TextView(this).apply {
            text = "Action History"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        titleRow.addView(Button(this).apply {
            text = "Clear All"
            setOnClickListener { clearLogs() }
        })

        root.addView(titleRow)

        // List
        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadLogs()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadLogs() {
        scope.launch {
            logs = withContext(Dispatchers.IO) {
                // Limit to most-recent LOG_PAGE_SIZE entries to avoid OOM on heavily-used devices.
                DatabaseHelper.getDB(this@LogActivity).logDao().getRecent(LOG_PAGE_SIZE)
            }
            refreshList()
        }
    }

    private fun clearLogs() {
        scope.launch {
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@LogActivity).logDao().clearAll()
            }
            logs = emptyList()
            refreshList()
        }
    }

    private fun refreshList() {
        if (logs.isEmpty()) {
            listView.adapter = null
            return
        }

        val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

        listView.adapter = object : BaseAdapter() {
            override fun getCount() = logs.size
            override fun getItem(pos: Int) = logs[pos]
            override fun getItemId(pos: Int) = logs[pos].id.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val log = logs[pos]
                val row = if (convertView == null) {
                    val layout = LinearLayout(this@LogActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(8, 12, 8, 12)
                    }
                    val title = TextView(this@LogActivity).apply {
                        textSize = 15f
                    }
                    val subtitle = TextView(this@LogActivity).apply {
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                    }
                    layout.addView(title)
                    layout.addView(subtitle)
                    layout.tag = RowViews(title, subtitle)
                    layout
                } else {
                    convertView
                }
                val views = row.tag as RowViews

                val status = if (log.success) "✓" else "✗"
                val time = dateFormat.format(Date(log.timestamp))

                views.title.text = "$status  ${log.actionType}: ${log.actionDetail}"
                views.subtitle.text = "$time  |  ${log.packageName}"
                return row
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
