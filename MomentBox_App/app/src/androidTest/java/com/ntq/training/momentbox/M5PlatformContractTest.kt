package com.ntq.training.momentbox

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.R as AppCompatR
import androidx.core.splashscreen.R as SplashR
import com.ntq.training.momentbox.core.notification.NotificationChannels
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.feature.chat.NewConversationDialog
import com.ntq.training.momentbox.feature.player.PlayerService
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5PlatformContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchAndDayNightThemeInheritAppCompatAttributes() {
        val splashTheme = ContextThemeWrapper(context, R.style.Theme_MomentBox_Splash).theme
        val postSplash = TypedValue()
        assertTrue(splashTheme.resolveAttribute(SplashR.attr.postSplashScreenTheme, postSplash, true))
        assertEquals(R.style.Theme_MomentBox, postSplash.resourceId)
        val appTheme = ContextThemeWrapper(context, postSplash.resourceId).theme
        assertTrue(appTheme.resolveAttribute(AppCompatR.attr.windowActionBar, TypedValue(), true))
    }

    @Test
    fun appCreatesFiveChannelsAndKeepsPlayerInPrivateProcess() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val manager = context.getSystemService(NotificationManager::class.java)
        val expected = mapOf(
            NotificationChannels.PLAYBACK to NotificationManager.IMPORTANCE_LOW,
            NotificationChannels.RECORDING to NotificationManager.IMPORTANCE_LOW,
            NotificationChannels.UPLOAD to NotificationManager.IMPORTANCE_LOW,
            NotificationChannels.CHAT to NotificationManager.IMPORTANCE_HIGH,
            NotificationChannels.REMINDER to NotificationManager.IMPORTANCE_HIGH
        )
        expected.forEach { (channel, importance) ->
            assertEquals(channel, importance, manager.getNotificationChannel(channel)?.importance)
        }
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, PlayerService::class.java), 0)
        assertFalse(service.exported)
        assertEquals("${context.packageName}:player", service.processName)
    }

    @Test
    fun conversationResultCannotBeReusedByAnotherSession() {
        val session = ChatSession("12", "MB_SERVER|http://10.0.2.2:3000", 7)
        val result = Bundle().apply {
            putLong("id", 4)
            putString("owner", session.ownerId)
            putString("source", session.source)
            putLong("generation", session.generation)
        }
        assertTrue(NewConversationDialog.matches(result, session))
        assertFalse(NewConversationDialog.matches(result, session.copy(generation = 8)))
        assertFalse(NewConversationDialog.matches(result, session.copy(ownerId = "13")))
        assertEquals(13L, NewConversationDialog.forUser(13L).requireArguments().getLong("user"))
    }

    @Test
    fun hiddenProfileCannotCoverChatWhenAccountLoads() {
        val app = context.applicationContext as MomentBoxApp
        assumeTrue("Run with a signed-in emulator fixture", runBlocking { app.authRepository.isLoggedIn() })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var profile: ProfileFragment
            scenario.onActivity {
                profile = it.supportFragmentManager.findFragmentByTag("PROFILE") as ProfileFragment
                it.findViewById<View>(R.id.tabChat).performClick()
            }
            runBlocking { withTimeout(10_000) { profile.model.state.first { it.scope != null } } }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val chat = checkNotNull(manager.findFragmentByTag("CHAT"))
                assertTrue(profile.isHidden)
                assertEquals(View.GONE, profile.requireView().visibility)
                assertEquals(View.VISIBLE, chat.requireView().visibility)
            }
        }
    }
}
