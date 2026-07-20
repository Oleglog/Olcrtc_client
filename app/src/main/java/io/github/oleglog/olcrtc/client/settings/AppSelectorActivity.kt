package io.github.oleglog.olcrtc.client.settings

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.databinding.ActivityAppSelectorBinding
import io.github.oleglog.olcrtc.client.databinding.ItemAppSelectionBinding
import io.github.oleglog.olcrtc.client.routing.AppRoutingItem
import io.github.oleglog.olcrtc.client.routing.AppRoutingRepository
import io.github.oleglog.olcrtc.client.routing.PerAppPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.ui.AppearanceTheme
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.Executors

class AppSelectorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppSelectorBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val repository by lazy { AppRoutingRepository.open(applicationContext) }
    private val settings by lazy { RoutingSettings.open(applicationContext) }
    private val selectedPackages = linkedSetOf<String>()
    private var apps = emptyList<AppRoutingItem>()
    private var query = ""
    private var showSystem = false
    private var restoredSelection = false

    private val adapter by lazy {
        AppSelectionAdapter(packageManager) { packageName ->
            if (!selectedPackages.add(packageName)) selectedPackages.remove(packageName)
            render()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppearanceTheme.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAppSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        restoredSelection = state?.containsKey(KEY_SELECTED) == true
        state?.getStringArrayList(KEY_SELECTED)?.let(selectedPackages::addAll)
        query = state?.getString(KEY_QUERY).orEmpty()
        showSystem = state?.getBoolean(KEY_SYSTEM) ?: false

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.search.setText(query)
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            render()
        }
        binding.typeFilter.check(if (showSystem) R.id.filter_system else R.id.filter_user)
        binding.typeFilter.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                showSystem = checkedId == R.id.filter_system
                render()
            }
        }
        binding.modeGroup.addOnButtonCheckedListener { _, _, _ -> render() }
        binding.selectAll.setOnClickListener {
            adapter.currentList.mapTo(selectedPackages, AppRoutingItem::packageName)
            render()
        }
        binding.clear.setOnClickListener {
            selectedPackages.clear()
            render()
        }
        binding.cancel.setOnClickListener { finish() }
        binding.save.setOnClickListener { save() }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selectedPackages))
        outState.putString(KEY_QUERY, query)
        outState.putBoolean(KEY_SYSTEM, showSystem)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        worker.execute {
            val policy = runCatching { settings.getPerAppPolicy() }.getOrElse {
                runOnUiThread {
                    if (!isDestroyed) showError(it.message)
                }
                return@execute
            }
            if (!restoredSelection) selectedPackages.addAll(policy.packages)
            val cached = runCatching { repository.cachedInstalled() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.modeGroup.check(policy.mode.buttonId)
                applyApps(cached)
            }
            val refreshed = runCatching { repository.refreshInstalled(includeSystem = true) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshed.onSuccess(::applyApps).onFailure { showError(it.message) }
            }
        }
    }

    private fun applyApps(values: List<AppRoutingItem>) {
        apps = values.filterNot { it.packageName == packageName }
        render()
    }

    private fun render() {
        val visible = filterAppItems(apps, query, showSystem).map {
            it.copy(selected = it.packageName in selectedPackages)
        }
        adapter.submitList(visible)
        binding.selectedCount.text = getString(R.string.settings_apps_selected, selectedPackages.size)
        binding.modePreview.text = when (binding.modeGroup.checkedButtonId.mode) {
            PerAppPolicy.Mode.ALL -> getString(R.string.settings_per_app_preview_all)
            PerAppPolicy.Mode.EXCLUDE_SELECTED ->
                getString(R.string.settings_per_app_preview_exclude, selectedPackages.size)
            PerAppPolicy.Mode.ONLY_SELECTED ->
                getString(R.string.settings_per_app_preview_only, selectedPackages.size)
        }
        binding.error.isVisible = false
    }

    private fun save() {
        val mode = binding.modeGroup.checkedButtonId.mode
        if (mode == PerAppPolicy.Mode.ONLY_SELECTED && selectedPackages.isEmpty()) {
            showError(getString(R.string.settings_apps_only_empty))
            return
        }
        binding.save.isEnabled = false
        val selection = selectedPackages.toSet()
        worker.execute {
            val result = runCatching {
                repository.setSelected(repository.selectedPackages(), false)
                repository.setSelected(selection, true)
                runBlocking { settings.setPerAppPolicy(PerAppPolicy(mode, selection)) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.save.isEnabled = true
                result.onSuccess {
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { showError(it.message) }
            }
        }
    }

    private fun showError(message: String?) {
        binding.error.text = message ?: getString(R.string.invalid_profile)
        binding.error.isVisible = true
    }

    private val PerAppPolicy.Mode.buttonId: Int
        get() = when (this) {
            PerAppPolicy.Mode.ALL -> R.id.mode_all
            PerAppPolicy.Mode.EXCLUDE_SELECTED -> R.id.mode_exclude
            PerAppPolicy.Mode.ONLY_SELECTED -> R.id.mode_only
        }

    private val Int.mode: PerAppPolicy.Mode
        get() = when (this) {
            R.id.mode_exclude -> PerAppPolicy.Mode.EXCLUDE_SELECTED
            R.id.mode_only -> PerAppPolicy.Mode.ONLY_SELECTED
            else -> PerAppPolicy.Mode.ALL
        }

    companion object {
        private const val KEY_SELECTED = "selected"
        private const val KEY_QUERY = "query"
        private const val KEY_SYSTEM = "system"
    }
}

internal fun filterAppItems(
    items: List<AppRoutingItem>,
    query: String,
    system: Boolean,
): List<AppRoutingItem> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    return items.filter { item ->
        item.system == system && (
            normalized.isEmpty() || item.label.lowercase(Locale.ROOT).contains(normalized) ||
                item.packageName.lowercase(Locale.ROOT).contains(normalized)
            )
    }
}

private class AppSelectionAdapter(
    private val packageManager: PackageManager,
    private val onToggle: (String) -> Unit,
) : ListAdapter<AppRoutingItem, AppSelectionAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemAppSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemAppSelectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppRoutingItem) {
            binding.appLabel.text = item.label
            binding.packageName.text = item.packageName
            binding.selected.isChecked = item.selected
            binding.appIcon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(item.packageName) }.getOrNull(),
            )
            binding.root.contentDescription = binding.root.context.getString(
                R.string.settings_app_item_description,
                item.label,
                item.packageName,
                binding.root.context.getString(
                    if (item.selected) R.string.settings_app_selected else R.string.settings_app_not_selected,
                ),
            )
            binding.root.setOnClickListener { onToggle(item.packageName) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppRoutingItem>() {
            override fun areItemsTheSame(old: AppRoutingItem, new: AppRoutingItem) =
                old.packageName == new.packageName

            override fun areContentsTheSame(old: AppRoutingItem, new: AppRoutingItem) = old == new
        }
    }
}
