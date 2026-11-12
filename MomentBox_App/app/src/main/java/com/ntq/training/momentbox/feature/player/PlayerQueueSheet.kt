package com.ntq.training.momentbox.feature.player

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ntq.training.momentbox.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PlayerQueueSheet : BottomSheetDialogFragment() {
    private var rows = mutableListOf<Track>()
    private var dragging = false
    private var helper: ItemTouchHelper? = null
    private val client get() = (requireActivity() as PlayerActivity).client
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, saved: Bundle?): View {
        val d = resources.displayMetrics.density
        val root =
            LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt()) }
        root.addView(TextView(requireContext()).apply {
            setText(R.string.m5_player_queue)
            textSize = 20f
            typeface =
            ResourcesCompat.getFont(
                context,
                R.font.be_vietnam_pro_semibold
            )
        })
        root.addView(TextView(requireContext()).apply {
            setText(R.string.m5_player_queue_hint)
            textSize = 12f
            setPadding(
            0,
            (8 * d).toInt(),
            0,
            (8 * d).toInt()
        )
        })
        val list = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams =
            LinearLayout.LayoutParams(-1, (360 * d).toInt())
        }
        root.addView(list)
        val adapter = object : RecyclerView.Adapter<Row>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Row {
                val line = LinearLayout(parent.context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (64 * d).toInt()
                }
                val text = TextView(parent.context).apply {
                    textSize = 14f
                    typeface = ResourcesCompat.getFont(
                    context,
                    R.font.be_vietnam_pro_regular
                )
                maxLines = 2
                }
                line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                val drag = ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_m4_drag)
                    setPadding((16 * d).toInt())
                    contentDescription =
                    getString(R.string.m5_player_queue_hint)
                }
                line.addView(drag, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
                return Row(line, text, drag)
            }

            override fun onBindViewHolder(holder: Row, index: Int) {
                val track = rows[index]
                holder.text.text = track.title
                holder.text.setTextColor(
                    requireContext().getColor(
                        if (track.id == client.state.value.queue.getOrNull(client.state.value.index)?.id) R.color.primary else R.color.text_primary
                    )
                )
                holder.itemView.setOnClickListener {
                    val target = holder.bindingAdapterPosition
                    if (target >= 0) client.setQueue(
                    rows.toList(),
                    target,
                    true
                )
                }
                holder.drag.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) helper?.startDrag(
                        holder
                    )
                    false
                }
            }
        }
        list.adapter = adapter
        helper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun isLongPressDragEnabled() = false
                override fun onMove(
                rv: RecyclerView,
                a: RecyclerView.ViewHolder,
                b: RecyclerView.ViewHolder
            ): Boolean {
                val from = a.bindingAdapterPosition
                val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                dragging =
                    true
                    rows.add(to, rows.removeAt(from))
                    adapter.notifyItemMoved(
                    from,
                    to
                )
                return true
            }

                override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}
                override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                    super.clearView(rv, holder)
                    if (dragging) {
                        dragging = false
                        val state = client.state.value
                        val id = state.queue.getOrNull(state.index)?.id
                        val index = rows.indexOfFirst { it.id == id }
                        client.setQueue(
                        rows.toList(),
                        index.coerceAtLeast(0)
                    )
                    }
                }
            }).also { it.attachToRecyclerView(list) }
        viewLifecycleOwnerLiveData.observe(this) { owner ->
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(
                    Lifecycle.State.STARTED
                ) {
                    client.state.map { it.queue }.distinctUntilChanged().collect { values ->
                        if (!dragging) {
                            rows = values.toMutableList()
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
        return root
    }

    override fun onDestroyView() {
        helper = null
        rows.clear()
        super.onDestroyView()
    }

    private class Row(root: View, val text: TextView, val drag: ImageView) :
        RecyclerView.ViewHolder(root)
}
