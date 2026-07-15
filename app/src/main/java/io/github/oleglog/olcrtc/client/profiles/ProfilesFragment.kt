package io.github.oleglog.olcrtc.client.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.ProfileSummary
import io.github.oleglog.olcrtc.client.data.SubscriptionProfileSummary
import io.github.oleglog.olcrtc.client.data.SubscriptionSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentProfilesBinding
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class ProfilesFragment : Fragment() {
    private var _binding: FragmentProfilesBinding? = null
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        requireNotNull(_binding).addSubscription.setOnClickListener { addSubscription() }
        return requireNotNull(_binding).root
    }

    override fun onStart() {
        super.onStart()
        loadProfiles()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }

    private fun loadProfiles() {
        storage.execute {
            val result = runCatching {
                ProfilesScreenModel(
                    local = profiles.listLocal(),
                    subscriptions = profiles.listSubscriptions(),
                )
            }
            activity?.runOnUiThread { result.onSuccess(::showProfiles) }
        }
    }

    private fun showProfiles(model: ProfilesScreenModel) {
        val binding = _binding ?: return
        val isEmpty = model.local.isEmpty() && model.subscriptions.isEmpty()
        binding.empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.profileList.removeAllViews()
        model.local.takeIf { it.isNotEmpty() }?.let { local ->
            binding.profileList.addView(sectionTitle(getString(R.string.profile_group_local)))
            binding.profileList.addView(materialCard().apply {
                addView(verticalContainer().apply {
                    local.forEach { addView(localProfileRow(it)) }
                })
            })
        }
        model.subscriptions.forEach { subscription ->
            binding.profileList.addView(sectionTitle(subscriptionHeader(subscription)))
            val card = materialCard().apply {
                addView(verticalContainer().apply {
                    addView(subscriptionActions(subscription))
                })
            }
            binding.profileList.addView(card)
            storage.execute {
                val result = runCatching { profiles.listSubscriptionProfiles(subscription.id) }
                activity?.runOnUiThread {
                    val current = _binding ?: return@runOnUiThread
                    val innerContainer = (card.getChildAt(0) as? LinearLayout) ?: return@runOnUiThread
                    result.getOrDefault(emptyList()).forEach { item ->
                        innerContainer.addView(subscriptionProfileRow(item))
                    }
                }
            }
        }
    }

    private fun materialCard(): com.google.android.material.card.MaterialCardView =
        com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            }
        }

    private fun verticalContainer(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
    }

    private fun localProfileRow(profile: ProfileSummary): View {
        val row = horizontalRow()
        row.addView(
            TextView(requireContext()).apply {
                text = "${profile.name}\n${profile.type} · ${profile.endpoint}"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setPadding(0, 12.dp, 16.dp, 12.dp)
                setOnClickListener { activityHost.requestVpnPermission(profile.id) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(Button(requireContext()).apply {
            setText(R.string.profile_test_latency)
            isEnabled = profile.type != "olcRTC"
            setOnClickListener { testLocalLatency(profile.id) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.edit)
            setOnClickListener { editLocalProfile(profile) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.copy)
            setOnClickListener { copyLocalProfileLink(profile.id) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.delete)
            setOnClickListener { confirmDelete(profile) }
        })
        return row
    }

    private fun subscriptionProfileRow(profile: SubscriptionProfileSummary): View {
        val row = horizontalRow().apply { setPadding(16.dp, 4.dp, 0, 4.dp) }
        row.addView(
            TextView(requireContext()).apply {
                val flags = listOfNotNull(
                    profile.type,
                    profile.lastLatencyMs?.let { "${it}ms" },
                    if (profile.locallyModified) getString(R.string.profile_locally_modified) else null,
                ).joinToString(" · ")
                text = "${profile.name}\n$flags · ${profile.endpoint}"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setPadding(0, 10.dp, 16.dp, 10.dp)
                setOnClickListener { activityHost.requestSubscriptionVpnPermission(profile.id) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(Button(requireContext()).apply {
            setText(R.string.profile_test_latency)
            isEnabled = profile.type != "OLCRTC"
            setOnClickListener { testSubscriptionLatency(profile.id) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.edit)
            setOnClickListener { editSubscriptionProfile(profile) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.copy)
            setOnClickListener { copySubscriptionProfileLink(profile.id) }
        })
        row.addView(Button(requireContext()).apply {
            setText(R.string.profile_reset)
            isEnabled = profile.locallyModified
            setOnClickListener { resetSubscriptionProfile(profile.id) }
        })
        return row
    }

    private fun addSubscription() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.subscription_edit_hint)
            setSingleLine(false)
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.subscription_add)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveNewSubscription(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun saveNewSubscription(raw: String) {
        storage.execute {
            val result = runCatching {
                val values = parseKeyValueLines(raw)
                val id = profiles.insertSubscriptionSource(
                    name = values["name"].orEmpty(),
                    url = values["url"].orEmpty(),
                    kind = values["kind"] ?: "GENERIC",
                )
                SubscriptionRefresher(profiles).refresh(id)
            }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun subscriptionActions(subscription: SubscriptionSummary): View {
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        container.addView(horizontalRow().apply {
            setPadding(0, 4.dp, 0, 4.dp)
            addView(Button(requireContext()).apply {
                setText(R.string.subscription_update_now)
                setOnClickListener { updateSubscription(subscription.id) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(requireContext()).apply {
                setText(R.string.subscription_details)
                setOnClickListener { showSubscriptionDetails(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        container.addView(horizontalRow().apply {
            setPadding(0, 0, 0, 4.dp)
            addView(Button(requireContext()).apply {
                setText(R.string.edit)
                setOnClickListener { editSubscription(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(requireContext()).apply {
                setText(R.string.delete)
                setOnClickListener { confirmDeleteSubscription(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        return container
    }

    private fun sectionTitle(textValue: String): TextView = TextView(requireContext()).apply {
        text = textValue
        setTextAppearance(android.R.style.TextAppearance_Material_Medium)
        setPadding(0, 18.dp, 0, 6.dp)
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 8.dp, 0, 8.dp)
    }

    private fun subscriptionHeader(subscription: SubscriptionSummary): String = buildString {
        append(subscription.name).append(" · ").append(subscription.kind)
        append(" · ").append(subscription.profileCount).append(' ').append(getString(R.string.profiles_count_suffix))
        subscription.serverVersion?.let { append(" · server ").append(it) }
        subscription.lastErrorCode?.let { append(" · ").append(it) }
        subscription.lastSuccessAt?.let {
            append(" · ").append(getString(R.string.subscription_last_success, formatTime(it)))
        }
        if (subscription.mirrorAvailable) append(" · mirror")
        if (!subscription.enabled) append(" · disabled")
    }

    private fun showSubscriptionDetails(subscription: SubscriptionSummary) {
        storage.execute {
            val result = runCatching { profiles.getSubscriptionSource(subscription.id) }
            activity?.runOnUiThread {
                result.onSuccess { source ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(subscription.name)
                        .setMessage(
                            buildString {
                                appendLine("Kind: ${subscription.kind}")
                                appendLine("Profiles: ${subscription.profileCount}")
                                appendLine("Server: ${subscription.serverVersion ?: "unknown"}")
                                appendLine("URL: ${source?.url ?: "unknown"}")
                                appendLine("Mirror: ${source?.mirrorType ?: "none"}")
                                appendLine("Last success: ${subscription.lastSuccessAt?.let(::formatTime) ?: "never"}")
                                appendLine("Last attempt: ${subscription.lastAttemptAt?.let(::formatTime) ?: "never"}")
                                appendLine("Last error: ${subscription.lastErrorCode ?: "none"}")
                            },
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun editSubscription(subscription: SubscriptionSummary) {
        storage.execute {
            val result = runCatching { profiles.getSubscriptionSource(subscription.id) }
            activity?.runOnUiThread {
                result.onSuccess { source ->
                    val input = EditText(requireContext()).apply {
                        hint = getString(R.string.subscription_edit_hint)
                        setSingleLine(false)
                        minLines = 2
                        setText("name=${subscription.name}\nurl=${source?.url.orEmpty()}")
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.edit)
                        .setView(input)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            saveSubscriptionEdit(subscription.id, input.text?.toString().orEmpty())
                        }
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun saveSubscriptionEdit(subscriptionId: Long, raw: String) {
        storage.execute {
            val result = runCatching {
                val values = parseKeyValueLines(raw)
                profiles.updateSubscriptionSource(
                    subscriptionId,
                    values["name"].orEmpty(),
                    values["url"].orEmpty(),
                )
            }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun editLocalProfile(profile: ProfileSummary) {
        storage.execute {
            val result = runCatching { profiles.exportProfileUri(profile.id, includeAuthToken = true) }
            activity?.runOnUiThread {
                result.onSuccess { raw ->
                    val input = EditText(requireContext()).apply {
                        setSingleLine(false)
                        minLines = 4
                        setText(raw)
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.profile_edit_title, profile.name))
                        .setView(input)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            saveLocalProfileEdit(profile.id, input.text?.toString().orEmpty())
                        }
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun saveLocalProfileEdit(profileId: Long, raw: String) {
        storage.execute {
            val result = runCatching {
                when (val imported = ProfileUri.parse(raw.trim())) {
                    is ImportedProfile.Olcrtc -> profiles.update(profileId, imported.value)
                    is ImportedProfile.Standard -> profiles.update(profileId, imported.value)
                }
            }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun editSubscriptionProfile(profile: SubscriptionProfileSummary) {
        storage.execute {
            val result = runCatching { profiles.exportSubscriptionProfileUri(profile.id, includeAuthToken = true) }
            activity?.runOnUiThread {
                result.onSuccess { raw ->
                    val input = EditText(requireContext()).apply {
                        setSingleLine(false)
                        minLines = 4
                        setText(raw)
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.profile_edit_title, profile.name))
                        .setView(input)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            saveSubscriptionProfileEdit(profile.id, input.text?.toString().orEmpty())
                        }
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun saveSubscriptionProfileEdit(profileId: String, raw: String) {
        storage.execute {
            val result = runCatching { profiles.updateSubscriptionProfile(profileId, ProfileUri.parse(raw.trim())) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun updateSubscription(subscriptionId: Long) {
        storage.execute {
            val result = runCatching { SubscriptionRefresher(profiles).refresh(subscriptionId) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun testLocalLatency(profileId: Long) {
        storage.execute {
            val result = runCatching { profiles.testLocalProfileLatency(profileId) }
            activity?.runOnUiThread { showLatencyResult(result) }
        }
    }

    private fun testSubscriptionLatency(profileId: String) {
        storage.execute {
            val result = runCatching { profiles.testSubscriptionProfileLatency(profileId) }
            activity?.runOnUiThread {
                showLatencyResult(result)
                if (result.isSuccess) loadProfiles()
            }
        }
    }

    private fun showLatencyResult(result: Result<Long>) {
        result.onSuccess { latency ->
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_test_latency)
                .setMessage(getString(R.string.profile_latency_result, latency))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }.onFailure { showError(it) }
    }

    private fun copyLocalProfileLink(profileId: Long) {
        copyProfileLink { profiles.exportProfileUri(profileId, includeAuthToken = false) }
    }

    private fun copySubscriptionProfileLink(profileId: String) {
        copyProfileLink { profiles.exportSubscriptionProfileUri(profileId, includeAuthToken = false) }
    }

    private fun copyProfileLink(factory: () -> String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_export_secret_warning_title)
            .setMessage(R.string.profile_export_secret_warning_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.copy) { _, _ ->
                storage.execute {
                    val result = runCatching(factory)
                    activity?.runOnUiThread {
                        result.onSuccess { link ->
                            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), link))
                        }.onFailure { showError(it) }
                    }
                }
            }
            .show()
    }

    private fun resetSubscriptionProfile(profileId: String) {
        storage.execute {
            val result = runCatching { profiles.resetSubscriptionProfile(profileId) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun confirmDelete(profile: ProfileSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storage.execute {
                    runCatching { profiles.deleteLocal(profile.id) }
                    activity?.runOnUiThread(::loadProfiles)
                }
            }
            .show()
    }

    private fun confirmDeleteSubscription(subscription: SubscriptionSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(getString(R.string.subscription_delete_message, subscription.name))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.subscription_keep_profiles) { _, _ -> deleteSubscription(subscription.id, true) }
            .setPositiveButton(R.string.delete) { _, _ -> deleteSubscription(subscription.id, false) }
            .show()
    }

    private fun deleteSubscription(subscriptionId: Long, retainProfiles: Boolean) {
        storage.execute {
            val result = runCatching { profiles.deleteSubscription(subscriptionId, retainProfiles) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun showError(error: Throwable) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.invalid_profile)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun parseKeyValueLines(raw: String): Map<String, String> = raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .associate { line ->
            val parts = line.split('=', limit = 2)
            require(parts.size == 2) { "Invalid line: $line" }
            parts[0].trim().lowercase() to parts[1].trim()
        }

    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
    ).format(Date(value))

    private data class ProfilesScreenModel(
        val local: List<ProfileSummary>,
        val subscriptions: List<SubscriptionSummary>,
    )

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
