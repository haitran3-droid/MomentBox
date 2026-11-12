package com.ntq.training.momentbox.feature.reminder

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class ReminderRingPhase { IDLE, PREPARING, RINGING, SILENT, FOCUS_DENIED, INTERRUPTED, DISMISSED, TIMED_OUT, ERROR }
data class ReminderRingState(
    val event: ReminderEvent? = null, val phase: ReminderRingPhase = ReminderRingPhase.IDLE,
    val volume: Float = 0f, val elapsedMillis: Long = 0, val error: String? = null
)

/** Driver Android phải callback về cùng owner thread của controller. Start có ACK riêng để
 * không gọi trạng thái RINGING khi MediaPlayer mới đang prepare hoặc chỉ vừa nhận lệnh start. */
interface ReminderRingDriver {
    fun canPlay(): Boolean
    fun acquire(onInterrupted: () -> Unit): Boolean
    fun prepare(onReady: () -> Unit, onError: (String) -> Unit)
    fun start(onStarted: () -> Unit, onError: (String) -> Unit)
    fun volume(value: Float)
    fun release()
}

/** Policy độc lập Android: service gọi trên Main, driver sở hữu MediaPlayer trên cùng Main; descriptor mở ở IO.
 * now phải là monotonic clock (production dùng elapsedRealtime), không dùng wall clock để
 * thay đổi giờ hệ thống vô tình kéo dài chuông. Unit kiểm policy; không thay proof âm thanh thật. */
class ReminderRingController(
    private val driver: ReminderRingDriver,
    private val scope: CoroutineScope,
    private val now: () -> Long
) {
    private val mutable = MutableStateFlow(ReminderRingState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var timer: Job? = null
    private var ownsDriver = false
    private var starting = false
    private var startedAt = 0L
    private fun active() =
        mutable.value.phase in listOf(ReminderRingPhase.PREPARING, ReminderRingPhase.RINGING)

    fun start(event: ReminderEvent) {
        // Broadcast/Service intent lặp không khởi động lại ramp hoặc hồi sinh alarm đã timeout.
        if (mutable.value.event == event && mutable.value.phase != ReminderRingPhase.IDLE) return
        stop()
        val epoch = ++generation
        starting = false
        startedAt = 0L
        mutable.value = ReminderRingState(event, ReminderRingPhase.PREPARING)
        try {
            if (!driver.canPlay()) {
                finish(ReminderRingPhase.SILENT)
                return
            }
            ownsDriver = true
            if (!driver.acquire { if (epoch == generation) finish(ReminderRingPhase.INTERRUPTED) }) {
                if (epoch == generation && active()) finish(ReminderRingPhase.FOCUS_DENIED)
                return
            }
            if (epoch != generation || !active()) return
            timer = scope.launch {
                delay(5000)
                if (epoch == generation && active()) finish(
                    ReminderRingPhase.ERROR,
                    "PREPARE_TIMEOUT"
                )
            }
            driver.prepare(onReady = {
                if (epoch == generation && active() && !starting) {
                    starting = true
                    try {
                        driver.volume(0f)
                        driver.start(onStarted = {
                            if (epoch == generation && mutable.value.phase == ReminderRingPhase.PREPARING) {
                                timer?.cancel()
                                startedAt = now()
                                mutable.value =
                                    mutable.value.copy(phase = ReminderRingPhase.RINGING)
                                timer = scope.launch { ramp(epoch) }
                            }
                        }, onError = { code -> fail(epoch, code) })
                    } catch (_: Exception) {
                        fail(epoch, "START_FAILED")
                    }
                }
            }, onError = { code -> fail(epoch, code) })
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (_: Exception) {
            fail(epoch, "AUDIO_FAILED")
        }
    }

    fun stop() {
        if (active()) finish(ReminderRingPhase.DISMISSED)
    }

    private fun fail(epoch: Long, code: String) {
        if (epoch == generation && active()) finish(ReminderRingPhase.ERROR, code)
    }

    private suspend fun ramp(epoch: Long) {
        try {
            while (epoch == generation && mutable.value.phase == ReminderRingPhase.RINGING) {
                val elapsed = (now() - startedAt).coerceAtLeast(0)
                if (elapsed >= 60000) {
                    finish(ReminderRingPhase.TIMED_OUT)
                    return
                }
                val volume = (elapsed / 10000f).coerceIn(0f, 1f)
                driver.volume(volume)
                mutable.value = mutable.value.copy(volume = volume, elapsedMillis = elapsed)
                delay(100)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(epoch, "PLAYBACK_FAILED")
        }
    }

    private fun finish(phase: ReminderRingPhase, error: String? = null) {
        val elapsed =
            if (mutable.value.phase == ReminderRingPhase.RINGING) (now() - startedAt).coerceAtLeast(
                0
            ) else mutable.value.elapsedMillis
        ++generation
        timer?.cancel()
        timer = null
        try {
            if (ownsDriver) {
                ownsDriver = false
                driver.release()
            }
        } finally {
            mutable.value = mutable.value.copy(
                phase = phase,
                volume = 0f,
                elapsedMillis = elapsed,
                error = error
            )
        }
    }
}
