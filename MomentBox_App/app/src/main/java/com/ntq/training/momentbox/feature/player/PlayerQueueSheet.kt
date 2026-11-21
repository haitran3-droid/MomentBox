package com.ntq.training.momentbox.feature.player

import android.annotation.SuppressLint
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
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
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
    private var renderedActiveId: String? = null
    private var dragging = false
    private var helper: ItemTouchHelper? = null
    private val client get() = (requireActivity() as PlayerActivity).client

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, saved: Bundle?): View {
        val d = resources.displayMetrics.density
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt())
        }
        root.addView(TextView(requireContext()).apply {
            setText(R.string.m5_player_queue)
            textSize = 20f
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold)
        })
        root.addView(TextView(requireContext()).apply {
            setText(R.string.m5_player_queue_hint)
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        })
        val list = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (360 * d).toInt()
            )
        }
        root.addView(list)
        val adapter = object : RecyclerView.Adapter<Row>() {
            override fun getItemCount() = rows.size

            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Row {
                val line = LinearLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (64 * d).toInt()
                }
                val text = TextView(parent.context).apply {
                    textSize = 14f
                    typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_regular)
                    maxLines = 2
                }
                line.addView(
                    text,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val drag = ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_m4_drag)
                    setPadding((16 * d).toInt())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                line.addView(drag, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
                val holder = Row(line, text, drag)
                bindDragHandle(holder)
                ViewCompat.setAccessibilityDelegate(
                    holder.itemView,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            val position = holder.bindingAdapterPosition
                            if (position !in rows.indices || dragging) return
                            if (position > 0) {
                                info.addAction(
                                    AccessibilityActionCompat(
                                        R.id.player_queue_move_up,
                                        getString(R.string.player_queue_move_up)
                                    )
                                )
                            }
                            if (position < rows.lastIndex) {
                                info.addAction(
                                    AccessibilityActionCompat(
                                        R.id.player_queue_move_down,
                                        getString(R.string.player_queue_move_down)
                                    )
                                )
                            }
                        }

                        override fun performAccessibilityAction(
                            host: View,
                            action: Int,
                            args: Bundle?
                        ): Boolean {
                            val offset = when (action) {
                                R.id.player_queue_move_up -> -1
                                R.id.player_queue_move_down -> 1
                                else -> return super.performAccessibilityAction(host, action, args)
                            }
                            val from = holder.bindingAdapterPosition
                            val to = from + offset
                            if (dragging || from !in rows.indices || to !in rows.indices) return false
                            rows.add(to, rows.removeAt(from))
                            notifyItemMoved(from, to)
                            saveOrder()
                            return true
                        }
                    }
                )
                return holder
            }

            override fun onBindViewHolder(holder: Row, index: Int) {
                val track = rows[index]
                holder.text.text = track.title
                val activeId = client.state.value.queue.getOrNull(client.state.value.index)?.id
                holder.text.setTextColor(
                    requireContext().getColor(
                        if (track.id == activeId) R.color.primary else R.color.text_primary
                    )
                )
                holder.itemView.setOnClickListener {
                    val target = holder.bindingAdapterPosition
                    if (target in rows.indices) {
                        client.setQueue(rows.toList(), target, play = true)
                    }
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
                dragging = true
                rows.add(to, rows.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                if (dragging) {
                    dragging = false
                    saveOrder()
                }
            }
        }).also { it.attachToRecyclerView(list) }
        viewLifecycleOwnerLiveData.observe(this) { owner ->
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    client.state
                        .map { it.queue to it.index }
                        .distinctUntilChanged()
                        .collect { (values, activeIndex) ->
                            if (!dragging) {
                                val oldRows = rows
                                val oldActiveId = renderedActiveId
                                val nextActiveId = values.getOrNull(activeIndex)?.id
                                val diff = DiffUtil.calculateDiff(
                                    object : DiffUtil.Callback() {
                                        override fun getOldListSize() = oldRows.size

                                        override fun getNewListSize() = values.size

                                        override fun areItemsTheSame(
                                            oldItemPosition: Int,
                                            newItemPosition: Int
                                        ) = oldRows[oldItemPosition].id == values[newItemPosition].id

                                        override fun areContentsTheSame(
                                            oldItemPosition: Int,
                                            newItemPosition: Int
                                        ): Boolean {
                                            val oldTrack = oldRows[oldItemPosition]
                                            val newTrack = values[newItemPosition]
                                            return oldTrack == newTrack &&
                                                (oldTrack.id == oldActiveId) ==
                                                (newTrack.id == nextActiveId)
                                        }
                                    }
                                )
                                rows = values.toMutableList()
                                renderedActiveId = nextActiveId
                                diff.dispatchUpdatesTo(adapter)
                            }
                        }
                }
            }
        }
        return root
    }

    // The handle begins a drag on touch down. The queue row exposes reorder actions to TalkBack.
    @SuppressLint("ClickableViewAccessibility")
    private fun bindDragHandle(holder: Row) {
        holder.drag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) helper?.startDrag(holder)
            false
        }
    }

    private fun saveOrder() {
        val state = client.state.value
        val currentId = state.queue.getOrNull(state.index)?.id
        val position = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        client.setQueue(rows.toList(), position)
    }

    override fun onDestroyView() {
        helper = null
        rows.clear()
        super.onDestroyView()
    }

    private class Row(root: View, val text: TextView, val drag: ImageView) :
        RecyclerView.ViewHolder(root)
}
