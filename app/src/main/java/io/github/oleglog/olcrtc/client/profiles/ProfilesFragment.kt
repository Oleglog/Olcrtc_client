package io.github.oleglog.olcrtc.client.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.ProfileSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentProfilesBinding
import java.util.concurrent.Executors

class ProfilesFragment : Fragment() {
    private var _binding: FragmentProfilesBinding? = null
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
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
            val result = runCatching(profiles::listLocal)
            activity?.runOnUiThread { result.onSuccess(::showProfiles) }
        }
    }

    private fun showProfiles(items: List<ProfileSummary>) {
        val binding = _binding ?: return
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.profileList.removeAllViews()
        items.forEach { profile ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8.dp, 0, 8.dp)
            }
            row.addView(
                TextView(requireContext()).apply {
                    text = "${profile.name}\n${profile.type} · ${profile.endpoint}"
                    textAppearance = android.R.style.TextAppearance_Material_Body1
                    setPadding(0, 12.dp, 16.dp, 12.dp)
                    setOnClickListener { activityHost.requestVpnPermission(profile.id) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Button(requireContext()).apply {
                setText(R.string.delete)
                setOnClickListener { confirmDelete(profile) }
            })
            binding.profileList.addView(row)
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

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
