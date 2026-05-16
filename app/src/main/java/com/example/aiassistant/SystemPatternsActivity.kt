package com.example.aiassistant

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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

/**
 * Displays learned system-setting patterns (volume, brightness, rotation, ringer)
 * grouped by app, with per-pattern delete and a "Clear All" option.
 *
 * Previously this data was recorded but never visible to the user.
 */
class SystemPatternsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var expandableList: ExpandableListView
    private lateinit var statsText: TextView

    private var patterns = listOf<SystemPatternEntity>()
    private var appNames = listOf<String>()
    private var grouped = mapOf<String, List<SystemPatternEntity>>()

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
            setPadding(0, 0, 0, 8)
        }
        titleRow.addView(TextView(this).apply {
            text = "System Patterns"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "Clear All"
            setOnClickListener { confirmClearAll() }
        })
        root.addView(titleRow)

        statsText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 12)
        }
        root.addView(statsText)

        expandableList = ExpandableListView(this)
        root.addView(
            expandableList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadPatterns()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPatterns() {
        scope.launch {
            patterns = withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@SystemPatternsActivity).systemPatternDao().getAll()
            }
            grouped = patterns.groupBy { it.packageName }
            appNames = grouped.keys.sorted()
            refreshList()
        }
    }

    private fun confirmClearAll() {
        if (patterns.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Clear All System Patterns")
            .setMessage("Delete all ${patterns.size} system pattern(s)? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ -> clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@SystemPatternsActivity).systemPatternDao().deleteAll()
            }
            patterns = emptyList()
            grouped = emptyMap()
            appNames = emptyList()
            refreshList()
        }
    }

    private fun deletePattern(pattern: SystemPatternEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Pattern")
            .setMessage("Remove \"${pattern.settingName}: ${pattern.newValue}\" (seen ${pattern.count}×)?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DatabaseHelper.getDB(this@SystemPatternsActivity)
                            .systemPatternDao().delete(pattern)
                    }
                    loadPatterns()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        statsText.text = if (patterns.isEmpty()) {
            "No system patterns learned yet."
        } else {
            "${patterns.size} pattern(s) across ${appNames.size} app(s)"
        }

        if (patterns.isEmpty()) {
            expandableList.setAdapter(null as BaseExpandableListAdapter?)
            return
        }

        val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        expandableList.setAdapter(object : BaseExpandableListAdapter() {

            override fun getGroupCount() = appNames.size
            override fun getChildrenCount(g: Int) = grouped[appNames[g]]?.size ?: 0
            override fun getGroup(g: Int) = appNames[g]
            override fun getChild(g: Int, c: Int): SystemPatternEntity =
                grouped[appNames[g]]!![c]
            override fun getGroupId(g: Int) = g.toLong()
            override fun getChildId(g: Int, c: Int) = getChild(g, c).id.toLong()
            override fun hasStableIds() = true
            override fun isChildSelectable(g: Int, c: Int) = false

            override fun getGroupView(
                g: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?
            ): View {
                val app = appNames[g]
                val count = grouped[app]?.size ?: 0
                val label = app.substringAfterLast('.').let { if (it.isNotEmpty() && it != app) "$it  ($app)" else app }

                val outer = LinearLayout(this@SystemPatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(48, 16, 16, 16)
                }
                val info = LinearLayout(this@SystemPatternsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this@SystemPatternsActivity).apply {
                    text = label
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                })
                info.addView(TextView(this@SystemPatternsActivity).apply {
                    text = "$count pattern(s)  •  ${if (isExpanded) "▲" else "▼"}"
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                })
                outer.addView(info)
                return outer
            }

            override fun getChildView(
                g: Int, c: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?
            ): View {
                val p = getChild(g, c)

                val row = LinearLayout(this@SystemPatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(64, 8, 16, 8)
                }

                val detail = LinearLayout(this@SystemPatternsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                // Setting name + value change
                detail.addView(TextView(this@SystemPatternsActivity).apply {
                    text = "${p.settingName}:  ${p.oldValue} → ${p.newValue}"
                    textSize = 15f
                })

                // Count badge + matched keywords
                val metaRow = LinearLayout(this@SystemPatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 0)
                }
                metaRow.addView(TextView(this@SystemPatternsActivity).apply {
                    text = " SYSTEM "
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF1565C0.toInt())
                    setPadding(12, 2, 12, 2)
                })
                metaRow.addView(TextView(this@SystemPatternsActivity).apply {
                    text = "  seen ${p.count}×"
                    textSize = 12f
                    setTextColor(0xFF666666.toInt())
                })
                detail.addView(metaRow)

                if (p.matchedKeywords.isNotBlank()) {
                    detail.addView(TextView(this@SystemPatternsActivity).apply {
                        text = "Keywords: ${p.matchedKeywords}"
                        textSize = 11f
                        setTextColor(0xFFAAAAAA.toInt())
                        setPadding(0, 2, 0, 0)
                    })
                }

                detail.addView(TextView(this@SystemPatternsActivity).apply {
                    text = "Last: ${dateFmt.format(Date(p.lastSeen))}"
                    textSize = 11f
                    setTextColor(0xFFAAAAAA.toInt())
                    setPadding(0, 2, 0, 0)
                })

                row.addView(detail)
                row.addView(Button(this@SystemPatternsActivity).apply {
                    text = "✕"
                    textSize = 13f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener { deletePattern(p) }
                })
                return row
            }
        })

        for (i in appNames.indices) expandableList.expandGroup(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
