package com.ntq.training.momentbox.feature.journal

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class JournalSwipeCallback(context: Context, private val onRevealChanged: (Boolean) -> Unit = {}) : RecyclerView.OnItemTouchListener {
    private val width = 132f * context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var base = 0f
    private var candidate: JournalEntriesAdapter.EntryViewHolder? = null
    private var active = false
    private var opened: JournalEntriesAdapter.EntryViewHolder? = null
    private var consumeClose = false

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                active = false
                consumeClose = false
                candidate = rv.findChildViewUnder(event.x, event.y)?.let { rv.getChildViewHolder(it) } as? JournalEntriesAdapter.EntryViewHolder
                if (opened != null && opened !== candidate) close(opened)
                candidate?.let { holder ->
                    val foreground = holder.binding.cardForeground
                    val right = holder.itemView.right + foreground.translationX
                    if (opened === holder && event.x > right) candidate = null // underlay button owns this tap
                    else if (opened === holder) {
                        close(holder)
                        consumeClose = true
                    }
                    base = foreground.translationX
                }
                return consumeClose
            }
            MotionEvent.ACTION_MOVE -> {
                if (candidate == null) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    candidate = null
                    return false
                }
                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                    active = true
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    draw(candidate!!, (base + dx).coerceIn(-width, 0f))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> candidate = null
        }
        return false
    }
    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (consumeClose) {
            if (event.actionMasked == MotionEvent.ACTION_UP) consumeClose = false
            return
        }
        val holder = candidate ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> draw(holder, (base + event.x - downX).coerceIn(-width, 0f))
            MotionEvent.ACTION_UP -> {
                val reveal = holder.binding.cardForeground.translationX <= -width / 2
                if (reveal) {
                    opened = holder
                    holder.binding.layoutUnderlayActions.visibility = View.VISIBLE
                    holder.binding.cardForeground.animate().translationX(-width).setDuration(160).start()
                } else close(holder)
                candidate = null
                active = false
            }
            MotionEvent.ACTION_CANCEL -> {
                close(holder)
                candidate = null
                active = false
            }
        }
    }
    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    fun closeRevealed() { close(opened) }
    private fun draw(holder: JournalEntriesAdapter.EntryViewHolder, translation: Float) {
        holder.binding.cardForeground.animate().cancel()
        holder.binding.cardForeground.translationX = translation
        onRevealChanged(translation < 0)
        holder.binding.layoutUnderlayActions.visibility = if (translation < 0) View.VISIBLE else View.INVISIBLE
    }
    private fun close(holder: JournalEntriesAdapter.EntryViewHolder?) {
        if (holder == null) return
        onRevealChanged(false)
        holder.binding.cardForeground.animate().cancel()
        holder.binding.cardForeground.animate().translationX(0f).setDuration(160)
            .withEndAction { holder.binding.layoutUnderlayActions.visibility = View.INVISIBLE }.start()
        if (opened === holder) opened = null
    }
}
