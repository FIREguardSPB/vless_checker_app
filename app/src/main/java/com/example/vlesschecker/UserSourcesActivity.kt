package com.example.vlesschecker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vlesschecker.databinding.ActivityUserSourcesBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton

class UserSourcesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserSourcesBinding
    private lateinit var adapter: UserSourcesAdapter
    private var sources = mutableListOf<UserSource>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Пользовательские источники"

        adapter = UserSourcesAdapter(
            sources = sources,
            onEdit = { source ->
                openEditDialog(source)
            },
            onDelete = { source ->
                UserSourceManager.delete(this, source.id)
                loadSources()
            },
            onToggle = { source ->
                source.isEnabled = !source.isEnabled
                UserSourceManager.update(this, source)
                adapter.notifyDataSetChanged()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fab.setOnClickListener {
            openEditDialog(null)
        }

        loadSources()
    }

    private fun loadSources() {
        sources.clear()
        sources.addAll(UserSourceManager.getAll(this))
        adapter.notifyDataSetChanged()
        
        if (sources.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun openEditDialog(source: UserSource?) {
        val dialog = UserSourceDialogFragment.newInstance(source?.id)
        dialog.setOnSavedListener { updatedSource ->
            if (source == null) {
                UserSourceManager.add(this, updatedSource)
            } else {
                UserSourceManager.update(this, updatedSource)
            }
            loadSources()
        }
        dialog.show(supportFragmentManager, "UserSourceDialog")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class UserSourcesAdapter(
        private val sources: List<UserSource>,
        private val onEdit: (UserSource) -> Unit,
        private val onDelete: (UserSource) -> Unit,
        private val onToggle: (UserSource) -> Unit
    ) : RecyclerView.Adapter<UserSourcesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.sourceName)
            val urlText: TextView = view.findViewById(R.id.sourceUrl)
            val enabledText: TextView = view.findViewById(R.id.sourceEnabled)
            val editButton: ImageButton = view.findViewById(R.id.editButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
            val toggleButton: ImageButton = view.findViewById(R.id.toggleButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val source = sources[position]
            holder.nameText.text = if (source.name.isNotBlank()) source.name else "Без названия"
            holder.urlText.text = source.url.take(80)
            holder.enabledText.text = if (source.isEnabled) "Включен" else "Выключен"
            holder.enabledText.setTextColor(
                if (source.isEnabled) 
                    holder.itemView.context.getColor(android.R.color.holo_green_dark)
                else 
                    holder.itemView.context.getColor(android.R.color.holo_red_dark)
            )

            holder.editButton.setOnClickListener { onEdit(source) }
            holder.deleteButton.setOnClickListener { onDelete(source) }
            holder.toggleButton.setOnClickListener { onToggle(source) }
        }

        override fun getItemCount(): Int = sources.size
    }
}