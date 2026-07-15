package io.github.oleglog.olcrtc.client.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.databinding.FragmentSettingsBinding
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val settings by lazy { RoutingSettings.open(requireContext().applicationContext) }

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
        binding.save.setOnClickListener { save() }
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) { settings.get() to settings.getDnsServer() }
            val binding = _binding ?: return@launch
            binding.routingPreset.setSelection(if (values.first.preset == RoutingPolicy.Preset.ALL_VPN) 0 else 1)
            binding.allowLan.isChecked = values.first.allowLan
            binding.dnsServer.setText(values.second.orEmpty())
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    settings.set(policy)
                    settings.setDnsServer(dnsServer)
                }
            }
            val current = _binding ?: return@launch
            result.onSuccess { current.status.setText(R.string.settings_saved) }
                .onFailure { current.status.text = it.message }
        }
    }
}
