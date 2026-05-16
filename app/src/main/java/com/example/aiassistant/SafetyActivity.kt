package com.example.aiassistant

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SafetyActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var listView: ListView
    private var observedApps = listOf<String>()
    private var excludedApps = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Safety Settings"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "Toggle apps the assistant should NOT interact with."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // Manual add
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val pkgInput = EditText(this).apply {
            hint = "com.example.app"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val addButton = Button(this).apply {
            text = "Exclude"
            setOnClickListener {
                val pkg = pkgInput.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    SafetyChecker.addExcludedApp(this@SafetyActivity, pkg)
                    pkgInput.text.clear()
                    loadData()
                }
            }
        }

        addRow.addView(pkgInput)
        addRow.addView(addButton)
        root.addView(addRow)

        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadData() {
        excludedApps = SafetyChecker.getExcludedApps(this).toMutableSet()

        scope.launch {
            // Use a targeted DISTINCT query instead of loading all log rows.
            val pkgs = withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@SafetyActivity).logDao().getDistinctPackages()
            }

            // Merge with excluded apps that may not appear in logs yet.
            val allApps = (pkgs + excludedApps).distinct().sorted()
            observedApps = allApps
            refreshList()
        }
    }

    private fun refreshList() {
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = observedApps.size
            override fun getItem(pos: Int) = observedApps[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val pkg = observedApps[pos]
                val row = LinearLayout(this@SafetyActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 12, 8, 12)
                    gravity = Gravity.CENTER_VERTICAL
                }

                row.addView(TextView(this@SafetyActivity).apply {
                    text = pkg
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                val isExcluded = pkg in excludedApps
                row.addView(Button(this@SafetyActivity).apply {
                    text = if (isExcluded) "Allow" else "Block"
                    setOnClickListener {
                        if (isExcluded) {
                            SafetyChecker.removeExcludedApp(this@SafetyActivity, pkg)
                        } else {
                            SafetyChecker.addExcludedApp(this@SafetyActivity, pkg)
                        }
                        loadData()
                    }
                })

                return row
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
