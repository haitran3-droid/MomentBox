package com.ntq.training.momentbox.feature.journal

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class JournalSwipeCallback(
    context: Context,
    private val onRevealChanged: (Boolean) -> Unit = {}
) : RecyclerView.OnItemTouchListener, RecyclerView.OnChildAttachStateChangeListener {

    private val width = 132f * context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var base = 0f
    private var candidate: JournalEntriesAdapter.EntryViewHolder? = null
    private var active = false
    private var opened: JournalEntriesAdapter.EntryViewHolder? = null
    private var consumeClose = false
    private var isRevealed = false

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                active = false
                consumeClose = false
                candidate = rv.findChildViewUnder(event.x, event.y)
                    ?.let { rv.getChildViewHolder(it) } as? JournalEntriesAdapter.EntryViewHolder
                if (opened != null && opened !== candidate) {
                    close(opened)
                }
                candidate?.let { holder ->
                    val foreground = holder.binding.cardForeground
                    val right = holder.itemView.right + foreground.translationX
                    if (opened === holder && event.x > right) {
                        candidate = null // Underlay button owns this tap.
                    } else if (opened === holder) {
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

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                candidate = null
            }
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
                val reveal = holder.binding.cardForeground.translationX <= -width / 2f
                candidate = null
                active = false
                if (reveal) {
                    opened = holder
                    holder.binding.layoutUnderlayActions.visibility = View.VISIBLE
                    holder.binding.cardForeground.animate().translationX(-width).setDuration(160).start()
                    notifyRevealChanged(true)
                } else {
                    close(holder)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                candidate = null
                active = false
                close(holder)
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    override fun onChildViewAttachedToWindow(view: View) = Unit

    override fun onChildViewDetachedFromWindow(view: View) {
        var detachedTrackedHolder = false
        val currentOpened = opened
        if (currentOpened != null && currentOpened.itemView === view) {
            resetHolderImmediately(currentOpened)
            opened = null
            detachedTrackedHolder = true
        }
        val currentCandidate = candidate
        if (currentCandidate != null && currentCandidate.itemView === view) {
            resetHolderImmediately(currentCandidate)
            candidate = null
            active = false
            detachedTrackedHolder = true
        }
        if (detachedTrackedHolder && opened == null && candidate == null) {
            isRevealed = false
            onRevealChanged(false)
        }
    }

    fun closeRevealed() {
        val currentOpened = opened
        val currentCandidate = candidate
        opened = null
        candidate = null
        active = false
        consumeClose = false

        if (currentOpened != null) {
            closeHolder(currentOpened)
        }
        if (currentCandidate != null && currentCandidate !== currentOpened) {
            closeHolder(currentCandidate)
        }
        isRevealed = false
        onRevealChanged(false)
    }

    private fun draw(holder: JournalEntriesAdapter.EntryViewHolder, translation: Float) {
        holder.binding.cardForeground.animate().cancel()
        holder.binding.cardForeground.translationX = translation
        val revealed = translation < 0f
        notifyRevealChanged(revealed)
        holder.binding.layoutUnderlayActions.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
    }

    private fun close(holder: JournalEntriesAdapter.EntryViewHolder?) {
        if (holder != null) {
            if (opened === holder) opened = null
            closeHolder(holder)
        }
        isRevealed = false
        onRevealChanged(false)
    }

    private fun closeHolder(holder: JournalEntriesAdapter.EntryViewHolder) {
        val foreground = holder.binding.cardForeground
        foreground.animate().cancel()
        if (holder.itemView.isAttachedToWindow) {
            foreground.animate()
                .translationX(0f)
                .setDuration(160)
                .withEndAction {
                    holder.binding.layoutUnderlayActions.visibility = View.INVISIBLE
                }
                .start()
        } else {
            resetHolderImmediately(holder)
        }
    }

    private fun resetHolderImmediately(holder: JournalEntriesAdapter.EntryViewHolder) {
        holder.binding.cardForeground.animate().cancel()
        holder.binding.cardForeground.translationX = 0f
        holder.binding.layoutUnderlayActions.visibility = View.INVISIBLE
    }

    private fun notifyRevealChanged(revealed: Boolean) {
        if (isRevealed == revealed) return
        isRevealed = revealed
        onRevealChanged(revealed)
    }
}
