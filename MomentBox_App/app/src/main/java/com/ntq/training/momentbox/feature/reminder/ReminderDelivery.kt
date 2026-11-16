package com.ntq.training.momentbox.feature.reminder

import com.ntq.training.momentbox.data.local.prefs.ReminderStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class ReminderDeliveryResult { STALE, ALREADY_WRITTEN, PRESENTED, FAILED }

class ReminderDelivery(
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val hasWritten: suspend (ReminderDayWindow) -> Boolean,
    private val present: suspend (ReminderEvent, ReminderTiming) -> Boolean
) {
    suspend fun onAlarm(event: ReminderEvent, timing: ReminderTiming): ReminderDeliveryResult =
        withContext(Dispatchers.IO) {
            var claimed = false
            var presented = false
            try {
                // Hoàn tất ghi claim và nhận kết quả trong cùng vùng không hủy: nếu coroutine bị
                // timeout ngay sau disk commit, finally vẫn biết chính nó phải dọn active event.
                // Không đặt query Room/notification vào vùng này; hai thao tác đó vẫn hủy bình thường.
                withContext(NonCancellable) { claimed = scheduler.claim(event) }
                currentCoroutineContext().ensureActive()
                if (!claimed) return@withContext ReminderDeliveryResult.STALE
                if (!isCurrent(event)) return@withContext ReminderDeliveryResult.STALE
                val window = ReminderPolicy.dayWindow(clock.instant(), zone())
                if (hasWritten(window)) return@withContext ReminderDeliveryResult.ALREADY_WRITTEN
                // Settings/quyền/ngày có thể đổi trong lúc Room query. Đọc lại trước khi hiện UI.
                scheduler.reconcile()
                if (!isCurrent(event)) return@withContext ReminderDeliveryResult.STALE
                presented = present(event, timing)
                if (presented) ReminderDeliveryResult.PRESENTED else ReminderDeliveryResult.FAILED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReminderDeliveryResult.FAILED
            } finally {
                // Duplicate bị từ chối không được xóa active do lần delivery trước đang hiển thị.
                // finish có CAS event identity nên cleanup cũ cũng không tắt phiên nhắc mới hơn.
                if (claimed && !presented) withContext(NonCancellable + Dispatchers.IO) {
                    store.finish(
                        event
                    )
                }
            }
        }

    private suspend fun isCurrent(event: ReminderEvent): Boolean {
        val snapshot = store.snapshot()
        val capabilities = scheduler.state.value.capabilities
        val currentZone = zone()
        return snapshot.settings.enabled && snapshot.active == event &&
                capabilities?.notifications == true &&
                Instant.ofEpochMilli(event.dueEpochMillis).atZone(currentZone)
                    .toLocalDate() == clock.instant().atZone(currentZone).toLocalDate()
    }
}
