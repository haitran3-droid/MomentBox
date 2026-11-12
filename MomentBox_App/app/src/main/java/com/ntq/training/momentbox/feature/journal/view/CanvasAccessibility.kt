package com.ntq.training.momentbox.feature.journal.view

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

internal data class CanvasNode(
    val id: Int,
    val bounds: Rect,
    val description: String,
    val enabled: Boolean = true,
    val selected: Boolean = false
)

/** Canvas không tự sinh node con như TextView; cung cấp từng ngày/sector cho TalkBack và bàn phím. */
internal class CanvasAccessibility(
    host: View, private val nodes: () -> List<CanvasNode>,
    private val hit: (Float, Float) -> Int?, private val activate: (Int) -> Unit
) : ExploreByTouchHelper(host) {
    override fun getVirtualViewAt(x: Float, y: Float) = hit(x, y) ?: INVALID_ID
    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        ids.addAll(nodes().map { it.id })
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val item = nodes().firstOrNull { it.id == id }
        node.contentDescription = item?.description ?: " "
        node.setBoundsInParent(item?.bounds ?: Rect(0, 0, 1, 1))
        node.className = "android.widget.Button"
        node.isEnabled = item?.enabled ?: false
        node.isSelected = item?.selected ?: false
        if (item?.enabled == true) {
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || nodes().none { it.id == id && it.enabled }) return false
        activate(id)
        invalidateRoot()
        return true
    }
}
