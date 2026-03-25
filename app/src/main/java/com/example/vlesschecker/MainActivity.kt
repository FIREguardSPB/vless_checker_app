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
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.vlesschecker.databinding.ActivityMainBinding
import com.example.vlesschecker.databinding.ItemCheckedResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import android.provider.DocumentsContract

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var intervalMinutes: List<Int>
    private var latestWorkingResults: List<LinkCheckResult> = emptyList()
    private var visibleWorkingResults: List<LinkCheckResult> = emptyList()
    private var sourceItems: List<ListSource> = emptyList()
        get() = if (field.isEmpty()) ListSource.getStaticSources() else field

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

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                saveToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize VlessChecker with xray-core enabled
        VlessChecker.init(this)
        VlessChecker.useXray = true
        Toast.makeText(this, "Проверка через Xray-core включена", Toast.LENGTH_LONG).show()

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

        val intervalLabels = resources.getStringArray(R.array.auto_check_intervals_labels)
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.intervalSpinner.adapter = intervalAdapter

        val maxConfigsLabels = resources.getStringArray(R.array.max_working_configs_labels)
        val maxConfigsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, maxConfigsLabels)
        maxConfigsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.maxConfigsSpinner.adapter = maxConfigsAdapter
        
        binding.maxConfigsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val values = resources.getStringArray(R.array.max_working_configs_values)
                val selectedValue = values[position].toIntOrNull() ?: 10
                PersistentWorkingConfigsManager.setMaxConfigs(this@MainActivity, selectedValue)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val maxLatencyLabels = resources.getStringArray(R.array.max_latency_labels)
        val maxLatencyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, maxLatencyLabels)
        maxLatencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.maxLatencySpinner.adapter = maxLatencyAdapter
        
        binding.maxLatencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val values = resources.getStringArray(R.array.max_latency_values)
                val selectedValue = values[position].toLongOrNull() ?: 1000L
                AppPrefs.setMaxLatencyMs(this@MainActivity, selectedValue)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        updateSourceItems()

        binding.linksEditText.doAfterTextChanged {
            if (currentSelectedSource() == ListSource.Manual) {
                val value = it?.toString().orEmpty()
                AppPrefs.setServerList(this, value)
                ConfigFileStore.saveCurrentSnapshot(this, ListSource.Manual, value)
            }
        }

        binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val source = sourceItems[position]
                AppPrefs.setSelectedSource(this@MainActivity, source)
                if (source is ListSource.UserDefined) {
                    if (source.url.isBlank()) {
                        // Это не должно происходить, но на всякий случай
                        Toast.makeText(this@MainActivity, "URL источника пуст", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                showSourcePreview(source)
                renderCheckedResults(emptyList())
                if (binding.autoCheckSwitch.isChecked) {
                    updateAutoCheckState(notify = false)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.sourceSpinner.setOnLongClickListener {
            // Открываем управление источниками
            val intent = Intent(this, UserSourcesActivity::class.java)
            startActivity(intent)
            true
        }

        binding.manageSourcesButton.setOnClickListener {
            val intent = Intent(this, UserSourcesActivity::class.java)
            startActivity(intent)
        }

        binding.refreshSourceButton.setOnClickListener {
            refreshSelectedSource(showToast = true)
        }

        binding.importButton.setOnClickListener {
            importFileLauncher.launch(arrayOf("*/*"))
        }

        binding.shareCurrentListFileButton.setOnClickListener {
            shareCurrentListFile()
        }

        binding.shareWorkingListFileButton.setOnClickListener {
            shareWorkingListFile()
        }

        binding.checkFirstButton.setOnClickListener {
            runFastestCheck()
        }

        binding.checkAllButton.setOnClickListener {
            runFullCheck()
        }

        binding.saveDisplayedListFileButton.setOnClickListener {
            saveDisplayedListFile()
        }

        binding.saveAsButton.setOnClickListener {
            saveAsToUserSelectedLocation()
        }

        binding.copyAllButton.setOnClickListener {
            copyAllDisplayedToClipboard()
        }

        binding.importFastestVpnButton.setOnClickListener {
            importFastestIntoVpnClient()
        }

        binding.openVpnSettingsButton.setOnClickListener {
            VpnImportHelper.openVpnSettings(this)
        }

        binding.startVpnButton.setOnClickListener {
            val ok = VpnImportHelper.controlV2RayTun(this, VpnImportHelper.ControlAction.START)
            Toast.makeText(
                this,
                if (ok) R.string.v2raytun_start_sent else R.string.v2raytun_control_missing,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.stopVpnButton.setOnClickListener {
            val ok = VpnImportHelper.controlV2RayTun(this, VpnImportHelper.ControlAction.STOP)
            Toast.makeText(
                this,
                if (ok) R.string.v2raytun_stop_sent else R.string.v2raytun_control_missing,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.restartVpnButton.setOnClickListener {
            val ok = VpnImportHelper.controlV2RayTun(this, VpnImportHelper.ControlAction.RESTART)
            Toast.makeText(
                this,
                if (ok) R.string.v2raytun_restart_sent else R.string.v2raytun_control_missing,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.autoCheckSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setAutoCheckEnabled(this, isChecked)
            updateAutoCheckState(notify = true)
        }

        binding.deleteDeadSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setDeleteDeadOnFullScan(this, isChecked)
        }

        binding.hideCandidatesSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setHideCandidates(this, isChecked)
            renderCheckedResults(latestWorkingResults)
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
        val manualText = AppPrefs.getServerList(this).ifBlank { loadLinksFromAssets() }
        if (AppPrefs.getServerList(this).isBlank()) {
            AppPrefs.setServerList(this, manualText)
        }
        ConfigFileStore.saveCurrentSnapshot(this, ListSource.Manual, manualText)

        binding.deleteDeadSwitch.isChecked = AppPrefs.isDeleteDeadOnFullScan(this)
        binding.hideCandidatesSwitch.isChecked = AppPrefs.isHideCandidates(this)
        binding.autoCheckSwitch.isChecked = AppPrefs.isAutoCheckEnabled(this)

        val savedInterval = AppPrefs.getAutoCheckIntervalMinutes(this)
        val selectedIntervalIndex = intervalMinutes.indexOf(savedInterval).takeIf { it >= 0 } ?: 0
        binding.intervalSpinner.setSelection(selectedIntervalIndex)
        updateIntervalHint(savedInterval)

        val maxConfigsValues = resources.getStringArray(R.array.max_working_configs_values).map { it.toInt() }
        val savedMaxConfigs = PersistentWorkingConfigsManager.getMaxConfigs(this)
        val selectedMaxConfigsIndex = maxConfigsValues.indexOf(savedMaxConfigs).takeIf { it >= 0 } ?: 1 // default 10
        binding.maxConfigsSpinner.setSelection(selectedMaxConfigsIndex)

        val maxLatencyValues = resources.getStringArray(R.array.max_latency_values).map { it.toLong() }
        val savedMaxLatency = AppPrefs.getMaxLatencyMs(this)
        val selectedMaxLatencyIndex = maxLatencyValues.indexOf(savedMaxLatency).takeIf { it >= 0 } ?: 2 // default 1000 ms (index 2)
        binding.maxLatencySpinner.setSelection(selectedMaxLatencyIndex)

        // Обновить список источников (включая пользовательские)
        updateSourceItems()

        val selectedSource = AppPrefs.getSelectedSource(this)
        val selectedSourceIndex = sourceItems.indexOf(selectedSource).takeIf { it >= 0 } ?: 0
        binding.sourceSpinner.setSelection(selectedSourceIndex)

        binding.statusText.text = getString(R.string.ready_status)
        binding.resultText.text = ""
        renderCheckedResults(emptyList())
        updateFastestActionsState(AppPrefs.getLastFastestLink(this))

        if (selectedSource != ListSource.Manual && AppPrefs.getRemoteCache(this, selectedSource).isBlank()) {
            refreshSelectedSource(showToast = false)
        }

        if (binding.autoCheckSwitch.isChecked) {
            updateAutoCheckState(notify = false)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val link = intent?.getStringExtra(NotificationHelper.EXTRA_FOUND_LINK).orEmpty()
        if (link.isBlank()) return
        val prepared = VpnImportHelper.prepareClipboardConfig(link)
        ClipboardHelper.copyLink(this, prepared)
        AppPrefs.setLastFastestLink(this, prepared)
        updateFastestActionsState(prepared)
        binding.resultText.text = buildString {
            appendLine(getString(R.string.last_found_from_notification))
            appendLine()
            append(prepared)
        }
        intent?.removeExtra(NotificationHelper.EXTRA_FOUND_LINK)
        Toast.makeText(this, R.string.copied_from_notification, Toast.LENGTH_SHORT).show()
    }

    private fun showSourcePreview(source: ListSource) {
        when (source) {
            ListSource.Manual -> {
                val text = AppPrefs.getServerList(this).ifBlank { loadLinksFromAssets() }
                updateLinksEditorText(text)
                binding.statusText.text = getString(R.string.source_selected_status, source.displayName(this))
                binding.resultText.text = getString(
                    R.string.manual_source_lines_count,
                    VlessChecker.normalizeLines(text).size
                )
            }
            else -> {
                val cached = AppPrefs.getRemoteCache(this, source)
                val previewText = cached.ifBlank {
                    getString(R.string.remote_source_placeholder, source.displayName(this))
                }
                updateLinksEditorText(previewText)
                binding.statusText.text = getString(R.string.source_selected_status, source.displayName(this))
                binding.resultText.text = if (cached.isBlank()) {
                    getString(R.string.remote_source_not_loaded_yet)
                } else {
                    getString(
                        R.string.remote_source_cached_lines_count,
                        VlessChecker.normalizeLines(cached).size,
                        source.displayName(this)
                    )
                }
            }
        }
    }

    private fun refreshSelectedSource(showToast: Boolean) {
        val source = currentSelectedSource()
        renderCheckedResults(emptyList())
        if (source == ListSource.Manual) {
            binding.statusText.text = getString(R.string.manual_source_no_refresh_needed)
            if (showToast) {
                Toast.makeText(this, R.string.manual_source_no_refresh_needed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        setBusy(true)
        binding.statusText.text = getString(R.string.loading_selected_source, source.displayName(this))
        binding.resultText.text = ""

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RemoteListRepository.loadForSource(
                        context = this@MainActivity,
                        source = source,
                        preferFreshRemote = true
                    )
                }
                updateLinksEditorText(result.rawText)
                ConfigFileStore.saveCurrentSnapshot(this@MainActivity, source, result.rawText)
                binding.statusText.text = getString(
                    R.string.remote_source_loaded_status,
                    result.sourceLabel,
                    result.links.size
                )
                binding.resultText.text = buildSourceInfoBlock(result)
                if (showToast) {
                    Toast.makeText(this@MainActivity, R.string.remote_source_loaded_toast, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.remote_source_failed)
                binding.resultText.text = e.message ?: getString(R.string.remote_source_failed)
                if (showToast) {
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: getString(R.string.remote_source_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runFastestCheck() {
        setBusy(true)
        renderCheckedResults(emptyList())
        binding.statusText.text = getString(
            R.string.preparing_source_for_check,
            currentSelectedSource().displayName(this)
        )
        binding.resultText.text = ""

        lifecycleScope.launch {
            try {
                val sourceResult = loadActiveSourceText(forceRemoteRefresh = currentSelectedSource() != ListSource.Manual)
                val links = sourceResult.links
                if (links.isEmpty()) {
                    binding.statusText.text = getString(R.string.empty_list)
                    binding.resultText.text = buildSourceInfoBlock(sourceResult)
                    updateFastestActionsState("")
                    return@launch
                }

                binding.statusText.text = getString(
                    R.string.checking_links_count_with_source,
                    links.size,
                    sourceResult.sourceLabel
                )

                val batch = withContext(Dispatchers.IO) {
                    VlessChecker.checkAll(links, { checked, total, current ->
                        runOnUiThread {
                            binding.statusText.text = getString(
                                R.string.check_progress,
                                checked,
                                total,
                                current.take(80)
                            )
                        }
                    }, sourceResult.configs)
                }
                
                // Update persistent storage with new working configs
                PersistentWorkingConfigsManager.updateWithNewResults(
                    context = this@MainActivity,
                    newResults = batch.checked,
                    source = sourceResult.source
                )

                renderCheckedResults(batch.checked.filter { it.isWorking })

                val fastest = batch.working.firstOrNull()
                if (fastest == null) {
                    AppPrefs.setLastFastestLink(this@MainActivity, "")
                    updateFastestActionsState("")
                    binding.statusText.text = getString(R.string.no_working_found)
                    binding.resultText.text = buildString {
                        appendLine(getString(R.string.source_label, sourceResult.sourceLabel))
                        appendLine(getString(R.string.no_link_passed_balanced))
                        appendLine(getString(R.string.working_count, batch.working.size))
                        appendLine(getString(R.string.confirmed_count, batch.confirmed.size))
                        appendLine(getString(R.string.candidate_count, batch.candidates.size))
                        appendLine(getString(R.string.failed_count, batch.failed.size))
                        appendLine(getString(R.string.skipped_count, batch.skipped.size))
                    }
                    Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
                } else {
                    onFastestChosen(fastest)
                    NotificationHelper.showFoundLinkNotification(
                        context = this@MainActivity,
                        link = fastest.link,
                        title = getString(R.string.notification_title_fastest),
                        text = getString(
                            R.string.notification_fastest_text,
                            fastest.latencyMs,
                            NotificationHelper.shorten(fastest.link, 56)
                        )
                    )
                    binding.statusText.text = getString(
                        R.string.fastest_working_found_with_source,
                        sourceResult.sourceLabel
                    )
                    binding.resultText.text = buildString {
                        appendLine(getString(R.string.fastest_summary_title))
                        appendLine(getString(R.string.source_label, sourceResult.sourceLabel))
                        appendLine(getString(R.string.latency_label, fastest.latencyMs))
                        appendLine(getString(R.string.check_type_label, fastest.checkType))
                        appendLine(getString(R.string.host_label, fastest.host, fastest.port))
                        appendLine(getString(R.string.working_count, batch.working.size))
                        appendLine(getString(R.string.confirmed_count, batch.confirmed.size))
                        appendLine(getString(R.string.candidate_count, batch.candidates.size))
                        appendLine(getString(R.string.failed_count, batch.failed.size))
                        appendLine(getString(R.string.skipped_count, batch.skipped.size))
                    }
                    Toast.makeText(this@MainActivity, R.string.fastest_selected_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.operation_failed)
                binding.resultText.text = e.message ?: getString(R.string.operation_failed)
                Toast.makeText(this@MainActivity, binding.resultText.text, Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runFullCheck() {
        setBusy(true)
        renderCheckedResults(emptyList())
        binding.statusText.text = getString(
            R.string.preparing_source_for_check,
            currentSelectedSource().displayName(this)
        )
        binding.resultText.text = ""

        lifecycleScope.launch {
            try {
                val sourceResult = loadActiveSourceText(forceRemoteRefresh = currentSelectedSource() != ListSource.Manual)
                val links = sourceResult.links
                if (links.isEmpty()) {
                    binding.statusText.text = getString(R.string.empty_list)
                    binding.resultText.text = buildSourceInfoBlock(sourceResult)
                    updateFastestActionsState("")
                    return@launch
                }

                binding.statusText.text = getString(
                    R.string.full_check_started_with_source,
                    links.size,
                    sourceResult.sourceLabel
                )

                val batch = withContext(Dispatchers.IO) {
                    VlessChecker.checkAll(links, { checked, total, current ->
                        runOnUiThread {
                            binding.statusText.text = getString(
                                R.string.check_progress,
                                checked,
                                total,
                                current.take(80)
                            )
                        }
                    }, sourceResult.configs)
                }
                
                // Update persistent storage with new working configs
                PersistentWorkingConfigsManager.updateWithNewResults(
                    context = this@MainActivity,
                    newResults = batch.checked,
                    source = sourceResult.source
                )

                val workingDetailed = batch.checked.filter { it.isWorking }
                renderCheckedResults(workingDetailed)

                val confirmedLinks = batch.confirmed.map { it.link }
                val workingLinks = batch.working.map { it.link }
                persistVisibleResultsSnapshot()
                val fastest = batch.working.firstOrNull()
                if (fastest != null) {
                    onFastestChosen(fastest)
                    NotificationHelper.showFoundLinkNotification(
                        context = this@MainActivity,
                        link = fastest.link,
                        title = getString(R.string.notification_title_all, batch.working.size),
                        text = getString(
                            R.string.notification_fastest_text,
                            fastest.latencyMs,
                            NotificationHelper.shorten(fastest.link, 56)
                        )
                    )
                } else {
                    AppPrefs.setLastFastestLink(this@MainActivity, "")
                    updateFastestActionsState("")
                }

                if (binding.deleteDeadSwitch.isChecked) {
                    val newText = workingLinks.joinToString("\n")
                    updateLinksEditorText(newText)
                    ConfigFileStore.saveCurrentSnapshot(this@MainActivity, currentSelectedSource(), newText)
                    if (currentSelectedSource() == ListSource.Manual) {
                        AppPrefs.setServerList(this@MainActivity, newText)
                    }
                }

                binding.statusText.text = getString(
                    R.string.full_check_done,
                    batch.working.size,
                    batch.failed.size,
                    batch.skipped.size
                )
                binding.resultText.text = buildString {
                    appendLine(getString(R.string.source_label, sourceResult.sourceLabel))
                    if (fastest != null) {
                        appendLine(getString(R.string.fastest_summary_title))
                        appendLine(getString(R.string.latency_label, fastest.latencyMs))
                        appendLine(getString(R.string.host_label, fastest.host, fastest.port))
                    } else {
                        appendLine(getString(R.string.no_link_passed_balanced))
                    }
                    appendLine(getString(R.string.working_count, batch.working.size))
                    appendLine(getString(R.string.confirmed_count, batch.confirmed.size))
                    appendLine(getString(R.string.candidate_count, batch.candidates.size))
                    appendLine(getString(R.string.failed_count, batch.failed.size))
                    appendLine(getString(R.string.skipped_count, batch.skipped.size))
                }

                if (workingLinks.isNotEmpty()) {
                    val toastText = if (binding.deleteDeadSwitch.isChecked) {
                        getString(R.string.full_check_done_and_cleaned)
                    } else {
                        getString(R.string.fastest_selected_to_clipboard)
                    }
                    Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.no_working_found, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.operation_failed)
                binding.resultText.text = e.message ?: getString(R.string.operation_failed)
                Toast.makeText(this@MainActivity, binding.resultText.text, Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun loadActiveSourceText(forceRemoteRefresh: Boolean): SourceTextResult {
        val source = currentSelectedSource()
        return if (source == ListSource.Manual) {
            val manualText = binding.linksEditText.text?.toString().orEmpty()
            AppPrefs.setServerList(this, manualText)
            ConfigFileStore.saveCurrentSnapshot(this, source, manualText)
            SourceTextResult(
                source = source,
                rawText = manualText,
                sourceLabel = source.displayName(this),
                fetchedFresh = false,
                fromCache = false
            )
        } else {
            val result = withContext(Dispatchers.IO) {
                RemoteListRepository.loadForSource(
                    context = this@MainActivity,
                    source = source,
                    preferFreshRemote = forceRemoteRefresh
                )
            }
            updateLinksEditorText(result.rawText)
            ConfigFileStore.saveCurrentSnapshot(this@MainActivity, source, result.rawText)
            result
        }
    }

    private fun buildDetailedList(sourceResult: SourceTextResult, batch: BatchCheckResult): String {
        return buildString {
            append(buildSourceInfoBlock(sourceResult))
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

    private fun buildSourceInfoBlock(sourceResult: SourceTextResult): String {
        return buildString {
            appendLine(getString(R.string.source_label, sourceResult.sourceLabel))
            when {
                sourceResult.fetchedFresh -> appendLine(getString(R.string.source_mode_fresh))
                sourceResult.fromCache -> appendLine(getString(R.string.source_mode_cache))
                else -> appendLine(getString(R.string.source_mode_local))
            }
            if (!sourceResult.warningMessage.isNullOrBlank()) {
                appendLine(getString(R.string.source_warning, sourceResult.warningMessage))
            }
            appendLine(getString(R.string.source_lines_count, sourceResult.links.size))
            val snapshotFile = ConfigFileStore.getCurrentSnapshotFile(this@MainActivity, sourceResult.source)
            if (snapshotFile.exists()) {
                appendLine(getString(R.string.saved_file_label, snapshotFile.absolutePath))
            }
            appendLine()
        }
    }

    private fun renderCheckedResults(items: List<LinkCheckResult>) {
        latestWorkingResults = items.filter { it.isWorking }
        val visibleItems = latestWorkingResults
            .filter { !shouldHideCandidates() || it.confidence != CheckConfidence.CANDIDATE }
            .sortedWith(
                compareBy<LinkCheckResult> { confidenceSortRank(it.confidence) }
                    .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            )
        visibleWorkingResults = visibleItems
        persistVisibleResultsSnapshot()
        binding.checkedResultsContainer.removeAllViews()
        val visible = visibleItems.isNotEmpty()
        binding.checkedResultsTitle.visibility = if (visible) View.VISIBLE else View.GONE
        binding.checkedResultsHint.visibility = if (visible) View.VISIBLE else View.GONE
        binding.checkedResultsContainer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.saveDisplayedListFileButton.isEnabled = visible
        binding.saveAsButton.isEnabled = visible
        binding.copyAllButton.isEnabled = visible
        if (visible) {
            binding.checkedResultsTitle.text = getString(R.string.checked_results_title)
        }

        visibleItems.forEachIndexed { index, item ->
            val row = ItemCheckedResultBinding.inflate(layoutInflater, binding.checkedResultsContainer, false)
            row.indexText.text = getString(R.string.checked_item_title, index + 1)
            row.statusText.text = buildRowStatus(item)
            row.endpointText.text = buildRowEndpoint(item)
            val preparedConfig = VpnImportHelper.prepareClipboardConfig(item.link)
            row.configPreviewText.text = preparedConfig
            applyRowColors(row, item.confidence)
            row.root.setOnClickListener {
                ClipboardHelper.copyLink(this, preparedConfig)
                Toast.makeText(
                    this,
                    getString(R.string.checked_item_copied_to_clipboard, index + 1),
                    Toast.LENGTH_SHORT
                ).show()
            }
            row.root.setOnLongClickListener {
                val openedDirectly = VpnImportHelper.importOrShare(this, preparedConfig)
                val messageRes = if (openedDirectly) {
                    R.string.fastest_sent_to_vpn_client
                } else {
                    R.string.fastest_sent_via_share
                }
                Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                true
            }
            binding.checkedResultsContainer.addView(row.root)
        }
    }

    private fun confidenceSortRank(confidence: CheckConfidence?): Int {
        return when (confidence) {
            CheckConfidence.CONFIRMED -> 0
            CheckConfidence.CANDIDATE -> 1
            null -> 2
        }
    }

    private fun applyRowColors(row: ItemCheckedResultBinding, confidence: CheckConfidence?) {
        val bgColor = when (confidence) {
            CheckConfidence.CONFIRMED -> ContextCompat.getColor(this, R.color.checked_confirmed_bg)
            CheckConfidence.CANDIDATE -> ContextCompat.getColor(this, R.color.checked_candidate_bg)
            null -> ContextCompat.getColor(this, R.color.checked_default_bg)
        }
        val strokeColor = when (confidence) {
            CheckConfidence.CONFIRMED -> ContextCompat.getColor(this, R.color.checked_confirmed_stroke)
            CheckConfidence.CANDIDATE -> ContextCompat.getColor(this, R.color.checked_candidate_stroke)
            null -> ContextCompat.getColor(this, R.color.checked_default_stroke)
        }
        val textColor = when (confidence) {
            CheckConfidence.CONFIRMED -> ContextCompat.getColor(this, R.color.checked_confirmed_text)
            CheckConfidence.CANDIDATE -> ContextCompat.getColor(this, R.color.checked_candidate_text)
            null -> ContextCompat.getColor(this, R.color.checked_default_text)
        }

        row.root.setCardBackgroundColor(bgColor)
        row.root.strokeWidth = (2 * resources.displayMetrics.density).toInt()
        row.root.strokeColor = strokeColor
        row.root.rippleColor = ColorStateList.valueOf(strokeColor)

        row.indexText.setTextColor(textColor)
        row.statusText.setTextColor(textColor)
        row.endpointText.setTextColor(textColor)
        row.configPreviewText.setTextColor(textColor)
    }

    private fun buildRowStatus(item: LinkCheckResult): String {
        val badge = when (item.confidence) {
            CheckConfidence.CONFIRMED -> getString(R.string.confidence_badge_confirmed)
            CheckConfidence.CANDIDATE -> getString(R.string.confidence_badge_candidate)
            null -> ""
        }
        val latency = item.latencyMs?.let { getString(R.string.latency_label, it) }
            ?: getString(R.string.latency_unknown_label)
        return listOf(badge.takeIf { it.isNotBlank() }, item.statusText, latency)
            .joinToString(" · ")
    }

    private fun buildRowEndpoint(item: LinkCheckResult): String {
        val endpoint = if (item.host != null && item.port != null) {
            "${item.host}:${item.port}"
        } else {
            getString(R.string.unknown_endpoint)
        }
        val confidenceText = when (item.confidence) {
            CheckConfidence.CONFIRMED -> getString(R.string.confidence_confirmed_long)
            CheckConfidence.CANDIDATE -> getString(R.string.confidence_candidate_long)
            null -> getString(R.string.confidence_unknown_long)
        }
        return getString(R.string.checked_item_meta, endpoint, item.checkType, confidenceText)
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

        AppPrefs.setSelectedSource(this, ListSource.Manual)
        binding.sourceSpinner.setSelection(sourceItems.indexOf(ListSource.Manual))
        updateLinksEditorText(text)
        AppPrefs.setServerList(this, text)
        ConfigFileStore.saveCurrentSnapshot(this, ListSource.Manual, text)
        renderCheckedResults(emptyList())
        binding.statusText.text = getString(R.string.import_success)
        binding.resultText.text = buildString {
            appendLine(getString(R.string.imported_lines_count, VlessChecker.normalizeLines(text).size))
            appendLine(getString(R.string.import_switched_to_manual))
        }
        Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentListFile() {
        val source = currentSelectedSource()
        if (source == ListSource.Manual) {
            val currentText = binding.linksEditText.text?.toString().orEmpty()
            AppPrefs.setServerList(this, currentText)
            ConfigFileStore.saveCurrentSnapshot(this, source, currentText)
        }
        val ok = ConfigFileStore.shareCurrentSnapshot(this, source)
        val messageRes = if (ok) {
            R.string.share_current_file_success
        } else {
            R.string.share_current_file_missing
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun shareWorkingListFile() {
        persistVisibleResultsSnapshot()
        val ok = ConfigFileStore.shareWorkingSnapshot(this, currentSelectedSource())
        val messageRes = if (ok) {
            R.string.share_working_file_success
        } else {
            R.string.share_working_file_missing
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun saveDisplayedListFile() {
        val snapshot = persistVisibleResultsSnapshot()
        if (snapshot == null) {
            Toast.makeText(this, R.string.save_displayed_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        binding.resultText.text = buildString {
            appendLine(getString(R.string.save_displayed_file_success))
            appendLine(getString(R.string.saved_file_label, snapshot.file.absolutePath))
            val existing = binding.resultText.text?.toString().orEmpty().trim()
            if (existing.isNotBlank()) {
                appendLine()
                append(existing)
            }
        }
        Toast.makeText(this, R.string.save_displayed_file_success, Toast.LENGTH_SHORT).show()
    }

    private fun saveAsToUserSelectedLocation() {
        val links = getDisplayedLinks()
        if (links.isEmpty()) {
            Toast.makeText(this, R.string.save_displayed_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = System.currentTimeMillis()
        val fileName = "vless_configs_${timestamp}.txt"
        saveAsLauncher.launch(fileName)
    }

    private fun copyAllDisplayedToClipboard() {
        val links = getDisplayedLinks()
        if (links.isEmpty()) {
            Toast.makeText(this, R.string.copy_all_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = links.joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VLESS configs", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copy_all_success, links.size), Toast.LENGTH_SHORT).show()
    }

    private fun saveToUri(uri: Uri) {
        val links = getDisplayedLinks()
        if (links.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                        links.forEach { link ->
                            writer.write(link)
                            writer.newLine()
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.save_as_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.save_as_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDisplayedLinks(): List<String> {
        return visibleWorkingResults.map { it.link }
    }

    private fun importFastestIntoVpnClient() {
        val link = AppPrefs.getLastFastestLink(this)
        if (link.isBlank()) {
            Toast.makeText(this, R.string.no_fastest_to_import, Toast.LENGTH_SHORT).show()
            return
        }

        val openedDirectly = VpnImportHelper.importOrShare(this, link)
        val messageRes = if (openedDirectly) {
            R.string.fastest_sent_to_vpn_client
        } else {
            R.string.fastest_sent_via_share
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun onFastestChosen(fastest: CheckResult) {
        val prepared = VpnImportHelper.prepareClipboardConfig(fastest.link)
        ClipboardHelper.copyLink(this, prepared)
        AppPrefs.setLastFastestLink(this, prepared)
        updateFastestActionsState(prepared)
    }

    private fun updateFastestActionsState(link: String) {
        val hasLink = link.isNotBlank()
        binding.importFastestVpnButton.isEnabled = hasLink
    }

    private fun persistVisibleResultsSnapshot(): SavedConfigSnapshot? {
        return ConfigFileStore.saveDisplayedSnapshot(
            context = this,
            source = currentSelectedSource(),
            links = visibleWorkingResults.map { it.link }
        )
    }

    private fun shouldHideCandidates(): Boolean {
        return binding.hideCandidatesSwitch.isChecked
    }

    private fun updateAutoCheckState(notify: Boolean) {
        val interval = AppPrefs.getAutoCheckIntervalMinutes(this)
        updateIntervalHint(interval)
        if (binding.autoCheckSwitch.isChecked) {
            if (currentSelectedSource() == ListSource.Manual) {
                AppPrefs.setServerList(this, binding.linksEditText.text?.toString().orEmpty())
            }
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
        binding.autoCheckHint.text = getString(
            R.string.auto_check_hint_with_source,
            interval,
            currentSelectedSource().displayName(this)
        )
    }

    private fun updateSourceItems() {
        sourceItems = ListSource.getAllSources(this)
        val sourceLabels = sourceItems.map { it.displayName(this) }
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sourceLabels)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sourceSpinner.adapter = sourceAdapter
        
        // Показать/скрыть кнопку управления источниками
        val hasUserSources = sourceItems.any { it is ListSource.UserDefined }
        binding.manageSourcesButton.visibility = if (hasUserSources) View.VISIBLE else View.GONE
    }

    private fun setBusy(isBusy: Boolean) {
        binding.checkFirstButton.isEnabled = !isBusy
        binding.checkAllButton.isEnabled = !isBusy
        binding.importButton.isEnabled = !isBusy
        binding.shareCurrentListFileButton.isEnabled = !isBusy
        binding.shareWorkingListFileButton.isEnabled = !isBusy
        binding.refreshSourceButton.isEnabled = !isBusy
        binding.sourceSpinner.isEnabled = !isBusy
        binding.intervalSpinner.isEnabled = !isBusy
        binding.maxConfigsSpinner.isEnabled = !isBusy
        binding.maxLatencySpinner.isEnabled = !isBusy
        binding.autoCheckSwitch.isEnabled = !isBusy
        binding.deleteDeadSwitch.isEnabled = !isBusy
        binding.hideCandidatesSwitch.isEnabled = !isBusy
        binding.saveDisplayedListFileButton.isEnabled = !isBusy && visibleWorkingResults.isNotEmpty()
        binding.importFastestVpnButton.isEnabled = !isBusy && AppPrefs.getLastFastestLink(this).isNotBlank()
        binding.openVpnSettingsButton.isEnabled = !isBusy
        binding.startVpnButton.isEnabled = !isBusy
        binding.stopVpnButton.isEnabled = !isBusy
        binding.restartVpnButton.isEnabled = !isBusy
        binding.progressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun currentSelectedSource(): ListSource {
        val position = binding.sourceSpinner.selectedItemPosition.coerceIn(0, sourceItems.lastIndex)
        return sourceItems[position]
    }

    private fun updateLinksEditorText(text: String) {
        if (binding.linksEditText.text?.toString() != text) {
            binding.linksEditText.setText(text)
        }
    }

    private fun loadLinksFromAssets(): String {
        return try {
            assets.open("servers.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun showUserDefinedUrlDialog() {
        val currentUrl = AppPrefs.getUserDefinedUrl(this)
        val currentName = AppPrefs.getUserDefinedName(this).takeIf { it.isNotBlank() } ?: ""

        val view = layoutInflater.inflate(R.layout.dialog_user_source, null)
        val urlEditText = view.findViewById<android.widget.EditText>(R.id.urlEditText)
        val nameEditText = view.findViewById<android.widget.EditText>(R.id.nameEditText)
        urlEditText.setText(currentUrl)
        nameEditText.setText(currentName)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.user_defined_url_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val url = urlEditText.text.toString().trim()
                val name = nameEditText.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, R.string.url_cannot_be_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, R.string.url_must_be_http_or_https, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AppPrefs.setUserDefinedUrl(this, url)
                AppPrefs.setUserDefinedName(this, name)
                // Refresh current source if it's USER_DEFINED
                if (currentSelectedSource() is ListSource.UserDefined) {
                    refreshSelectedSource(showToast = true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(R.string.clear) { dialog, _ ->
                AppPrefs.setUserDefinedUrl(this, "")
                AppPrefs.setUserDefinedName(this, "")
                if (currentSelectedSource() is ListSource.UserDefined) {
                    // Switch to MANUAL source if URL cleared while USER_DEFINED selected
                    AppPrefs.setSelectedSource(this, ListSource.Manual)
                    binding.sourceSpinner.setSelection(sourceItems.indexOf(ListSource.Manual))
                }
                Toast.makeText(this, R.string.url_cleared, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }
}
