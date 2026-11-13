package com.ntq.training.momentbox.debug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.ntq.training.momentbox.R
import leakcanary.LeakCanary

/** Deliberately retains only this debug screen. Each run is bounded and can be cleared manually. */
class LeakLabActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val spacing = (20 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing, spacing, spacing)
        }
        panel.addView(TextView(this).apply { setText(R.string.s23_leak_instructions) })
        fun action(label: Int, click: () -> Unit) {
            panel.addView(MaterialButton(this).apply {
                setText(label)
                setOnClickListener { click() }
            })
        }
        action(R.string.s23_leak_static) {
            clearRetainedReferences()
            retainedActivity = this
            handler.postDelayed(::clearRetainedReferences, LAB_DURATION_MS)
            finishAndAnalyze()
        }
        action(R.string.s23_leak_listener) {
            clearRetainedReferences()
            LeakLabListeners.callbacks += { title }
            handler.postDelayed(::clearRetainedReferences, LAB_DURATION_MS)
            finishAndAnalyze()
        }
        action(R.string.s23_leak_handler) {
            clearRetainedReferences()
            handler.postDelayed({ title }, LAB_DURATION_MS)
            finishAndAnalyze()
        }
        action(R.string.s23_leak_clear, ::clearRetainedReferences)
        val scroll = ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun finishAndAnalyze() {
        finish()
        // Give LeakCanary time to observe onDestroy before requesting a heap dump.
        handler.postDelayed(analyze, 6000L)
    }

    companion object {
        private const val LAB_DURATION_MS = 60_000L
        private val handler = Handler(Looper.getMainLooper())
        private val analyze = Runnable { LeakCanary.dumpHeap() }
        private var retainedActivity: LeakLabActivity? = null

        private fun clearRetainedReferences() {
            handler.removeCallbacksAndMessages(null)
            retainedActivity = null
            LeakLabListeners.callbacks.clear()
        }
    }
}

private object LeakLabListeners {
    val callbacks = mutableListOf<() -> CharSequence>()
}
