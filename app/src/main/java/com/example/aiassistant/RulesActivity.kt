package com.example.aiassistant

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class RulesActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var listView: ListView
    private var rules = mutableListOf<RuleEntity>()

    private data class RowViews(
        val ruleText: TextView,
        val toggleButton: Button,
        val deleteButton: Button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Manage Rules"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // Add rule form
        val formRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val keywordInput = EditText(this).apply {
            hint = "Keyword"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RulesActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("CLICK", "SCROLL_FORWARD", "SCROLL_BACKWARD", "SWIPE", "TYPE")
            )
        }

        val addButton = Button(this).apply {
            text = "Add"
            setOnClickListener {
                val keyword = keywordInput.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    val actionType = actionSpinner.selectedItem.toString()
                    addRule(keyword, actionType)
                    keywordInput.text.clear()
                }
            }
        }

        formRow.addView(keywordInput)
        formRow.addView(actionSpinner)
        formRow.addView(addButton)
        root.addView(formRow)

        // Rules list
        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadRules()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadRules() {
        scope.launch {
            val db = DatabaseHelper.getDB(this@RulesActivity)
            rules = withContext(Dispatchers.IO) { db.ruleDao().getAll().toMutableList() }
            refreshList()
        }
    }

    private fun addRule(keyword: String, actionType: String) {
        scope.launch {
            val rule = RuleEntity(keyword = keyword, actionType = actionType)
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@RulesActivity).ruleDao().insert(rule)
            }
            loadRules()
        }
    }

    private fun toggleRule(rule: RuleEntity) {
        scope.launch {
            val updated = rule.copy(enabled = !rule.enabled)
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@RulesActivity).ruleDao().update(updated)
            }
            loadRules()
        }
    }

    private fun deleteRule(rule: RuleEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                DatabaseHelper.getDB(this@RulesActivity).ruleDao().delete(rule)
            }
            loadRules()
        }
    }

    private fun refreshList() {
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = rules.size
            override fun getItem(pos: Int) = rules[pos]
            override fun getItemId(pos: Int) = rules[pos].id.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val rule = rules[pos]
                val row = if (convertView == null) {
                    val layout = LinearLayout(this@RulesActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(8, 12, 8, 12)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val label = TextView(this@RulesActivity).apply {
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }
                    val toggle = Button(this@RulesActivity)
                    val delete = Button(this@RulesActivity).apply { text = "X" }
                    layout.addView(label)
                    layout.addView(toggle)
                    layout.addView(delete)
                    layout.tag = RowViews(label, toggle, delete)
                    layout
                } else {
                    convertView
                }
                val views = row.tag as RowViews
                views.ruleText.text = "${rule.keyword}  →  ${rule.actionType}"
                views.ruleText.alpha = if (rule.enabled) 1f else 0.4f
                views.toggleButton.text = if (rule.enabled) "ON" else "OFF"
                views.toggleButton.setOnClickListener { toggleRule(rule) }
                views.deleteButton.setOnClickListener { deleteRule(rule) }
                return row
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
