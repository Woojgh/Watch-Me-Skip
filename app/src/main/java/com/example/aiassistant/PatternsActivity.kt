package com.example.aiassistant

import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
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
 * Shows learned patterns grouped by app, with expandable sections
 * and separated detail lines for each pattern.
 */
class PatternsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var expandableList: ExpandableListView
    private lateinit var statsText: TextView
    private var patterns = listOf<UserPatternEntity>()

    /** App package names in display order */
    private var appNames = listOf<String>()
    /** Patterns grouped by app */
    private var groupedPatterns = mapOf<String, List<UserPatternEntity>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title row with Clear All button
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        titleRow.addView(TextView(this).apply {
            text = "Learned Patterns"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "Clear All"
            setOnClickListener { confirmClearAll() }
        })
        root.addView(titleRow)

        // Stats line
        statsText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 12)
        }
        root.addView(statsText)

        // Expandable pattern list grouped by app
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
                DatabaseHelper.getDB(this@PatternsActivity).userPatternDao().getAll()
            }
            groupedPatterns = patterns.groupBy { it.packageName }
            appNames = groupedPatterns.keys.sorted()
            refreshList()
        }
    }

    private fun confirmClearAll() {
        if (patterns.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Clear All Patterns")
            .setMessage(
                "Delete all ${patterns.size} learned pattern(s)? " +
                        "Blocked status for those apps will also reset. This cannot be undone."
            )
            .setPositiveButton("Delete All") { _, _ -> clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAll() {
        scope.launch {
            val affectedPackages = patterns.map { it.packageName }.toSet()
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@PatternsActivity).userPatternDao().deleteAll()
                SafetyChecker.removeExcludedApps(this@PatternsActivity, affectedPackages)
            }
            patterns = emptyList()
            groupedPatterns = emptyMap()
            appNames = emptyList()
            refreshList()
        }
    }

    private fun deletePattern(pattern: UserPatternEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Pattern")
            .setMessage("Remove \"${pattern.actionText}\" (seen ${pattern.count}×)?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DatabaseHelper.getDB(this@PatternsActivity).userPatternDao().delete(pattern)
                    }
                    loadPatterns()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        statsText.text = if (patterns.isEmpty()) {
            "No patterns learned yet."
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
            override fun getChildrenCount(groupPos: Int): Int {
                return groupedPatterns[appNames[groupPos]]?.size ?: 0
            }
            override fun getGroup(groupPos: Int) = appNames[groupPos]
            override fun getChild(groupPos: Int, childPos: Int): UserPatternEntity {
                return groupedPatterns[appNames[groupPos]]!![childPos]
            }
            override fun getGroupId(groupPos: Int) = groupPos.toLong()
            override fun getChildId(groupPos: Int, childPos: Int) =
                getChild(groupPos, childPos).id.toLong()
            override fun hasStableIds() = true
            override fun isChildSelectable(groupPos: Int, childPos: Int) = false

            override fun getGroupView(
                groupPos: Int, isExpanded: Boolean,
                convertView: View?, parent: ViewGroup?
            ): View {
                val app = appNames[groupPos]
                val count = groupedPatterns[app]?.size ?: 0
                val appLabel = simplifyPackageName(app)
                val isExcluded = !SafetyChecker.isAppAllowed(this@PatternsActivity, app)

                val outer = LinearLayout(this@PatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(48, 16, 16, 16)
                }

                // App info column
                val info = LinearLayout(this@PatternsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                info.addView(TextView(this@PatternsActivity).apply {
                    text = appLabel
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                })
                info.addView(TextView(this@PatternsActivity).apply {
                    text = buildString {
                        append("$count pattern(s)")
                        if (isExcluded) append("  •  BLOCKED")
                        append("  •  ${if (isExpanded) "▲" else "▼"}")
                    }
                    textSize = 12f
                    setTextColor(if (isExcluded) 0xFFE53935.toInt() else 0xFF888888.toInt())
                })
                outer.addView(info)

                // Block/Allow button
                outer.addView(Button(this@PatternsActivity).apply {
                    text = if (isExcluded) "Allow" else "Block"
                    textSize = 12f
                    setPadding(16, 4, 16, 4)
                    setOnClickListener {
                        if (isExcluded) {
                            SafetyChecker.removeExcludedApp(this@PatternsActivity, app)
                        } else {
                            SafetyChecker.addExcludedApp(this@PatternsActivity, app)
                        }
                        refreshList()
                    }
                })

                return outer
            }

            override fun getChildView(
                groupPos: Int, childPos: Int, isLastChild: Boolean,
                convertView: View?, parent: ViewGroup?
            ): View {
                val p = getChild(groupPos, childPos)

                val row = LinearLayout(this@PatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(64, 8, 16, 8)
                }

                // Detail column
                val detail = LinearLayout(this@PatternsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }

                // Action text
                detail.addView(TextView(this@PatternsActivity).apply {
                    text = p.actionText
                    textSize = 15f
                })

                // Action type badge + count
                val metaRow = LinearLayout(this@PatternsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 0)
                }
                metaRow.addView(TextView(this@PatternsActivity).apply {
                    text = " ${p.actionType} "
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(actionTypeColor(p.actionType))
                    setPadding(12, 2, 12, 2)
                })
                metaRow.addView(TextView(this@PatternsActivity).apply {
                    text = "  seen ${p.count}×"
                    textSize = 12f
                    setTextColor(0xFF666666.toInt())
                })
                detail.addView(metaRow)

                // Last seen
                detail.addView(TextView(this@PatternsActivity).apply {
                    text = "Last: ${dateFmt.format(Date(p.lastSeen))}"
                    textSize = 11f
                    setTextColor(0xFFAAAAAA.toInt())
                    setPadding(0, 2, 0, 0)
                })

                row.addView(detail)

                // Delete button
                row.addView(Button(this@PatternsActivity).apply {
                    text = "✕"
                    textSize = 13f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener { deletePattern(p) }
                })

                return row
            }
        })

        // Expand all groups by default
        for (i in appNames.indices) {
            expandableList.expandGroup(i)
        }
    }

    /** Turn "com.example.myapp" into "myapp" for cleaner display. */
    private fun simplifyPackageName(pkg: String): String {
        val last = pkg.substringAfterLast('.')
        return if (last.isNotEmpty() && last != pkg) "$last  ($pkg)" else pkg
    }

    /** Color-code action type badges. */
    private fun actionTypeColor(type: String): Int {
        return when (type.uppercase()) {
            "CLICK" -> 0xFF4CAF50.toInt()   // green
            "SCROLL" -> 0xFF2196F3.toInt()   // blue
            "TYPE" -> 0xFFFF9800.toInt()      // orange
            "SWIPE" -> 0xFF9C27B0.toInt()     // purple
            else -> 0xFF757575.toInt()         // grey
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
