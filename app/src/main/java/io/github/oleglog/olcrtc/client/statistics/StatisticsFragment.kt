package io.github.oleglog.olcrtc.client.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.databinding.FragmentSectionBinding

class StatisticsFragment : Fragment() {
    private var _binding: FragmentSectionBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val binding = FragmentSectionBinding.inflate(inflater, container, false)
        _binding = binding
        binding.title.setText(R.string.navigation_statistics)
        binding.content.setText(R.string.statistics_placeholder)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
