package com.ntq.training.momentbox.feature.chat

import com.ntq.training.momentbox.core.realtime.NotificationScope
import com.ntq.training.momentbox.core.realtime.RealtimeSocket
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.core.realtime.SocketPhase
import com.ntq.training.momentbox.data.local.db.ConversationEntity
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.local.prefs.PersistedTokens
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenPersistence
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.repository.ChatClockSkew
import com.ntq.training.momentbox.data.repository.recalibrateUnconfirmedTimestamps
import com.ntq.training.momentbox.data.repository.resolveSyncedConversation
import com.ntq.training.momentbox.data.repository.shouldAdvancePreviewForServerMessage
import com.ntq.training.momentbox.feature.reminder.ReminderEvent
import com.ntq.training.momentbox.feature.reminder.ReminderKind
import com.ntq.training.momentbox.feature.reminder.ReminderNotifications
import com.ntq.training.momentbox.feature.reminder.ReminderRingController
import com.ntq.training.momentbox.feature.reminder.ReminderRingDriver
import com.ntq.training.momentbox.feature.reminder.ReminderRingPhase
import com.ntq.training.momentbox.feature.reminder.ReminderTiming
import io.socket.client.Socket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPlayerReminderRegressionTest {
    @Test
    fun clockSkewAheadOffsetsOptimisticTimestampAndAnchorsAfterLatestConfirmed() {
        val skew = ChatClockSkew()
        // Device clock is +22s ahead of server clock.
        val offset = skew.observeLiveServerTime(serverTimeMs = 1_000_000L, localNowMs = 1_022_000L)
        assertEquals(-22_000L, offset)

        // Offline #fail message sent 1s later on skewed device clock gets server-aligned timestamp.
        val failCreated = skew.optimisticTimestamp(
            localNowMs = 1_023_000L,
            latestConfirmedAt = 1_000_000L
        )
        assertEquals(1_001_000L, failCreated)
        assertTrue(failCreated < 1_005_000L)

        // If adjusted local clock slightly lags latest confirmed message, anchor to confirmed + 1.
        val anchored = skew.optimisticTimestamp(
            localNowMs = 1_021_500L,
            latestConfirmedAt = 1_000_000L
        )
        assertEquals(1_000_001L, anchored)
    }

    @Test
    fun uncalibratedFailedMessagesRecalibrateStrictlyBeforeNewerServerMessage() {
        val fail1 = sampleMessage(
            localId = "client:fail-1",
            serverId = null,
            content = "9901#fail",
            status = "FAILED",
            createdAt = 1_023_000L
        )
        val fail2 = sampleMessage(
            localId = "client:fail-2",
            serverId = null,
            content = "9901b#fail",
            status = "FAILED",
            createdAt = 1_024_000L
        )
        val updates = recalibrateUnconfirmedTimestamps(
            candidatesNewestFirst = listOf(fail2, fail1),
            liveServerTimeMs = 1_005_000L,
            clockOffsetMs = -22_000L,
            uncalibratedLocalTimes = mapOf(
                fail1.localId to 1_023_000L,
                fail2.localId to 1_024_000L
            )
        )
        assertEquals(1_001_000L, updates[fail1.localId])
        assertEquals(1_002_000L, updates[fail2.localId])
        assertTrue(updates.getValue(fail1.localId) < updates.getValue(fail2.localId))
        assertTrue(updates.getValue(fail2.localId) < 1_005_000L)

        // Even after process restart (no in-memory uncalibrated map), future-skewed FAILED rows
        // are still clamped below the live server message while preserving relative ordering.
        val restartUpdates = recalibrateUnconfirmedTimestamps(
            candidatesNewestFirst = listOf(fail2, fail1),
            liveServerTimeMs = 1_005_000L,
            clockOffsetMs = -22_000L,
            uncalibratedLocalTimes = emptyMap()
        )
        assertEquals(1_004_999L, restartUpdates[fail2.localId])
        assertEquals(1_004_998L, restartUpdates[fail1.localId])
    }

    @Test
    fun skewedFailedMessageCannotFreezeSyncedOrLiveConversationPreview() {
        val previousFrozen = sampleConversation(
            preview = "9901#fail",
            updatedAt = 1_023_000L,
            unreadCount = 0
        )
        val serverRow = sampleConversation(
            preview = "Bot reply",
            updatedAt = 1_006_000L,
            unreadCount = 1
        )
        val oldConfirmed = sampleMessage(
            localId = "server:10",
            serverId = 10L,
            content = "old",
            status = "SENT",
            createdAt = 995_000L
        )

        // Authoritative sync unfreezes the preview when no newer confirmed or SENDING message exists.
        val synced = resolveSyncedConversation(
            serverRow = serverRow,
            previous = previousFrozen,
            localNewestConfirmed = oldConfirmed,
            remoteLastMessageId = 12L,
            inFlightSending = null
        )
        assertEquals("Bot reply", synced.lastMessagePreview)
        assertEquals(1_006_000L, synced.updatedAt)
        assertEquals(1, synced.unreadCount)

        // A live confirmed socket message (#13) newer than stale REST (#12) is still preserved.
        val newerConfirmed = sampleMessage(
            localId = "server:13",
            serverId = 13L,
            content = "Live msg",
            status = "SENT",
            createdAt = 1_008_000L
        )
        val previousWithLive = sampleConversation(
            preview = "Live msg",
            updatedAt = 1_008_000L,
            unreadCount = 2
        )
        val preservedLive = resolveSyncedConversation(
            serverRow = serverRow,
            previous = previousWithLive,
            localNewestConfirmed = newerConfirmed,
            remoteLastMessageId = 12L,
            inFlightSending = null
        )
        assertEquals("Live msg", preservedLive.lastMessagePreview)
        assertEquals(2, preservedLive.unreadCount)

        // An actively in-flight SENDING message keeps preview while adopting server unread count.
        val sending = sampleMessage(
            localId = "client:sending-1",
            serverId = null,
            content = "Sending now",
            status = "SENDING",
            createdAt = 1_007_000L
        )
        val mergedSending = resolveSyncedConversation(
            serverRow = serverRow,
            previous = previousFrozen,
            localNewestConfirmed = oldConfirmed,
            remoteLastMessageId = 12L,
            inFlightSending = sending
        )
        assertEquals("Sending now", mergedSending.lastMessagePreview)
        assertEquals(1_007_000L, mergedSending.updatedAt)
        assertEquals(1, mergedSending.unreadCount)

        // Live server message advances preview over inflated #fail updatedAt, but not out-of-order older msg.
        assertTrue(
            shouldAdvancePreviewForServerMessage(
                messageCreatedAt = 1_005_000L,
                messageServerId = 11L,
                conversationUpdatedAt = 1_023_000L,
                newestConfirmedServerId = 11L,
                inFlightSending = null
            )
        )
        assertFalse(
            shouldAdvancePreviewForServerMessage(
                messageCreatedAt = 990_000L,
                messageServerId = 9L,
                conversationUpdatedAt = 1_006_000L,
                newestConfirmedServerId = 12L,
                inFlightSending = null
            )
        )
    }

    @Test
    fun socketManagerRecoversFromAuthExpiredAndWaitingOnManualReconnect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val socketScope = TestScope(dispatcher)
        val tokenStore = TokenStore(InMemoryTokenPersistence())
        tokenStore.saveTokens("expired-token", "refresh-token", rememberMe = true)
        val settings = FakeSettingsStorage(SettingsDataStore.SOURCE_MB_SERVER)
        val transport = FakeRealtimeSocket()
        val manager = SocketManager(
            tokenStore = tokenStore,
            settingsStorage = settings,
            baseUrlProvider = { "http://127.0.0.1:3000/" },
            scope = socketScope,
            notificationScopeProvider = {
                NotificationScope("42", SettingsDataStore.SOURCE_MB_SERVER)
            },
            socketFactory = { _, _ -> transport }
        )

        try {
            manager.connect()
            socketScope.advanceUntilIdle()
            assertEquals(SocketPhase.CONNECTING, manager.state.value.phase)
            assertEquals(1, transport.connectCalls)

            transport.emit(Socket.EVENT_CONNECT_ERROR, "TOKEN_EXPIRED")
            assertEquals(SocketPhase.AUTH_EXPIRED, manager.state.value.phase)

            // Retry after temporary network outage must transition out of AUTH_EXPIRED instead of ignoring.
            manager.reconnectNow()
            socketScope.advanceUntilIdle()
            assertEquals(SocketPhase.CONNECTING, manager.state.value.phase)
            assertEquals(2, transport.connectCalls)

            // Transport error enters WAITING; explicit reconnectNow retries immediately.
            transport.emit(Socket.EVENT_CONNECT_ERROR, "timeout")
            assertEquals(SocketPhase.WAITING, manager.state.value.phase)

            manager.reconnectNow()
            socketScope.advanceUntilIdle()
            assertEquals(SocketPhase.CONNECTING, manager.state.value.phase)
            assertEquals(3, transport.connectCalls)

            transport.emit(Socket.EVENT_CONNECT)
            assertEquals(SocketPhase.CONNECTED, manager.state.value.phase)
            assertTrue(manager.isConnectedFlow.value)
        } finally {
            manager.disconnect()
            socketScope.advanceUntilIdle()
        }
    }

    @Test
    fun reminderFullScreenIntentOnlyEnabledForInitialForegroundExactAlert() {
        assertTrue(
            ReminderNotifications.shouldAttachFullScreenIntent(
                fullScreen = true,
                hasFullScreenCapability = true,
                hasExactCapability = true,
                timing = ReminderTiming.EXACT
            )
        )
        // Post-timeout or alarm-volume-0 ordinary downgrade must NOT re-post a second fullScreenIntent.
        assertFalse(
            ReminderNotifications.shouldAttachFullScreenIntent(
                fullScreen = false,
                hasFullScreenCapability = true,
                hasExactCapability = true,
                timing = ReminderTiming.EXACT
            )
        )
        assertFalse(
            ReminderNotifications.shouldAttachFullScreenIntent(
                fullScreen = true,
                hasFullScreenCapability = true,
                hasExactCapability = true,
                timing = ReminderTiming.INEXACT
            )
        )
        assertFalse(
            ReminderNotifications.shouldAttachFullScreenIntent(
                fullScreen = true,
                hasFullScreenCapability = false,
                hasExactCapability = true,
                timing = ReminderTiming.EXACT
            )
        )
    }

    @Test
    fun reminderRingControllerHandlesZeroVolumeSilentAndTimeoutCleanly() = runTest {
        var clockMs = 1_000L
        val silentDriver = FakeReminderRingDriver(playable = false)
        val silentController = ReminderRingController(silentDriver, this) { clockMs }
        val event = ReminderEvent(ReminderKind.DAILY, revision = 1L, dueEpochMillis = 10_000L)

        silentController.start(event)
        assertEquals(ReminderRingPhase.SILENT, silentController.state.value.phase)
        assertEquals(0, silentDriver.releaseCalls)

        val ringingDriver = FakeReminderRingDriver(playable = true)
        val ringingController = ReminderRingController(ringingDriver, this) { clockMs }
        ringingController.start(event)
        runCurrent()
        assertEquals(ReminderRingPhase.RINGING, ringingController.state.value.phase)

        clockMs += 60_000L
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(ReminderRingPhase.TIMED_OUT, ringingController.state.value.phase)
        assertEquals(1, ringingDriver.releaseCalls)
    }

    private fun sampleMessage(
        localId: String,
        serverId: Long?,
        content: String,
        status: String,
        createdAt: Long
    ) = MessageEntity(
        ownerId = "1",
        source = "MB_SERVER|http://127.0.0.1:3000",
        localId = localId,
        serverId = serverId,
        clientId = localId.removePrefix("client:"),
        conversationId = 99L,
        senderId = 1L,
        type = "text",
        content = content,
        mediaUrl = null,
        durationMs = null,
        status = status,
        createdAt = createdAt
    )

    private fun sampleConversation(
        preview: String,
        updatedAt: Long,
        unreadCount: Int
    ) = ConversationEntity(
        ownerId = "1",
        source = "MB_SERVER|http://127.0.0.1:3000",
        id = 99L,
        type = "direct",
        title = "Bot",
        avatarUrl = null,
        lastMessagePreview = preview,
        lastMessageType = "text",
        lastSenderId = 2L,
        unreadCount = unreadCount,
        updatedAt = updatedAt
    )

    private class InMemoryTokenPersistence : TokenPersistence {
        private var persisted = PersistedTokens(null, null, true, 0L)
        override suspend fun read(): PersistedTokens = persisted
        override suspend fun write(tokens: PersistedTokens) {
            persisted = tokens
        }
    }

    private class FakeSettingsStorage(initialSource: String) : SettingsStorage {
        private val source = MutableStateFlow(initialSource)
        private var savedUser: String? = null
        override val onboardingDoneFlow: Flow<Boolean> = flowOf(true)
        override val dataSourceFlow: Flow<String> = source
        override val debugDelayMsFlow: Flow<Int> = flowOf(0)
        override val debugForce401Flow: Flow<Boolean> = flowOf(false)
        override val configCacheFlow: Flow<String?> = flowOf(null)
        override suspend fun setOnboardingDone(done: Boolean) {}
        override suspend fun isOnboardingDone(): Boolean = true
        override suspend fun setDataSource(source: String) {
            this.source.value = source
        }

        override suspend fun getDataSource(): String = source.value
        override suspend fun setConfigCache(json: String) {}
        override suspend fun getConfigCache(): String? = null
        override suspend fun setDebugDelay(delayMs: Int) {}
        override suspend fun setDebugForce401(force: Boolean) {}
        override suspend fun isDebugForce401(): Boolean = false
        override suspend fun setSavedUserJson(json: String?) {
            savedUser = json
        }

        override suspend fun getSavedUserJson(): String? = savedUser
    }

    private class FakeRealtimeSocket : RealtimeSocket {
        private val listeners = mutableMapOf<String, MutableList<(Array<out Any>) -> Unit>>()
        var connectCalls = 0
        var disconnectCalls = 0

        override fun on(event: String, listener: (Array<out Any>) -> Unit) {
            listeners.getOrPut(event) { mutableListOf() }.add(listener)
        }

        override fun off(event: String, listener: (Array<out Any>) -> Unit) {
            listeners[event]?.remove(listener)
        }

        override fun connect() {
            connectCalls++
        }

        override fun disconnect() {
            disconnectCalls++
            listeners.clear()
        }

        fun emit(event: String, vararg args: Any) {
            listeners[event]?.toList()?.forEach { it(args) }
        }
    }

    private class FakeReminderRingDriver(private val playable: Boolean) : ReminderRingDriver {
        var releaseCalls = 0
        override fun canPlay(): Boolean = playable
        override fun acquire(onInterrupted: () -> Unit): Boolean = true
        override fun prepare(onReady: () -> Unit, onError: (String) -> Unit) = onReady()
        override fun start(onStarted: () -> Unit, onError: (String) -> Unit) = onStarted()
        override fun volume(value: Float) {}
        override fun release() {
            releaseCalls++
        }
    }
}
