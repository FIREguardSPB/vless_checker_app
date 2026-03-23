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
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var intervalMinutes: List<Int>
    private lateinit var sourceItems: List<ListSource>
    private var isProgrammaticTextChange = false

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

        sourceItems = listOf(
            ListSource.LOCAL_MANUAL,
            ListSource.REMOTE_AVAILABLE,
            ListSource.REMOTE_WHITE
        )

        val intervalLabels = resources.getStringArray(R.array.auto_check_intervals_labels)
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.intervalSpinner.adapter = intervalAdapter

        val sourceLabels = resources.getStringArray(R.array.source_labels)
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sourceLabels)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sourceSpinner.adapter = sourceAdapter

        binding.linksEditText.doAfterTextChanged {
            if (isProgrammaticTextChange) return@doAfterTextChanged
            if (currentSource() == ListSource.LOCAL_MANUAL) {
                AppPrefs.setServerList(this, it?.toString().orEmpty())
            }
        }

        binding.importButton.setOnClickListener {
            importFileLauncher.launch(arrayOf("*/*"))
        }

        binding.refreshRemoteButton.setOnClickListener {
            refreshSelectedRemoteSource(showToast = true)
        }

        binding.checkFirstButton.setOnClickListener {
            runFastestCheck()
        }

        binding.checkAllButton.setOnClickListener {
            runFullCheck()
        }

        binding.handoffButton.setOnClickListener {
            handoffLastFastestLink()
        }

        binding.vpnSettingsButton.setOnClickListener {
            val opened = ConfigHandoffHelper.openVpnSettings(this)
            if (!opened) {
                Toast.makeText(this, R.string.vpn_settings_unavailable, Toast.LENGTH_SHORT).show()
            }
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

        binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = sourceItems[position]
                AppPrefs.setSelectedSource(this@MainActivity, selected)
                updateSourceUi(selected)
                if (selected.isRemote) {
                    refreshSelectedRemoteSource(showToast = false)
                } else {
                    loadLocalListIntoEditor()
                }
                if (binding.autoCheckSwitch.isChecked) {
                    updateAutoCheckState(notify = false)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadInitialState() {
        val localText = AppPrefs.getServerList(this).ifBlank { loadLinksFromAssets() }
        if (AppPrefs.getServerList(this).isBlank()) {
            AppPrefs.setServerList(this, localText)
        }

        binding.deleteDeadSwitch.isChecked = AppPrefs.isDeleteDeadOnFullScan(this)
        binding.autoCheckSwitch.isChecked = AppPrefs.isAutoCheckEnabled(this)

        val savedInterval = AppPrefs.getAutoCheckIntervalMinutes(this)
        val selectedIntervalIndex = intervalMinutes.indexOf(savedInterval).takeIf { it >= 0 } ?: 0
        binding.intervalSpinner.setSelection(selectedIntervalIndex)
        updateIntervalHint(savedInterval)

        val selectedSource = currentSource()
        val sourceIndex = sourceItems.indexOf(selectedSource).takeIf { it >= 0 } ?: 0
        binding.sourceSpinner.setSelection(sourceIndex)
        updateSourceUi(selectedSource)
        if (!selectedSource.isRemote) {
            setEditorText(localText)
        }

        binding.statusText.text = getString(R.string.ready_status)
        binding.resultText.text = ""
        updateLastFastestHint()

        if (selectedSource.isRemote) {
            refreshSelectedRemoteSource(showToast = false)
        }

        if (binding.autoCheckSwitch.isChecked) {
            updateAutoCheckState(notify = false)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val link = intent?.getStringExtra(NotificationHelper.EXTRA_FOUND_LINK).orEmpty()
        if (link.isBlank()) return
        ClipboardHelper.copyLink(this, link)
        AppPrefs.setLastFoundFastestLink(this, link)
        updateLastFastestHint()
        binding.resultText.text = buildString {
            appendLine(getString(R.string.last_found_from_notification))
            appendLine()
            append(link)
        }
        intent?.removeExtra(NotificationHelper.EXTRA_FOUND_LINK)
        Toast.makeText(this, R.string.copied_from_notification, Toast.LENGTH_SHORT).show()
    }

    private fun runFastestCheck() {
        setBusy(true)
        binding.statusText.text = getString(R.string.preparing_source)
        binding.resultText.text = ""

        lifecycleScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { resolveCurrentSourceText(forceRefreshRemote = true) }
                val links = VlessChecker.normalizeLines(resolved.text)
                updateEditorFromResolvedSource(resolved.source, resolved.text)

                if (links.isEmpty()) {
                    setBusy(false)
                    binding.statusText.text = getString(R.string.empty_list)
                    binding.resultText.text = ""
                    maybeShowWarning(resolved.warning)
                    return@launch
                }

                binding.statusText.text = getString(R.string.checking_links_count, links.size)
                maybeShowWarning(resolved.warning)

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

                val fastest = batch.working.firstOrNull()
                if (fastest == null) {
                    binding.statusText.text = getString(R.string.no_working_found)
                    binding.resultText.text = buildDetailedList(batch)
                    Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
                } else {
                    rememberAndNotifyFastest(fastest, getString(R.string.notification_title_fastest))
                    binding.statusText.text = getString(
                        R.string.fastest_working_found_with_source,
                        sourceDisplayName(resolved.source)
                    )
                    binding.resultText.text = buildString {
                        appendLine(getString(R.string.fastest_summary_title))
                        appendLine(getString(R.string.source_label, sourceDisplayName(resolved.source)))
                        appendLine(getString(R.string.latency_label, fastest.latencyMs))
                        appendLine(getString(R.string.check_type_label, fastest.checkType))
                        appendLine(getString(R.string.host_label, fastest.host, fastest.port))
                        appendLine()
                        appendLine(fastest.link)
                        appendLine()
                        append(buildDetailedList(batch))
                    }
                    Toast.makeText(this@MainActivity, R.string.fastest_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                setBusy(false)
                binding.statusText.text = getString(R.string.remote_load_failed)
                binding.resultText.text = e.message.orEmpty()
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.remote_load_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runFullCheck() {
        setBusy(true)
        binding.statusText.text = getString(R.string.preparing_source)
        binding.resultText.text = ""

        lifecycleScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { resolveCurrentSourceText(forceRefreshRemote = true) }
                val links = VlessChecker.normalizeLines(resolved.text)
                updateEditorFromResolvedSource(resolved.source, resolved.text)

                if (links.isEmpty()) {
                    setBusy(false)
                    binding.statusText.text = getString(R.string.empty_list)
                    binding.resultText.text = ""
                    maybeShowWarning(resolved.warning)
                    return@launch
                }

                binding.statusText.text = getString(R.string.full_check_started, links.size)
                maybeShowWarning(resolved.warning)

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
                val fastest = batch.working.firstOrNull()
                if (fastest != null) {
                    rememberAndNotifyFastest(
                        fastest = fastest,
                        title = getString(R.string.notification_title_all, batch.working.size)
                    )
                }

                if (binding.deleteDeadSwitch.isChecked) {
                    val newText = workingLinks.joinToString("\n")
                    when (resolved.source) {
                        ListSource.LOCAL_MANUAL -> {
                            setEditorText(newText)
                            AppPrefs.setServerList(this@MainActivity, newText)
                        }
                        else -> {
                            AppPrefs.setRemoteCache(this@MainActivity, resolved.source, newText)
                            AppPrefs.setRemoteCacheTimestamp(this@MainActivity, resolved.source, System.currentTimeMillis())
                            updateEditorFromResolvedSource(resolved.source, newText)
                        }
                    }
                }

                binding.statusText.text = getString(
                    R.string.full_check_done,
                    batch.working.size,
                    batch.failed.size,
                    batch.skipped.size
                )
                binding.resultText.text = buildString {
                    appendLine(getString(R.string.source_label, sourceDisplayName(resolved.source)))
                    if (fastest != null) {
                        appendLine(getString(R.string.fastest_summary_title))
                        appendLine(getString(R.string.latency_label, fastest.latencyMs))
                        appendLine(getString(R.string.host_label, fastest.host, fastest.port))
                        appendLine()
                    }
                    append(buildDetailedList(batch))
                }

                if (workingLinks.isNotEmpty()) {
                    val toastText = if (binding.deleteDeadSwitch.isChecked) {
                        getString(R.string.full_check_done_and_cleaned)
                    } else {
                        getString(R.string.fastest_copied_to_clipboard)
                    }
                    Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                setBusy(false)
                binding.statusText.text = getString(R.string.remote_load_failed)
                binding.resultText.text = e.message.orEmpty()
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.remote_load_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildDetailedList(batch: BatchCheckResult): String {
        return buildString {
            appendLine(getString(R.string.working_count, batch.working.size))
            appendLine(getString(R.string.failed_count, batch.failed.size))
            appendLine(getString(R.string.skipped_count, batch.skipped.size))
            appendLine()
            appendLine(getString(R.string.all_results_title))

            batch.checked.forEachIndexed { index, item ->
                appendLine()
                val endpoint = if (item.host != null && item.port != null) {
                    "${item.host}:${item.port}"
                } else {
                    getString(R.string.unknown_endpoint)
                }
                appendLine(
                    getString(
                        R.string.result_line_format,
                        index + 1,
                        if (item.isWorking) "✅" else if (item.host == null) "⚠️" else "❌",
                        item.statusText,
                        endpoint,
                        item.checkType
                    )
                )
                appendLine(item.link)
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

        AppPrefs.setSelectedSource(this, ListSource.LOCAL_MANUAL)
        binding.sourceSpinner.setSelection(sourceItems.indexOf(ListSource.LOCAL_MANUAL))
        setEditorText(text)
        AppPrefs.setServerList(this, text)
        binding.statusText.text = getString(R.string.import_success)
        binding.resultText.text = getString(R.string.imported_lines_count, VlessChecker.normalizeLines(text).size)
        Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
    }

    private fun refreshSelectedRemoteSource(showToast: Boolean) {
        val source = currentSource()
        if (!source.isRemote) {
            if (showToast) {
                Toast.makeText(this, R.string.remote_refresh_only_for_remote, Toast.LENGTH_SHORT).show()
            }
            return
        }

        binding.statusText.text = getString(R.string.remote_loading_started, sourceDisplayName(source))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RemoteListRepository.loadForSource(
                        context = this@MainActivity,
                        source = source,
                        forceRefresh = true
                    )
                }
                updateEditorFromResolvedSource(source, result.text)
                val message = if (result.usedCache) {
                    getString(R.string.remote_loaded_cache, sourceDisplayName(source))
                } else {
                    getString(R.string.remote_loaded_fresh, sourceDisplayName(source))
                }
                binding.statusText.text = message
                binding.resultText.text = getString(
                    R.string.imported_lines_count,
                    VlessChecker.normalizeLines(result.text).size
                )
                updateSourceUi(source)
                if (showToast) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
                maybeShowWarning(result.warning)
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.remote_load_failed)
                binding.resultText.text = e.message.orEmpty()
                if (showToast) {
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.remote_load_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun resolveCurrentSourceText(forceRefreshRemote: Boolean): ResolvedSourceText {
        val source = currentSource()
        return when (source) {
            ListSource.LOCAL_MANUAL -> {
                val text = binding.linksEditText.text?.toString().orEmpty()
                AppPrefs.setServerList(this, text)
                ResolvedSourceText(source = source, text = text, warning = null)
            }
            else -> {
                val loaded = RemoteListRepository.loadForSource(
                    context = this@MainActivity,
                    source = source,
                    forceRefresh = forceRefreshRemote
                )
                ResolvedSourceText(source = source, text = loaded.text, warning = loaded.warning)
            }
        }
    }

    private fun rememberAndNotifyFastest(fastest: CheckResult, title: String) {
        ClipboardHelper.copyLink(this, fastest.link)
        AppPrefs.setLastFoundFastestLink(this, fastest.link)
        updateLastFastestHint()
        NotificationHelper.showFoundLinkNotification(
            context = this,
            link = fastest.link,
            title = title,
            text = getString(
                R.string.notification_fastest_text,
                fastest.latencyMs,
                NotificationHelper.shorten(fastest.link, 56)
            )
        )
    }

    private fun handoffLastFastestLink() {
        val link = AppPrefs.getLastFoundFastestLink(this)
        if (link.isBlank()) {
            Toast.makeText(this, R.string.no_saved_fastest_for_handoff, Toast.LENGTH_SHORT).show()
            return
        }
        val handedOff = ConfigHandoffHelper.handoffToCompatibleApp(this, link)
        if (handedOff) {
            Toast.makeText(this, R.string.handoff_started, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.handoff_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoCheckState(notify: Boolean) {
        val interval = AppPrefs.getAutoCheckIntervalMinutes(this)
        updateIntervalHint(interval)
        if (binding.autoCheckSwitch.isChecked) {
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

    private fun updateSourceUi(source: ListSource) {
        val isLocal = source == ListSource.LOCAL_MANUAL
        binding.importButton.isEnabled = isLocal
        binding.refreshRemoteButton.isEnabled = source.isRemote
        binding.linksEditText.isEnabled = true
        binding.linksEditText.isFocusable = isLocal
        binding.linksEditText.isFocusableInTouchMode = isLocal
        binding.linksEditText.isCursorVisible = isLocal
        binding.linksEditText.isLongClickable = true

        val sourceInfo = when (source) {
            ListSource.LOCAL_MANUAL -> getString(R.string.source_info_local)
            else -> {
                val ts = AppPrefs.getRemoteCacheTimestamp(this, source)
                if (ts > 0L) {
                    val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(ts))
                    getString(R.string.source_info_remote_with_time, sourceDisplayName(source), formatted)
                } else {
                    getString(R.string.source_info_remote_without_time, sourceDisplayName(source))
                }
            }
        }
        binding.sourceInfoText.text = sourceInfo
    }

    private fun updateLastFastestHint() {
        val last = AppPrefs.getLastFoundFastestLink(this)
        binding.lastFastestText.text = if (last.isBlank()) {
            getString(R.string.last_fastest_empty)
        } else {
            getString(R.string.last_fastest_value, NotificationHelper.shorten(last, 88))
        }
    }

    private fun loadLocalListIntoEditor() {
        val text = AppPrefs.getServerList(this).ifBlank { loadLinksFromAssets() }
        if (AppPrefs.getServerList(this).isBlank()) {
            AppPrefs.setServerList(this, text)
        }
        setEditorText(text)
    }

    private fun updateEditorFromResolvedSource(source: ListSource, text: String) {
        if (currentSource() == source) {
            setEditorText(text)
            updateSourceUi(source)
        }
    }

    private fun setEditorText(text: String) {
        isProgrammaticTextChange = true
        binding.linksEditText.setText(text)
        isProgrammaticTextChange = false
    }

    private fun maybeShowWarning(warning: String?) {
        if (!warning.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.remote_warning_using_cache, warning), Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.checkFirstButton.isEnabled = !isBusy
        binding.checkAllButton.isEnabled = !isBusy
        binding.importButton.isEnabled = !isBusy && currentSource() == ListSource.LOCAL_MANUAL
        binding.refreshRemoteButton.isEnabled = !isBusy && currentSource().isRemote
        binding.handoffButton.isEnabled = !isBusy
        binding.vpnSettingsButton.isEnabled = !isBusy
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

    private fun sourceDisplayName(source: ListSource): String {
        return when (source) {
            ListSource.LOCAL_MANUAL -> getString(R.string.source_local_manual)
            ListSource.REMOTE_AVAILABLE -> getString(R.string.source_remote_available)
            ListSource.REMOTE_WHITE -> getString(R.string.source_remote_white)
        }
    }

    private fun currentSource(): ListSource {
        return ListSource.fromPref(AppPrefs.getSelectedSource(this))
    }

    private data class ResolvedSourceText(
        val source: ListSource,
        val text: String,
        val warning: String?
    )
}
