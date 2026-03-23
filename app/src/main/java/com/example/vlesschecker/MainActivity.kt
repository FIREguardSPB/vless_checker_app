package com.example.vlesschecker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.vlesschecker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var intervalMinutes: List<Int>

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importLinksFromUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannel(this)
        requestNotificationPermissionIfNeeded()
        setupUi()
        loadInitialState()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setupUi() {
        intervalMinutes = resources.getStringArray(R.array.auto_check_intervals_values)
            .map { it.toInt() }

        val labels = resources.getStringArray(R.array.auto_check_intervals_labels)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.intervalSpinner.adapter = adapter

        binding.linksEditText.doAfterTextChanged {
            AppPrefs.setServerList(this, it?.toString().orEmpty())
        }

        binding.importButton.setOnClickListener {
            importFileLauncher.launch(arrayOf("*/*"))
        }

        binding.checkFirstButton.setOnClickListener {
            runFirstCheck()
        }

        binding.checkAllButton.setOnClickListener {
            runFullCheck()
        }

        binding.autoCheckSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setAutoCheckEnabled(this, isChecked)
            updateAutoCheckState(notify = true)
        }

        binding.deleteDeadSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setDeleteDeadOnFullScan(this, isChecked)
        }

        binding.intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val minutes = intervalMinutes[position]
                AppPrefs.setAutoCheckIntervalMinutes(this@MainActivity, minutes)
                if (binding.autoCheckSwitch.isChecked) {
                    updateAutoCheckState(notify = false)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadInitialState() {
        val initialText = AppPrefs.getServerList(this).ifBlank { loadLinksFromAssets() }
        if (AppPrefs.getServerList(this).isBlank()) {
            AppPrefs.setServerList(this, initialText)
        }
        binding.linksEditText.setText(initialText)
        binding.deleteDeadSwitch.isChecked = AppPrefs.isDeleteDeadOnFullScan(this)
        binding.autoCheckSwitch.isChecked = AppPrefs.isAutoCheckEnabled(this)

        val savedInterval = AppPrefs.getAutoCheckIntervalMinutes(this)
        val selectedIndex = intervalMinutes.indexOf(savedInterval).takeIf { it >= 0 } ?: 0
        binding.intervalSpinner.setSelection(selectedIndex)
        updateIntervalHint(savedInterval)

        binding.statusText.text = getString(R.string.ready_status)
        binding.resultText.text = ""

        if (binding.autoCheckSwitch.isChecked) {
            updateAutoCheckState(notify = false)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val link = intent?.getStringExtra(NotificationHelper.EXTRA_FOUND_LINK).orEmpty()
        if (link.isBlank()) return
        ClipboardHelper.copyLink(this, link)
        binding.resultText.text = buildString {
            appendLine(getString(R.string.last_found_from_notification))
            appendLine()
            append(link)
        }
        intent?.removeExtra(NotificationHelper.EXTRA_FOUND_LINK)
        Toast.makeText(this, R.string.copied_from_notification, Toast.LENGTH_SHORT).show()
    }

    private fun runFirstCheck() {
        val rawText = binding.linksEditText.text?.toString().orEmpty()
        AppPrefs.setServerList(this, rawText)
        val links = VlessChecker.normalizeLines(rawText)

        if (links.isEmpty()) {
            binding.statusText.text = getString(R.string.empty_list)
            binding.resultText.text = ""
            return
        }

        setBusy(true)
        binding.statusText.text = getString(R.string.checking_links_count, links.size)
        binding.resultText.text = ""

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VlessChecker.findFirstAvailable(links) { checked, total, current ->
                    runOnUiThread {
                        binding.statusText.text = getString(
                            R.string.check_progress,
                            checked,
                            total,
                            current.take(80)
                        )
                    }
                }
            }

            setBusy(false)

            if (result == null) {
                binding.statusText.text = getString(R.string.no_working_found)
                binding.resultText.text = getString(R.string.no_link_passed)
                Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
            } else {
                ClipboardHelper.copyLink(this@MainActivity, result.link)
                NotificationHelper.showFoundLinkNotification(
                    context = this@MainActivity,
                    link = result.link,
                    title = getString(R.string.notification_title_manual),
                    text = NotificationHelper.shorten(result.link)
                )
                binding.statusText.text = getString(R.string.first_working_found)
                binding.resultText.text = buildString {
                    appendLine(getString(R.string.check_type_label, result.checkType))
                    appendLine(getString(R.string.host_label, result.host, result.port))
                    appendLine()
                    append(result.link)
                }
                Toast.makeText(this@MainActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runFullCheck() {
        val rawText = binding.linksEditText.text?.toString().orEmpty()
        AppPrefs.setServerList(this, rawText)
        val links = VlessChecker.normalizeLines(rawText)

        if (links.isEmpty()) {
            binding.statusText.text = getString(R.string.empty_list)
            binding.resultText.text = ""
            return
        }

        setBusy(true)
        binding.statusText.text = getString(R.string.full_check_started, links.size)
        binding.resultText.text = ""

        lifecycleScope.launch {
            val batch = withContext(Dispatchers.IO) {
                VlessChecker.checkAll(links) { checked, total, current ->
                    runOnUiThread {
                        binding.statusText.text = getString(
                            R.string.check_progress,
                            checked,
                            total,
                            current.take(80)
                        )
                    }
                }
            }

            setBusy(false)

            val workingLinks = batch.working.map { it.link }
            if (workingLinks.isNotEmpty()) {
                val first = batch.working.first()
                ClipboardHelper.copyLink(this@MainActivity, first.link)
                NotificationHelper.showFoundLinkNotification(
                    context = this@MainActivity,
                    link = first.link,
                    title = getString(R.string.notification_title_all, batch.working.size),
                    text = NotificationHelper.shorten(first.link)
                )
            }

            if (binding.deleteDeadSwitch.isChecked) {
                val newText = workingLinks.joinToString("\n")
                binding.linksEditText.setText(newText)
                AppPrefs.setServerList(this@MainActivity, newText)
            }

            binding.statusText.text = getString(
                R.string.full_check_done,
                batch.working.size,
                batch.failed.size,
                batch.skipped.size
            )
            binding.resultText.text = buildString {
                appendLine(getString(R.string.working_count, batch.working.size))
                appendLine(getString(R.string.failed_count, batch.failed.size))
                appendLine(getString(R.string.skipped_count, batch.skipped.size))
                appendLine()
                if (batch.working.isEmpty()) {
                    append(getString(R.string.no_working_found))
                } else {
                    appendLine(getString(R.string.working_links_title))
                    batch.working.forEachIndexed { index, item ->
                        appendLine()
                        appendLine("${index + 1}. ${item.host}:${item.port} — ${item.checkType}")
                        appendLine(item.link)
                    }
                }
            }

            if (workingLinks.isNotEmpty()) {
                val toastText = if (binding.deleteDeadSwitch.isChecked) {
                    getString(R.string.full_check_done_and_cleaned)
                } else {
                    getString(R.string.first_working_copied)
                }
                Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importLinksFromUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Not all providers grant persistable permissions. Ignore.
        }

        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            binding.statusText.text = getString(R.string.import_failed)
            Toast.makeText(this, e.message ?: getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            return
        }

        binding.linksEditText.setText(text)
        AppPrefs.setServerList(this, text)
        binding.statusText.text = getString(R.string.import_success)
        binding.resultText.text = getString(R.string.imported_lines_count, VlessChecker.normalizeLines(text).size)
        Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoCheckState(notify: Boolean) {
        val interval = AppPrefs.getAutoCheckIntervalMinutes(this)
        updateIntervalHint(interval)
        if (binding.autoCheckSwitch.isChecked) {
            val rawText = binding.linksEditText.text?.toString().orEmpty()
            AppPrefs.setServerList(this, rawText)
            AutoCheckScheduler.schedule(this, interval)
            if (notify) {
                Toast.makeText(this, getString(R.string.auto_check_enabled_toast, interval), Toast.LENGTH_SHORT).show()
            }
        } else {
            AutoCheckScheduler.cancel(this)
            if (notify) {
                Toast.makeText(this, R.string.auto_check_disabled_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateIntervalHint(interval: Int) {
        binding.autoCheckHint.text = getString(R.string.auto_check_hint, interval)
    }

    private fun setBusy(isBusy: Boolean) {
        binding.checkFirstButton.isEnabled = !isBusy
        binding.checkAllButton.isEnabled = !isBusy
        binding.importButton.isEnabled = !isBusy
        binding.progressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadLinksFromAssets(): String {
        return try {
            assets.open("servers.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }
}
