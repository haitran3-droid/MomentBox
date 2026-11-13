package com.ntq.training.momentbox.debug

import android.R as AndroidR
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.Spanned
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.NetworkLogEntry
import com.ntq.training.momentbox.core.network.NetworkLogInterceptor
import com.ntq.training.momentbox.core.network.NetworkModule
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.core.realtime.SocketTuning
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.feature.settings.ImageCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Tag

private interface DebugApi {
    @POST("v1/debug/expire-token")
    suspend fun expireToken(@Tag session: SessionRequest): ApiResponse<Unit>

    @POST("v1/debug/socket/drop")
    suspend fun dropSocket(
        @Tag session: SessionRequest,
        @Query("outageSeconds") seconds: Int
    ): ApiResponse<Unit>
}

class DevToolsActivity : AppCompatActivity() {
    private val app get() = application as MomentBoxApp
    private val settings get() = app.settingsDataStore
    private val socket get() = app.socketManager
    private val api by lazy {
        val client = NetworkModule.provideOkHttpClient(app, app.tokenStore, settings)
        NetworkModule.provideRetrofit(client).create(DebugApi::class.java)
    }
    private val sources = arrayOf(
        SettingsDataStore.SOURCE_MB_SERVER,
        SettingsDataStore.SOURCE_DUMMY_JSON,
        SettingsDataStore.SOURCE_OFFLINE
    )
    private val sourceNames = arrayOf("MB Server", "DummyJSON", "Offline")
    private lateinit var panel: LinearLayout
    private lateinit var sourceStatus: TextView
    private lateinit var networkStatus: TextView
    private lateinit var socketStatus: TextView
    private lateinit var memoryStatus: TextView
    private var operation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.background))
            addView(panel)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        action(getString(R.string.s23_back)) { finish() }
        heading(getString(R.string.s22_developer))
        sourceStatus = text()
        action(getString(R.string.s23_source)) { runAction { chooseSource() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.dataSourceFlow.collect { source ->
                    sourceStatus.text = sourceNames.getOrElse(sources.indexOf(source)) { source }
                }
            }
        }
        if (intent.getBooleanExtra(DebugToolsEntry.EXTRA_SOURCE_ONLY, false)) return

        heading(getString(R.string.s23_network))
        networkStatus = text()
        action(getString(R.string.s23_simulate_401)) { runAction { simulate401() } }
        action(getString(R.string.s23_delay)) { runAction { editDelay() } }
        action(getString(R.string.s23_show_logs)) { showLogs() }
        action(getString(R.string.s23_clear_logs), NetworkLogInterceptor::clearLogs)

        heading("Socket.IO")
        socketStatus = text()
        action(getString(R.string.s23_socket_options)) { runAction { editSocket() } }
        action(getString(R.string.s23_disconnect), socket::disconnect)
        action(getString(R.string.s23_connect)) {
            runAction {
                requireServerSession()
                app.chatRuntime.activate()
                socket.connect()
            }
        }
        action(getString(R.string.s23_outage)) {
            runAction { check(api.dropSocket(requireServerSession(), 10).success) }
        }

        heading(getString(R.string.s23_memory))
        memoryStatus = text()
        action(getString(R.string.s23_refresh_memory)) { runAction { showMemory() } }
        action(getString(R.string.s23_pref_keys)) {
            runAction {
                val keys = settings.preferenceNames()
                showText(getString(R.string.s23_pref_keys), keys.joinToString("\n").ifEmpty { "—" })
            }
        }
        action("Leak lab") { startActivity(Intent(this, LeakLabActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(settings.debugDelayMsFlow, NetworkLogInterceptor.logsFlow) { delay, logs ->
                        getString(R.string.s23_log_count, delay, logs.size)
                    }.collect { networkStatus.text = it }
                }
                launch {
                    combine(socket.state, socket.connectionIdFlow, socket.appliedTuningFlow, settings.socketTuningFlow) {
                        state, id, applied, configured ->
                        buildString {
                            append(state.phase).append(" · #").append(state.attempt)
                            append("\nID ").append(id ?: "—")
                            append("\n").append(getString(R.string.s23_saved)).append(" ").append(tuningText(configured))
                            if (applied != null) {
                                append("\n").append(getString(R.string.s23_active)).append(" ").append(tuningText(applied))
                            }
                        }
                    }.collect { socketStatus.text = it }
                }
            }
        }
        runAction { showMemory() }
    }

    private suspend fun chooseSource() {
        val current = settings.getDataSource()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s23_source)
            .setSingleChoiceItems(sourceNames, sources.indexOf(current)) { dialog, selected ->
                dialog.dismiss()
                val target = sources[selected]
                if (target != current) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.s23_switch_source_notice)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(AndroidR.string.ok) { _, _ ->
                            runAction {
                                if (app.authRepository.isLoggedIn()) {
                                    app.logoutCoordinator.start(keepJournal = true, nextSource = target).join()
                                } else {
                                    app.authRepository.changeDataSource(target)
                                }
                            }
                        }.show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun requireServerSession(): SessionRequest {
        check(settings.getDataSource() == SettingsDataStore.SOURCE_MB_SERVER)
        val token = app.tokenStore.snapshot()
        check(!token.accessToken.isNullOrBlank())
        return SessionRequest(token.generation)
    }

    private suspend fun simulate401() {
        when (settings.getDataSource()) {
            SettingsDataStore.SOURCE_MB_SERVER -> check(api.expireToken(requireServerSession()).success)
            SettingsDataStore.SOURCE_DUMMY_JSON -> {
                check(app.authRepository.isLoggedIn())
                settings.setDebugForce401(true)
            }
            else -> error("Offline source")
        }
        try {
            app.authRepository.getMe().getOrThrow()
            Snackbar.make(panel, R.string.s23_refresh_completed, Snackbar.LENGTH_SHORT).show()
        } finally {
            withContext(NonCancellable) {
                if (settings.isDebugForce401()) settings.setDebugForce401(false)
            }
        }
    }

    private suspend fun editDelay() {
        val saved = settings.getDebugDelay()
        editNumbers(getString(R.string.s23_delay), listOf("0–5000 ms" to saved.toString())) { values ->
            val delay = values.single().toIntOrNull()?.takeIf { it in 0..5000 } ?: return@editNumbers false
            runAction { settings.setDebugDelay(delay) }
            true
        }
    }

    private suspend fun editSocket() {
        val current = settings.getSocketTuning()
        val fields = listOf(
            "Timeout (ms)" to current.timeoutMs.toString(),
            getString(R.string.s23_attempts) to current.attempts.toString(),
            "Delay min (ms)" to current.delayMinMs.toString(),
            "Delay max (ms)" to current.delayMaxMs.toString(),
            getString(R.string.s23_randomization) to current.randomization.toString()
        )
        editNumbers(getString(R.string.s23_socket_options), fields) { values ->
            val tuning = runCatching {
                SocketTuning(
                    values[0].toLong(), values[1].toInt(), values[2].toLong(),
                    values[3].toLong(), values[4].replace(',', '.').toDouble()
                )
            }.getOrNull() ?: return@editNumbers false
            runAction { settings.setSocketTuning(tuning) }
            true
        }
    }

    private fun editNumbers(title: String, fields: List<Pair<String, String>>, save: (List<String>) -> Boolean) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val inputs = fields.map { (label, value) ->
            val input = EditText(this).apply {
                id = View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(value)
            }
            form.addView(TextView(this).apply {
                text = label
                labelFor = input.id
            })
            form.addView(input)
            input
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(AndroidR.string.ok, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (save(inputs.map { it.text.toString().trim() })) dialog.dismiss()
            else inputs.first().error = getString(R.string.s23_invalid_value)
        }
    }

    private fun showLogs() {
        val logs = NetworkLogInterceptor.logsFlow.value
        if (logs.isEmpty()) {
            showText(getString(R.string.s23_network), getString(R.string.s23_no_requests))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s23_network)
            .setItems(logs.map(::logRow).toTypedArray()) { _, selected -> showLog(logs[selected]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun logSummary(entry: NetworkLogEntry): String {
        val result = if (entry.statusCode == 0) "I/O" else entry.statusCode.toString()
        return "${entry.method} $result · ${entry.durationMs} ms\n${entry.url}"
    }

    private fun logRow(entry: NetworkLogEntry): CharSequence {
        val color = when (entry.statusCode) {
            in 200..299 -> R.color.success
            in 300..399 -> R.color.primary
            else -> R.color.error
        }
        return SpannableString(logSummary(entry)).apply {
            setSpan(ForegroundColorSpan(getColor(color)), 0, indexOf('·'), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showLog(entry: NetworkLogEntry) {
        fun headers(value: Map<String, String>) = value.entries.joinToString("\n") { (key, field) -> "$key: $field" }
        showText(logSummary(entry), buildString {
            appendLine("Request").appendLine(headers(entry.requestHeaders))
            entry.requestBody?.let { appendLine().appendLine(it) }
            appendLine().appendLine("Response").appendLine(headers(entry.responseHeaders))
            entry.responseBody?.let { appendLine().appendLine(it) }
        })
    }

    private suspend fun showMemory() {
        val disk = ImageCacheRepository(this).diskBytes()
        Glide.get(this)
        val cache = DebugGlideModule.memoryCache
        val runtime = Runtime.getRuntime()
        memoryStatus.text = getString(
            R.string.s23_memory_status,
            Formatter.formatFileSize(this, disk),
            Formatter.formatFileSize(this, cache.currentSize),
            Formatter.formatFileSize(this, cache.maxSize),
            Formatter.formatFileSize(this, runtime.totalMemory() - runtime.freeMemory())
        )
    }

    private fun tuningText(tuning: SocketTuning): String =
        "${tuning.timeoutMs} ms / ${tuning.attempts} / ${tuning.delayMinMs}–${tuning.delayMaxMs} ms / ${tuning.randomization}"

    private fun runAction(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = lifecycleScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Snackbar.make(panel, R.string.s23_action_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showText(title: String, content: String) {
        val view = TextView(this).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(AndroidR.string.ok, null)
            .show()
    }

    private fun heading(value: String) {
        text().apply {
            text = value
            textSize = 20f
            setPadding(0, dp(16), 0, dp(4))
            ViewCompat.setAccessibilityHeading(this, true)
        }
    }

    private fun text(): TextView = TextView(this).also {
        it.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        it.textSize = 16f
        panel.addView(it)
    }

    private fun action(label: String, click: () -> Unit) {
        panel.addView(MaterialButton(this).apply {
            text = label
            minHeight = dp(48)
            isAllCaps = false
            setOnClickListener { click() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
