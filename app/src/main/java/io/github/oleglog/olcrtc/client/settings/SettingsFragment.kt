package io.github.oleglog.olcrtc.client.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.oleglog.olcrtc.client.BuildConfig
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.databinding.FragmentSettingsBinding
import io.github.oleglog.olcrtc.client.diagnostics.DiagnosticsLogStore
import io.github.oleglog.olcrtc.client.routing.AppRoutingRepository
import io.github.oleglog.olcrtc.client.routing.GeoAssetManager
import io.github.oleglog.olcrtc.client.routing.PerAppPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingRule
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.support.IssueReportBuilder
import io.github.oleglog.olcrtc.client.support.IssueReportInfo
import io.github.oleglog.olcrtc.client.updater.ApkUpdateInstaller
import io.github.oleglog.olcrtc.client.updater.GitHubUpdateClient
import io.github.oleglog.olcrtc.client.updater.GitHubRelease
import io.github.oleglog.olcrtc.client.vpn.GomobileCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val settings by lazy { RoutingSettings.open(requireContext().applicationContext) }
    private val diagnostics by lazy { DiagnosticsLogStore.open(requireContext().applicationContext) }
    private val appRouting by lazy { AppRoutingRepository.open(requireContext().applicationContext) }
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return requireNotNull(_binding).root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = requireNotNull(_binding)
        binding.routingPreset.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.routing_all_vpn), getString(R.string.routing_russia_direct)),
        )
        binding.perAppMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.settings_per_app_all),
                getString(R.string.settings_per_app_exclude),
                getString(R.string.settings_per_app_only),
            ),
        )
        binding.save.setOnClickListener { save() }
        binding.refreshAppList.setOnClickListener { refreshAppList() }
        binding.selectApps.setOnClickListener { selectApps() }
        binding.addRoutingRule.setOnClickListener { addRoutingRule() }
        binding.updateGeoAssets.setOnClickListener { updateGeoAssets() }
        binding.changeLanguage.setOnClickListener { changeLanguage() }
        binding.openAlwaysOn.setOnClickListener { openSystemSettings(Settings.ACTION_VPN_SETTINGS) }
        binding.openBatteryOptimization.setOnClickListener {
            openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        binding.checkUpdate.setOnClickListener { checkUpdate() }
        binding.showCoreVersions.setOnClickListener { showCoreVersions() }
        binding.viewDiagnostics.setOnClickListener { showDiagnostics() }
        binding.copyDiagnostics.setOnClickListener { copyDiagnostics() }
        binding.exportDiagnostics.setOnClickListener { exportDiagnostics() }
        binding.reportIssue.setOnClickListener { reportIssue() }
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun changeLanguage() {
        val options = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_russian),
            getString(R.string.settings_language_english),
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = when {
            current.startsWith("ru") -> 1
            current.startsWith("en") -> 2
            else -> 0
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, selected) { dialog, index ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        when (index) {
                            1 -> "ru"
                            2 -> "en"
                            else -> ""
                        },
                    ),
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                LoadedSettings(
                    routingPolicy = settings.get(),
                    dnsServer = settings.getDnsServer(),
                    perAppPolicy = settings.getPerAppPolicy(),
                    routingRules = profiles.getEnabledRoutingRules().size,
                )
            }
            val binding = _binding ?: return@launch
            binding.routingPreset.setSelection(if (values.routingPolicy.preset == RoutingPolicy.Preset.ALL_VPN) 0 else 1)
            binding.allowLan.isChecked = values.routingPolicy.allowLan
            binding.dnsServer.setText(values.dnsServer.orEmpty())
            binding.perAppMode.setSelection(values.perAppPolicy.mode.ordinal)
            binding.appRoutingSummary.text = getString(
                R.string.settings_app_routing_summary,
                0,
                values.perAppPolicy.packages.size,
            )
            binding.routingRulesSummary.text = getString(
                R.string.settings_routing_rules_summary,
                values.routingRules,
            )
        }
    }

    private fun save() {
        val binding = _binding ?: return
        val policy = RoutingPolicy(
            preset = if (binding.routingPreset.selectedItemPosition == 0) {
                RoutingPolicy.Preset.ALL_VPN
            } else {
                RoutingPolicy.Preset.RUSSIA_DIRECT
            },
            allowLan = binding.allowLan.isChecked,
        )
        val dnsServer = binding.dnsServer.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val perAppMode = PerAppPolicy.Mode.values()[binding.perAppMode.selectedItemPosition]
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val currentPerApp = settings.getPerAppPolicy()
                    settings.set(policy)
                    settings.setDnsServer(dnsServer)
                    settings.setPerAppPolicy(currentPerApp.copy(mode = perAppMode))
                }
            }
            val current = _binding ?: return@launch
            result.onSuccess { current.status.setText(R.string.settings_saved) }
                .onFailure { current.status.text = it.message }
        }
    }

    private fun refreshAppList() {
        val includeSystem = _binding?.includeSystemApps?.isChecked ?: false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val currentPolicy = settings.getPerAppPolicy()
                    val apps = appRouting.refreshInstalled(includeSystem)
                    appRouting.setSelected(currentPolicy.packages, true)
                    apps.size to currentPolicy.packages.size
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess { (installed, selected) ->
                binding.appRoutingSummary.text = getString(
                    R.string.settings_app_routing_summary,
                    installed,
                    selected,
                )
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun selectApps() {
        val includeSystem = _binding?.includeSystemApps?.isChecked ?: false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val policy = settings.getPerAppPolicy()
                    val apps = appRouting.refreshInstalled(includeSystem)
                    apps to policy.packages
                }
            }
            result.onSuccess { (apps, selectedPackages) ->
                val checked = apps.map { it.packageName in selectedPackages }.toBooleanArray()
                val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                val iconSize = 36.dp
                apps.forEachIndexed { index, app ->
                    list.addView(CheckBox(requireContext()).apply {
                        text = "${app.label}\n${app.packageName}"
                        isChecked = checked[index]
                        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                        runCatching { requireContext().packageManager.getApplicationIcon(app.packageName) }
                            .onSuccess { icon ->
                                icon.setBounds(0, 0, iconSize, iconSize)
                                setCompoundDrawables(icon, null, null, null)
                                compoundDrawablePadding = 12.dp
                            }
                        setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
                    })
                }
                val scroll = ScrollView(requireContext()).apply { addView(list) }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_select_apps)
                    .setMessage(R.string.settings_select_apps_description)
                    .setView(scroll)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = apps.filterIndexed { index, _ -> checked[index] }
                            .mapTo(mutableSetOf()) { it.packageName }
                        saveSelectedApps(apps.size, selected)
                    }
                    .show()
            }.onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun saveSelectedApps(installedCount: Int, selected: Set<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val current = settings.getPerAppPolicy()
                    appRouting.setSelected(appRouting.selectedPackages(), false)
                    appRouting.setSelected(selected, true)
                    settings.setPerAppPolicy(current.copy(packages = selected))
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess {
                binding.appRoutingSummary.text = getString(
                    R.string.settings_app_routing_summary,
                    installedCount,
                    selected.size,
                )
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun addRoutingRule() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.settings_routing_rule_hint)
            setSingleLine(false)
            minLines = 2
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_add_routing_rule)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveRoutingRule(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun saveRoutingRule(raw: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val parts = raw.trim().split(Regex("\\s+"), limit = 3)
                    require(parts.size == 3) { getString(R.string.settings_routing_rule_hint) }
                    profiles.saveRoutingRule(
                        RoutingRule.create(
                            matchType = RoutingRule.MatchType.valueOf(parts[1].uppercase()),
                            value = parts[2],
                            action = RoutingRule.Action.valueOf(parts[0].uppercase()),
                            enabled = true,
                            sortOrder = profiles.getEnabledRoutingRules().size,
                        ),
                    )
                    profiles.getEnabledRoutingRules().size
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess { count ->
                binding.routingRulesSummary.text = getString(R.string.settings_routing_rules_summary, count)
                binding.status.setText(R.string.settings_saved)
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun openSystemSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { _binding?.status?.setText(R.string.settings_system_screen_unavailable) }
    }

    private fun updateGeoAssets() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GeoAssetManager(appContext).updateFromDefaultSources()
            }
            result.onSuccess { _binding?.status?.setText(R.string.settings_geo_assets_updated) }
                .onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun showCoreVersions() {
        val versions = GomobileCore.coreVersions()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_core_versions)
            .setMessage(getString(R.string.settings_core_versions_message, versions.xray, versions.olcrtc))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun checkUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    GitHubUpdateClient(currentVersion = BuildConfig.VERSION_NAME).check()
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess { update ->
                binding.status.text = when {
                    update.selectedAsset == null -> getString(R.string.settings_update_no_asset)
                    update.newerThanCurrent -> getString(
                        R.string.settings_update_available,
                        update.release.tagName,
                        update.selectedAsset.name,
                    )
                    else -> getString(R.string.settings_update_current, BuildConfig.VERSION_NAME)
                }
                if (update.newerThanCurrent && update.selectedAsset != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_check_update)
                        .setMessage(binding.status.text)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.settings_update_download_install) { _, _ ->
                            installUpdate(update.release, update.selectedAsset)
                        }
                        .show()
                }
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun installUpdate(release: GitHubRelease, asset: GitHubRelease.ReleaseAsset) {
        viewLifecycleOwner.lifecycleScope.launch {
            val installer = ApkUpdateInstaller(requireContext().applicationContext)
            if (!installer.canRequestPackageInstalls()) {
                _binding?.status?.setText(R.string.settings_update_install_permission)
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${requireContext().packageName}")))
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { installer.downloadAndVerify(release, asset) }
            }
            result.onSuccess { update ->
                (activity as? io.github.oleglog.olcrtc.client.MainActivity)?.stopVpn()
                startActivity(installer.installIntent(update))
            }.onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun showDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { diagnostics.readRedacted() }
                .ifBlank { getString(R.string.settings_no_diagnostics) }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_diagnostics_title)
                .setMessage(text.takeLast(MAX_DIALOG_LOG_CHARS))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun copyDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { diagnostics.readRedacted() }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.settings_diagnostics_title), text))
            _binding?.status?.setText(R.string.settings_diagnostics_copied)
        }
    }

    private fun exportDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(requireContext().cacheDir, "diagnostics").apply { mkdirs() }
                    File(directory, "olcrtc-diagnostics.txt").apply {
                        writeText(diagnostics.readRedacted())
                    }
                }
            }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.files", file)
                startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun reportIssue() {
        val versions = GomobileCore.coreVersions()
        val url = IssueReportBuilder.buildUrl(
            IssueReportInfo(
                appVersion = BuildConfig.VERSION_NAME,
                protocol = null,
                xrayVersion = versions.xray,
                olcrtcCoreVersion = versions.olcrtc,
            ),
        )
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { _binding?.status?.text = it.message }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private data class LoadedSettings(
        val routingPolicy: RoutingPolicy,
        val dnsServer: String?,
        val perAppPolicy: PerAppPolicy,
        val routingRules: Int,
    )

    private companion object {
        const val MAX_DIALOG_LOG_CHARS = 12_000
    }
}
