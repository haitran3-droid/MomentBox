package com.ntq.training.momentbox.debug

import android.content.Context
import android.content.Intent
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
        val scenario = intent.getIntExtra(EXTRA_SCENARIO, 0)
        if (scenario in scenarios) {
            if (savedInstanceState == null) runScenario(scenario) else finish()
            return
        }
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
        scenarios.forEach { scenario -> action(scenario) { runScenario(scenario) } }
        action(R.string.s23_leak_clear, ::clearRetainedReferences)
        val scroll = ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun runScenario(scenario: Int) {
        if (scenario !in scenarios) return
        clearRetainedReferences()
        when (scenario) {
            R.string.s23_leak_static -> retainedActivity = this
            R.string.s23_leak_listener -> LeakLabListeners.callbacks += { title }
            R.string.s23_leak_handler -> handler.postDelayed({ title }, LAB_DURATION_MS)
        }
        if (scenario != R.string.s23_leak_handler) {
            handler.postDelayed(::clearRetainedReferences, LAB_DURATION_MS)
        }
        finishAndAnalyze()
    }

    private fun finishAndAnalyze() {
        finish()
        // Give LeakCanary time to observe onDestroy before requesting a heap dump.
        handler.postDelayed(analyze, 6000L)
    }

    companion object {
        private const val EXTRA_SCENARIO = "leak_scenario"
        private val scenarios = listOf(R.string.s23_leak_static, R.string.s23_leak_listener, R.string.s23_leak_handler)

        fun intent(context: Context, scenario: Int): Intent =
            Intent(context, LeakLabActivity::class.java).putExtra(EXTRA_SCENARIO, scenario)

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
