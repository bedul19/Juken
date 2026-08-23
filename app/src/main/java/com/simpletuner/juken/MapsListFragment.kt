package com.simpletuner.juken

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

class MapsListFragment : Fragment(R.layout.fragment_maps_list) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<LinearLayout>(R.id.mapListContainer)
        container.removeAllViews()

        EcuProtocol.ALL_MAPS.forEachIndexed { i, spec ->
            val row = buildRow(spec, isLast = i == EcuProtocol.ALL_MAPS.size - 1)
            container.addView(row)
        }
    }

    private fun buildRow(spec: MapSpec, isLast: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_ios_row)
            setOnClickListener {
                (activity as? MainActivity)?.openMapDetail(spec)
            }
        }
        val title = TextView(ctx).apply {
            text = spec.label
            textSize = 17f
            setTextColor(Color.parseColor("#000000"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        row.addView(title)
        row.addView(chevron)

        if (isLast) return row

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row)
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(16)
            }
            setBackgroundColor(Color.parseColor("#E5E5EA"))
        }
        wrapper.addView(divider)
        return wrapper
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
