package com.ntq.training.momentbox.feature.journal

import android.content.Context
import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

class JournalSwipeCallback(
    context: Context
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
    private val actionButtonWidth = 132f * context.resources.displayMetrics.density
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        return if (viewHolder is JournalEntriesAdapter.EntryViewHolder) {
            makeMovementFlags(0, ItemTouchHelper.LEFT)
        } else {
            0
        }
    }
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int
    ) {}

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if(viewHolder is JournalEntriesAdapter.EntryViewHolder){
            val clampedDx = max(-actionButtonWidth,min(0f, dX))
            getDefaultUIUtil().onDraw(
                c,
                recyclerView,
                viewHolder.binding.cardForeground,
                clampedDx,
                dY,
                actionState,
                isCurrentlyActive
            )
        }else{
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if(viewHolder is JournalEntriesAdapter.EntryViewHolder){
            getDefaultUIUtil().clearView(viewHolder.binding.cardForeground)
        }else{
            super.clearView(recyclerView, viewHolder)
        }
    }
}