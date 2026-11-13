package com.ntq.training.momentbox

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.View
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.core.notification.ChatNotifications
import com.ntq.training.momentbox.core.realtime.NotificationScope
import com.ntq.training.momentbox.core.realtime.RealtimeSocket
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.prefs.ReminderStore
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.feature.chat.ChatActivity
import com.ntq.training.momentbox.feature.feed.FeedViewModel
import com.ntq.training.momentbox.feature.player.PlayerActivity
import com.ntq.training.momentbox.feature.player.PlayerService
import com.ntq.training.momentbox.feature.player.PlayerState
import com.ntq.training.momentbox.feature.profile.ProfilePageFragment
import com.ntq.training.momentbox.feature.reminder.ReminderAlarmBackend
import com.ntq.training.momentbox.feature.reminder.ReminderCapabilities
import com.ntq.training.momentbox.feature.reminder.ReminderDelivery
import com.ntq.training.momentbox.feature.reminder.ReminderDeliveryResult
import com.ntq.training.momentbox.feature.reminder.ReminderEvent
import com.ntq.training.momentbox.feature.reminder.ReminderKind
import com.ntq.training.momentbox.feature.reminder.ReminderScheduleStatus
import com.ntq.training.momentbox.feature.reminder.ReminderScheduler
import com.ntq.training.momentbox.feature.reminder.ReminderTiming
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5EmulatorFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MomentBoxApp
    private val args get() = InstrumentationRegistry.getArguments()

    @Before
    fun onlyOnTestEmulator() {
        val isTestEmulator = Build.PRODUCT.startsWith("sdk_gphone") ||
            (Build.PRODUCT == "sdk_phone_x86_64" && Build.HARDWARE == "ranchu")
        assumeTrue(args.getString("m5Fixture") == "true" && isTestEmulator)
        assertTrue(runBlocking { app.authRepository.isLoggedIn() })
        assertTrue(BuildConfig.BASE_URL.startsWith("http://127.0.0.1:") ||
            BuildConfig.BASE_URL.startsWith("http://10.0.2.2:"))
    }

    @Test
    fun sendFailsOfflineAndRetriesWithSameId() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            runBlocking {
                val runtime = app.chatRuntime
                val socket = app.socketManager
                val session = withTimeout(15_000) { runtime.session.first { it != null } }!!
                withTimeout(15_000) { socket.isConnectedFlow.first { it } }
                val room = runtime.repository.create(session, args.getString("peer")?.toLong() ?: 4L)
                val dao = app.database.chatDao()
                val rows = dao.messages(session.ownerId, session.source, room)
                socket.disconnect()
                val started = SystemClock.elapsedRealtime()
                val text = "M5-QA retry ${UUID.randomUUID()}"
                runtime.send(room, text)
                val failed = withTimeout(9_000) {
                    rows.first { records -> records.any { it.content == text && it.status == "FAILED" } }
                        .single { it.content == text }
                }
                val millis = SystemClock.elapsedRealtime() - started
                assertTrue("Failed after ${millis} ms", millis in 4_800..7_500)
                socket.connect()
                withTimeout(15_000) { socket.isConnectedFlow.first { it } }
                runtime.send(room, "", retry = failed.localId)
                val sent = withTimeout(12_000) {
                    rows.first { records -> records.any { it.content == text && it.serverId != null } }
                        .single { it.content == text }
                }
                assertEquals(failed.clientId, sent.clientId)
                assertEquals(failed.localId, sent.localId)
                println("M5_QA failedMs=$millis retryUnique=true room=$room")
            }
        }
    }

    @Test
    fun socketCollectorsDetachTheirOwnListeners() = runBlocking {
        val transport = TrackingSocket()
        val user = app.authRepository.getSavedUser()!!
        val socket = SocketManager(
            app.tokenStore,
            app.settingsDataStore,
            notificationScopeProvider = {
                NotificationScope(user.id.toString(), SettingsDataStore.SOURCE_MB_SERVER)
            },
            socketFactory = { _, _ -> transport }
        )
        val events = listOf("presence:update", "typing", "message:new", "message:status")
        try {
            socket.connect()
            withTimeout(10_000) { socket.isConnectedFlow.first { it } }
            events.forEach { event ->
                repeat(3) {
                    val subscriber = launch { socket.events(event).collect() }
                    withTimeout(3_000) { while (transport.count(event) == 0) delay(20) }
                    assertEquals(1, transport.count(event))
                    subscriber.cancelAndJoin()
                    assertEquals(0, transport.count(event))
                }
            }
        } finally {
            socket.disconnect()
        }
    }

    @Test
    fun reportsPresenceChangesFromAnotherEmulator() = runBlocking {
        val peer = args.getString("peer")?.toLongOrNull() ?: 0L
        assumeTrue("Set peer to the second emulator's account ID", peer > 0L)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            val runtime = app.chatRuntime
            val socket = app.socketManager
            val session = withTimeout(15_000) { runtime.session.first { it != null } }!!
            withTimeout(12_000) { socket.isConnectedFlow.first { it } }
            runtime.repository.create(session, peer)
            val users = app.database.chatDao().users(session.ownerId, session.source)
            withTimeout(5_000) { users.first { list -> list.any { it.id == peer && it.online } } }
            coroutineScope {
                val offlineEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    socket.events("presence:update").first { event ->
                        event.payload.optLong("userId") == peer && !event.payload.optBoolean("online")
                    }
                }
                println("M5_QA presenceReady peer=$peer")
                val event = withTimeout(35_000) { offlineEvent.await() }
                val serverMillis = Instant.parse(event.payload.getString("lastSeenAt")).toEpochMilli()
                withTimeout(2_000) { users.first { list -> list.any { it.id == peer && !it.online } } }
                val offlineMillis = System.currentTimeMillis() - serverMillis
                assertTrue("Offline propagation: ${offlineMillis}ms", offlineMillis in 0 until 2000)
                val onlineEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    socket.events("presence:update").first { event ->
                        event.payload.optLong("userId") == peer && event.payload.optBoolean("online")
                    }
                    SystemClock.elapsedRealtime()
                }
                println("M5_QA presenceOffline millis=$offlineMillis")
                val received = withTimeout(55_000) { onlineEvent.await() }
                withTimeout(2_000) { users.first { list -> list.any { it.id == peer && it.online } } }
                val onlineMillis = SystemClock.elapsedRealtime() - received
                assertTrue("Online database update: ${onlineMillis}ms", onlineMillis < 2000)
                println("M5_QA presenceOnline localMillis=$onlineMillis")
            }
        }
    }

    @Test
    fun notificationReusesOpenConversationActivity() {
        val manager = app.getSystemService(NotificationManager::class.java)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        scenario.use {
            val session = runBlocking { withTimeout(15_000) { app.chatRuntime.session.first { it != null } } }!!
            val room = runBlocking {
                withTimeout(10_000) {
                    app.database.chatDao().conversations(session.ownerId, session.source)
                        .first { rows -> rows.any { it.unreadCount > 0 && !it.muted } }
                        .first { it.unreadCount > 0 && !it.muted }.id
                }
            }
            val content = runBlocking {
                withTimeout(10_000) {
                    var pending = manager.activeNotifications.firstOrNull {
                        it.notification.extras.getLong(ChatNotifications.ROOM) == room
                    }
                    while (pending == null) {
                        delay(150)
                        pending = manager.activeNotifications.firstOrNull {
                            it.notification.extras.getLong(ChatNotifications.ROOM) == room
                        }
                    }
                    pending.notification.contentIntent
                }
            }
            scenario.onActivity { activity ->
                activity.startActivity(ChatActivity.scopedIntent(activity, room, session))
            }
            val first = runBlocking {
                withTimeout(10_000) {
                    var chat = resumedChat()
                    while (chat == null) {
                        delay(100)
                        chat = resumedChat()
                    }
                    checkNotNull(chat)
                }
            }
            try {
                assertTrue(!first.isTaskRoot)
                content.send()
                runBlocking { delay(700) }
                assertTrue(first === resumedChat())
                assertEquals(room, first.intent.getLongExtra("conversationId", -1))
            } finally {
                instrumentation.runOnMainSync { first.finish() }
            }
        }
    }

    private fun resumedChat(): ChatActivity? {
        var activity: ChatActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ChatActivity>().singleOrNull()
        }
        return activity
    }

    @Test
    fun scheduleRealAlarm() = runBlocking {
        val due = ZonedDateTime.now().plusMinutes(args.getString("m5AlarmMinutes")?.toLong() ?: 2L)
        val result = app.reminderScheduler.configure(true, due.hour * 60 + due.minute)
        assertEquals(ReminderScheduleStatus.SCHEDULED, result.status)
        assertEquals(ReminderTiming.EXACT, result.daily?.timing)
        println("M5_QA alarmDue=${result.daily?.event?.dueEpochMillis} fullScreen=${result.capabilities?.fullScreen}")
    }

    @Test
    fun writtenJournalSuppressesReminder() = runBlocking {
        val dao = app.database.journalDao()
        val zone = ZoneId.systemDefault()
        val due = ZonedDateTime.now(zone).plusMinutes(1).withSecond(0).withNano(0)
        val clock = object : Clock() {
            var time: Instant = due.toInstant().minusSeconds(1)
            override fun getZone(): ZoneId = zone
            override fun withZone(newZone: ZoneId): Clock = fixed(time, newZone)
            override fun instant(): Instant = time
        }
        val alarms = object : ReminderAlarmBackend {
            override fun capabilities() = ReminderCapabilities(true, true, true)
            override fun replace(event: ReminderEvent, preferred: ReminderTiming) = preferred
            override fun cancel(kind: ReminderKind) = Unit
        }
        val file = File(app.cacheDir, "m5-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = ReminderStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        val scheduler = ReminderScheduler(store, alarms, clock)
        val entry = dao.insertEntry(
            JournalEntryEntity(title = "M5 QA today", content = "Journal exists", mood = 3, createdAt = due.toInstant().toEpochMilli())
        )
        try {
            val result = scheduler.configure(true, due.hour * 60 + due.minute)
            assertEquals(ReminderScheduleStatus.SCHEDULED, result.status)
            val scheduled = checkNotNull(result.daily)
            clock.time = Instant.ofEpochMilli(scheduled.event.dueEpochMillis).plusMillis(100L)
            val delivery = ReminderDelivery(
                store,
                scheduler,
                clock = clock,
                hasWritten = { window ->
                    dao.countNonDeletedInRange(window.start.toEpochMilli(), window.endExclusive.toEpochMilli()) > 0
                },
                present = { _, _ -> error("The alarm should be suppressed") }
            )
            assertEquals(ReminderDeliveryResult.ALREADY_WRITTEN, delivery.onAlarm(scheduled.event, scheduled.timing))
        } finally {
            dao.hardDelete(entry)
            scope.cancel()
            file.delete()
        }
        Unit
    }

    @Test
    fun playerReopensWhilePlaying() = runBlocking {
        val wav = withContext(Dispatchers.IO) { makeTestWav() }
        val dao = app.database.journalDao()
        var entry = 0L
        try {
            entry = dao.save(
                JournalEntryEntity(
                    title = "M5 QA background audio",
                    content = "Player IPC fixture",
                    mood = 3,
                    createdAt = ZonedDateTime.now().minusDays(1).toInstant().toEpochMilli()
                ),
                listOf(JournalAttachmentEntity(entryId = 0L, type = "audio", localPath = wav.path, durationMs = 120_000)),
                emptyList()
            )
            val id = checkNotNull(dao.getEntryById(entry)).attachments.single().id
            val trackId = "journal:$id"
            var servicePid = 0
            var position = 0L
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(app, trackId)).use { scenario ->
                val state = playingState(scenario)
                assertEquals(trackId, state.queue[state.index].id)
                assertNotEquals(Process.myPid(), state.servicePid)
                servicePid = state.servicePid
                position = state.positionMs
            }
            ActivityScenario.launch<PlayerActivity>(Intent(app, PlayerActivity::class.java)).use { scenario ->
                val state = playingState(scenario)
                assertEquals(servicePid, state.servicePid)
                assertEquals(trackId, state.queue[state.index].id)
                assertTrue("Playback position was reset", state.positionMs >= position)
                scenario.onActivity {
                    assertEquals(it.getString(R.string.m5_player_pause), it.findViewById<View>(R.id.playerPlay).contentDescription)
                }
            }
            println("M5_QA reopened playerPid=$servicePid track=$trackId")
        } finally {
            app.stopService(Intent(app, PlayerService::class.java))
            if (entry > 0L) dao.hardDelete(entry)
            wav.delete()
        }
        Unit
    }

    private suspend fun playingState(scenario: ActivityScenario<PlayerActivity>): PlayerState {
        lateinit var player: PlayerActivity
        scenario.onActivity { player = it }
        return withTimeout(18_000) {
            player.client.state.first { it.playing && it.positionMs > 0 }
        }
    }

    @Test
    fun tabletShowsConversationBesideList() {
        assumeTrue("Run this test at tablet width", app.resources.configuration.smallestScreenWidthDp >= 600)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.tabChat).performClick() }
            val session = runBlocking { withTimeout(15_000) { app.chatRuntime.session.first { it != null } } }!!
            runBlocking {
                withTimeout(15_000) { app.database.chatDao().conversations(session.ownerId, session.source).first { it.isNotEmpty() } }
                delay(500)
            }
            scenario.onActivity { activity ->
                val chat = activity.supportFragmentManager.findFragmentByTag("CHAT")!!
                val view = chat.requireView()
                val list = view.findViewById<RecyclerView>(R.id.conversations)
                assertTrue(list.getChildAt(0).performClick())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val chat = activity.supportFragmentManager.findFragmentByTag("CHAT")!!
                val list = chat.requireView().findViewById<View>(R.id.listPanel)
                val detail = chat.requireView().findViewById<View>(R.id.chatDetail)
                assertTrue(list.isShown && detail.isShown)
                assertTrue(list.width > 0 && detail.width > 0 && list.right <= detail.left)
                assertTrue(detail.findViewById<View>(R.id.input).isShown)
            }
        }
    }

    @Test
    fun profilePagesAndAvatarUpdateInProfileAndFeed() {
        assumeTrue("Use a disposable mock-server account for avatar updates", args.getString("m5Avatar") == "true")
        val owner = runBlocking { checkNotNull(app.authRepository.getSavedUser()) }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            lateinit var profile: ProfileFragment
            lateinit var feed: FeedViewModel
            scenario.onActivity { activity ->
                profile = activity.supportFragmentManager.findFragmentByTag("PROFILE") as ProfileFragment
                val fragment = checkNotNull(activity.supportFragmentManager.findFragmentByTag("FEED"))
                feed = ViewModelProvider(fragment)[FeedViewModel::class.java]
                activity.findViewById<View>(R.id.tabProfile).performClick()
            }
            runBlocking {
                withTimeout(15_000) {
                    profile.model.state.first { it.user?.id == owner.id }
                    feed.feedItems.first { list -> list.filterIsInstance<FeedItem.PostItem>().any { it.post.author.id == owner.id } }
                }
            }
            scenario.onActivity {
                val pager = profile.requireView().findViewById<ViewPager2>(R.id.profilePager)
                assertEquals(3, pager.adapter?.itemCount)
                assertTrue(profile.childFragmentManager.fragments.any { it is ProfilePageFragment })
                assertTrue(it.supportFragmentManager.fragments.none { fragment -> fragment is ProfilePageFragment })
                pager.setCurrentItem(1, false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val pager = profile.requireView().findViewById<ViewPager2>(R.id.profilePager)
                assertEquals(1, pager.currentItem)
                pager.setCurrentItem(0, false)
            }
            val file = runBlocking {
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.rgb(49, 137, 104))
                    OwnedImageFiles.jpeg(app, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.media", file)
                scenario.onActivity { profile.model.uploadAvatar(it, uri) }
                runBlocking {
                    val updated = withTimeout(15_000) {
                        profile.model.state.first {
                            it.user?.id == owner.id && it.user.avatarUrl != owner.avatarUrl
                        }.user!!
                    }
                    withTimeout(12_000) {
                        feed.feedItems.first { list ->
                            list.filterIsInstance<FeedItem.PostItem>().any {
                                it.post.author.id == owner.id &&
                                    it.post.author.avatarUrl == updated.avatarUrl &&
                                    it.post.author.avatarVersion == updated.avatarVersion
                            }
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    private fun makeTestWav(): File {
        val rate = 8_000
        val sampleCount = rate * 120
        val wav = File.createTempFile("m5-qa-tone-", ".wav", app.filesDir)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + sampleCount * 2)
        header.put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(sampleCount * 2)
        val second = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rate) { second.putShort((sin(it * 440.0 * Math.PI * 2 / rate) * 100).toInt().toShort()) }
        wav.outputStream().buffered().use { output ->
            output.write(header.array())
            repeat(120) { output.write(second.array()) }
        }
        return wav
    }

    private class TrackingSocket : RealtimeSocket {
        private val listeners = ConcurrentHashMap<String, MutableSet<(Array<out Any>) -> Unit>>()

        override fun on(event: String, listener: (Array<out Any>) -> Unit) {
            listeners.computeIfAbsent(event) { ConcurrentHashMap.newKeySet() }.add(listener)
        }

        override fun off(event: String, listener: (Array<out Any>) -> Unit) {
            listeners[event]?.remove(listener)
        }

        fun count(event: String): Int = listeners[event]?.size ?: 0

        override fun connect() {
            listeners["connect"].orEmpty().forEach { it(emptyArray()) }
        }

        override fun disconnect() {
            listeners.clear()
        }
    }
}
