package com.ntq.training.momentbox.debug

import android.R as AndroidR
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isNotEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.NetworkLogEntry
import com.ntq.training.momentbox.core.network.NetworkLogInterceptor
import com.ntq.training.momentbox.core.network.NetworkModule
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.core.realtime.SocketPhase
import com.ntq.training.momentbox.core.realtime.SocketTuning
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.feature.settings.ImageCacheRepository
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
        SettingsDataStore.SOURCE_OFFLINE,
        SettingsDataStore.SOURCE_DUMMY_JSON,
        SettingsDataStore.SOURCE_MB_SERVER
    )
    private val sourceNames = arrayOf("Offline", "DummyJSON", "MB Server")
    private lateinit var panel: LinearLayout
    private lateinit var memoryStatus: TextView
    private var operation: Job? = null
    private var delayUpdate: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = column().apply { setBackgroundColor(getColor(R.color.background)) }
        root.addView(MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_s22_arrow_back)
            setNavigationIconTint(getColor(R.color.text_primary))
            navigationContentDescription = getString(R.string.s23_back)
            setNavigationOnClickListener { finish() }
            addView(row().apply {
                addView(label(getString(R.string.s22_developer), 20f, R.color.text_primary, bold = true).apply {
                    ViewCompat.setAccessibilityHeading(this, true)
                })
                addView(label("DEBUG", 10f, R.color.s22_developer_icon, bold = true).apply {
                    background = rounded(R.color.s22_developer_bg, 7)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, Toolbar.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, -2))
        panel = column().apply { setPadding(dp(16), 0, dp(16), dp(20)) }
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        section(R.string.s23_source)
        val segments = row().apply {
            background = rounded(R.color.surface_variant, 12)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val sourceButtons = sources.mapIndexed { index, source ->
            button(sourceNames[index], height = 40) { if (!it.isSelected) confirmSource(source) }.also {
                segments.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        panel.addView(segments)
        observe {
            settings.dataSourceFlow.collect { source ->
                sourceButtons.forEachIndexed { index, button ->
                    button.isSelected = sources[index] == source
                    button.backgroundTintList = if (button.isSelected) tint(R.color.surface) else ColorStateList.valueOf(0)
                    button.setTextColor(getColor(if (button.isSelected) R.color.text_primary else R.color.text_secondary))
                    button.setTypeface(button.typeface, if (button.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    button.elevation = if (button.isSelected) dp(2).toFloat() else 0f
                }
            }
        }
        if (intent.getBooleanExtra(DebugToolsEntry.EXTRA_SOURCE_ONLY, false)) return

        val simulation = card(top = 8, padding = 0)
        simulation.addView(MaterialSwitch(this).apply {
            text = getString(R.string.s23_simulate_401)
            setTextColor(getColor(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro)
            textSize = 13f
            minHeight = dp(45)
            minimumHeight = dp(45)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            switchMinWidth = dp(44)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (operation?.isActive == true) {
                        isChecked = false
                    } else {
                        isEnabled = false
                        runAction {
                            try {
                                simulate401()
                            } finally {
                                isChecked = false
                                isEnabled = true
                            }
                        }
                    }
                }
            }
        })
        simulation.addView(divider())
        val delayValue = label("", 11f, R.color.text_secondary).apply { typeface = Typeface.MONOSPACE }
        val slider = Slider(this).apply {
            valueTo = 5000f
            contentDescription = getString(R.string.s23_delay)
            trackHeight = dp(4)
            trackStopIndicatorSize = 0
            thumbTrackGapSize = 0
            trackActiveTintList = tint(R.color.primary)
            trackInactiveTintList = tint(R.color.surface_variant)
            thumbRadius = 0
            haloRadius = dp(12)
            labelBehavior = LabelFormatter.LABEL_GONE
            addOnChangeListener { _, value, fromUser ->
                val progress = value.toInt()
                delayValue.text = getString(R.string.s23_dashboard_ms, progress.toLong())
                if (fromUser) {
                    delayUpdate?.cancel()
                    delayUpdate = lifecycleScope.launch {
                        delay(150)
                        try {
                            settings.setDebugDelay(progress)
                        } catch (_: IOException) {
                            Snackbar.make(panel, R.string.s23_action_failed, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        simulation.addView(row().apply {
            setPadding(dp(14), 0, dp(14), 0)
            addView(label(getString(R.string.s23_delay), 14f, R.color.text_primary), weighted())
            addView(slider, LinearLayout.LayoutParams(0, -2, 0.85f))
            addView(delayValue)
        })
        observe {
            settings.debugDelayMsFlow.collect {
                if (delayUpdate?.isActive != true) slider.value = it.toFloat()
                delayValue.text = getString(R.string.s23_dashboard_ms, slider.value.toLong())
            }
        }

        section(R.string.s23_dashboard_network, R.string.s23_clear_logs, NetworkLogInterceptor::clearLogs)
        val logCard = card(padding = 0)
        observe {
            NetworkLogInterceptor.logsFlow.collect { logs ->
                logCard.removeAllViews()
                if (logs.isEmpty()) {
                    logCard.addView(label(getString(R.string.s23_no_requests), 13f, R.color.text_secondary).apply {
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    })
                }
                val preview = logs.take(3)
                preview.forEachIndexed { index, entry ->
                    if (index > 0) logCard.addView(divider())
                    val logRow = logPreviewRow(entry)
                    if (logs.size > preview.size) {
                        logRow.setOnLongClickListener {
                            showLogs()
                            true
                        }
                        ViewCompat.replaceAccessibilityAction(
                            logRow, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                            getString(R.string.s23_show_logs)
                        ) { _, _ ->
                            showLogs()
                            true }
                    }
                    logCard.addView(logRow)
                }
            }
        }

        section(R.string.s23_dashboard_socket)
        val socketCard = card()
        val socketDot = View(this)
        val socketStatus = label("", 13f, R.color.text_primary, bold = true)
        val socketId = label("", 11f, R.color.text_secondary).apply {
            typeface = Typeface.MONOSPACE
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            gravity = Gravity.END
        }
        val socketTuning = label("", 11f, R.color.text_secondary).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(7), 0, dp(4))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val socketDetails = column().apply {
            setPadding(0, dp(4), 0, 0)
            addView(row().apply {
                addView(socketDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                addView(socketStatus)
                addView(socketId, weighted().apply { marginStart = dp(8) })
            })
            addView(socketTuning)
        }
        socketCard.addView(socketDetails)
        socketCard.addView(row().apply {
            addView(button(getString(R.string.s23_disconnect), outlined = true, height = 38) { socket.disconnect() }, weighted())
            addView(button(getString(R.string.s23_connect), filled = true, height = 38) {
                runAction {
                    requireServerSession()
                    app.chatRuntime.activate()
                    socket.connect()
                }
            }, weighted().apply { marginStart = dp(8) })
        })
        observe {
            combine(socket.state, socket.connectionIdFlow, socket.appliedTuningFlow, settings.socketTuningFlow) {
                state, id, applied, configured ->
                val phase = state.phase.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase() }
                val color = if (state.phase == SocketPhase.CONNECTED) R.color.success else R.color.text_secondary
                val tuning = applied ?: configured
                socketStatus.text = phase
                val shortId = id?.let { if (it.length > 8) it.take(6) + "…" else it } ?: "—"
                socketId.text = getString(R.string.s23_dashboard_socket_id, shortId)
                socketId.contentDescription = getString(R.string.s23_dashboard_socket_id, id ?: "—")
                socketDot.background = rounded(color, 4)
                socketTuning.text = getString(
                    R.string.s23_dashboard_socket_tuning, tuning.timeoutMs, tuning.attempts,
                    tuning.delayMinMs, tuning.delayMaxMs, tuning.randomization.toString()
                )
                socketDetails.setOnClickListener {
                    showSocketActions(state.attempt, configured, applied)
                }
            }.collect { }
        }

        section(R.string.s23_memory)
        val memory = card()
        memoryStatus = label("", 12f, R.color.text_secondary)
        memory.addView(row().apply {
            addView(memoryStatus, weighted())
            addView(button(getString(R.string.s23_dashboard_prefs), height = 36) {
                runAction {
                    val title = getString(R.string.s23_pref_keys)
                    val keys = settings.preferenceNames()
                    showText(title, keys.joinToString("\n").ifEmpty { "—" })
                }
            })
        })
        memory.addView(row().apply {
            addView(leakButton(R.string.s23_dashboard_static, R.string.s23_leak_static))
            addView(leakButton(R.string.s23_dashboard_listener, R.string.s23_leak_listener), LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(6)
            })
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
        memory.addView(row().apply {
            addView(leakButton(R.string.s23_dashboard_handler, R.string.s23_leak_handler))
        })
        runAction { showMemory() }
    }

    private fun showSocketActions(attempt: Int, configured: SocketTuning, applied: SocketTuning?) {
        val details = buildString {
            append(getString(R.string.s23_attempts)).append(" · ").append(attempt)
            append("\n").append(getString(R.string.s23_saved)).append(" ").append(tuningText(configured))
            if (applied != null) {
                append("\n").append(getString(R.string.s23_active)).append(" ").append(tuningText(applied))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s23_dashboard_socket)
            .setMessage(details)
            .setPositiveButton(R.string.s23_socket_options) { _, _ -> runAction { editSocket() } }
            .setNeutralButton(R.string.s23_outage) { _, _ ->
                runAction { check(api.dropSocket(requireServerSession(), 10).success) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun leakButton(label: Int, scenario: Int) =
        button(getString(label), outlined = true, color = R.color.error, height = 32) {
            startActivity(LeakLabActivity.intent(this, scenario))
        }.apply {
            cornerRadius = dp(18)
            contentDescription = getString(scenario)
        }

    private fun observe(block: suspend () -> Unit) {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { block() } }
    }

    private fun confirmSource(target: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.s23_switch_source_notice)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                runAction {
                    if (target != settings.getDataSource()) {
                        if (app.authRepository.isLoggedIn()) {
                            app.logoutCoordinator.start(keepJournal = true, nextSource = target).join()
                        } else {
                            app.authRepository.changeDataSource(target)
                        }
                    }
                }
            }.show()
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
        val details = getString(
            R.string.s23_memory_status,
            Formatter.formatFileSize(this, disk),
            Formatter.formatFileSize(this, cache.currentSize),
            Formatter.formatFileSize(this, cache.maxSize),
            Formatter.formatFileSize(this, runtime.totalMemory() - runtime.freeMemory())
        )
        memoryStatus.text = getString(
            R.string.s23_dashboard_glide,
            Formatter.formatFileSize(this, cache.currentSize),
            Formatter.formatFileSize(this, disk)
        )
        memoryStatus.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.s23_memory)
                .setMessage(details)
                .setPositiveButton(R.string.s23_refresh_memory) { _, _ -> runAction { showMemory() } }
                .setNeutralButton(R.string.s23_dashboard_leak_lab) { _, _ ->
                    startActivity(Intent(this, LeakLabActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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

    private fun logPreviewRow(entry: NetworkLogEntry) = row().apply {
        val failed = entry.statusCode == 0 || entry.statusCode >= 400
        if (failed) setBackgroundColor(getColor(R.color.error_container))
        minimumHeight = dp(39)
        setPadding(dp(14), 0, dp(14), 0)
        val parsed = entry.url.toUri()
        val path = buildString {
            append(parsed.encodedPath.orEmpty().ifEmpty { "/" })
            parsed.encodedQuery?.let { append('?').append(it) }
        }
        val result = if (entry.statusCode == 0) "I/O" else entry.statusCode.toString()
        val duration = getString(R.string.s23_dashboard_ms, entry.durationMs)
        val methodColor = if (entry.method == "GET") R.color.s23_log_success else R.color.s22_developer_icon
        addView(mono(entry.method, methodColor, bold = true), LinearLayout.LayoutParams(dp(42), -2))
        addView(mono(path, R.color.text_primary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(6), 0)
        }, weighted())
        addView(mono(result, if (failed) R.color.error else R.color.s23_log_success, bold = true))
        addView(mono(duration, R.color.text_secondary).apply { setPadding(dp(6), 0, 0, 0) })
        contentDescription = logSummary(entry)
        val selectable = TypedValue()
        theme.resolveAttribute(AndroidR.attr.selectableItemBackground, selectable, true)
        foreground = AppCompatResources.getDrawable(context, selectable.resourceId)
        setOnClickListener { showLog(entry) }
    }

    private fun section(title: Int, action: Int? = null, click: () -> Unit = {}) {
        panel.addView(row().apply {
            minimumHeight = dp(32)
            setPadding(dp(4), 0, dp(4), 0)
            addView(label(getString(title), 12f, R.color.text_secondary, bold = true).apply {
                setAllCaps(true)
                letterSpacing = 0.04f
                ViewCompat.setAccessibilityHeading(this, true)
            }, weighted())
            if (action != null) addView(button(getString(action), height = 36) { click() })
        }, LinearLayout.LayoutParams(-1, -2).apply {
            if (panel.isNotEmpty()) topMargin = dp(5)
        })
    }

    private fun card(top: Int = 0, padding: Int = 14): LinearLayout = column().apply {
        background = rounded(R.color.surface, 16)
        clipToOutline = true
        val vertical = if (padding == 0) 0 else dp(7)
        setPadding(dp(padding), vertical, dp(padding), vertical)
        panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun mono(value: String, color: Int, bold: Boolean = false) =
        label(value, 11f, color).apply {
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        setTypeface(ResourcesCompat.getFont(context, R.font.be_vietnam_pro), if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun button(
        title: String,
        outlined: Boolean = false,
        filled: Boolean = false,
        color: Int = R.color.primary,
        height: Int = 40,
        click: (MaterialButton) -> Unit
    ) = MaterialButton(this).apply {
        text = title
        textSize = 13f
        typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold)
        isAllCaps = false
        minHeight = dp(height)
        minimumHeight = dp(height)
        minWidth = 0
        minimumWidth = 0
        cornerRadius = dp(10)
        insetTop = dp(3)
        insetBottom = dp(3)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        backgroundTintList = if (filled) tint(R.color.primary_container) else ColorStateList.valueOf(0)
        setTextColor(getColor(if (filled) R.color.on_primary_container else if (outlined && color == R.color.primary) R.color.text_primary else color))
        if (outlined) {
            strokeWidth = dp(1)
            strokeColor = tint(if (color == R.color.primary) R.color.outline else R.color.s23_leak_outline)
        }
        setOnClickListener { click(this) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(getColor(color))
    }

    private fun tint(color: Int) = ColorStateList.valueOf(getColor(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
