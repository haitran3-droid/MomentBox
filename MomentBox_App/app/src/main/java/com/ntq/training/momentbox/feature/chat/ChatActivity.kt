package com.ntq.training.momentbox.feature.chat

import android.view.View
import androidx.core.app.TaskStackBuilder
import androidx.lifecycle.Lifecycle
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.feature.tabs.ChatFragment
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.databinding.ActivityChatBinding

/** Entry nội bộ singleTop. Chỉ nhận ID primitive; notification còn phải khớp phiên tạo nó.
 * Entry epoch ngăn activate cũ hoàn tất sau onNewIntent mới rồi render nhầm phòng. */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private var entryEpoch = 0L
    private var listVisibility = View.GONE
    private var openedSession: ChatSession? = null
    private var restoredSession: ChatSession? = null
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        binding =
            ActivityChatBinding.inflate(layoutInflater)
            setContentView(binding.root)
        // Fragment cũ có thể được Android restore trước khi activate xác minh xong phiên.
        // Giữ root vẽ background để WindowManager hoàn tất transition/finish bình thường.
        // Chỉ ẩn hai vùng nội dung có thể chứa Fragment được phục hồi.
        listVisibility = binding.chatListContainer.visibility
        binding.chatContainer.visibility = View.INVISIBLE
        if (listVisibility == View.VISIBLE) binding.chatListContainer.visibility =
            View.INVISIBLE
        val owner = saved?.getString("chatOwner")
        val source = saved?.getString("chatSource")
        if (owner != null && source != null) restoredSession =
            ChatSession(owner, source, saved.getLong("chatGeneration", -1))
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        // Chặn cả trường hợp logout khi Main đang STOPPED dưới Chat. Không để nội dung/nháp
        // của phiên cũ tồn tại tới lần đăng nhập tiếp theo, kể cả Activity đang ở nền.
        lifecycleScope.launch {
            (application as MomentBoxApp).chatRuntime.session.collect { current ->
                if (openedSession != null && current != openedSession) {
                    entryEpoch++
                    finish()
                }
            }
        }
        openEntry(intent, true)
    }

    private fun openEntry(entry: Intent, initial: Boolean) {
        val room = entry.getLongExtra("conversationId", -1)
        if (room <= 0) {
            if (initial) finish()
            return
        }
        val version = ++entryEpoch
        lifecycleScope.launch {
            val runtime = (application as MomentBoxApp).chatRuntime
            runtime.activate()
            // activate có thể suspend qua onSaveInstanceState. Chỉ giao dịch Fragment sau RESUMED,
            // rồi kiểm lại epoch và phiên để không dùng snapshot trước khi chờ lifecycle.
            val verified = runtime.current()
            lifecycle.withResumed { renderEntry(entry, initial, version, runtime, verified) }
        }
    }

    private fun renderEntry(
        entry: Intent,
        initial: Boolean,
        version: Long,
        runtime: ChatRuntime,
        verified: ChatSession?
    ) {
        if (version != entryEpoch || isFinishing) return
        val session = verified?.takeIf { runtime.session.value == it }
        if (session == null || (openedSession != null && openedSession != session) || (restoredSession != null && restoredSession != session)) {
            finish()
            return
        }
        // Result tạo phòng có thể tới sau logout/relogin. ID phòng một mình có thể trùng ở
        // account khác; xác minh epoch trước khi Fragment/Room hiển thị bất kỳ nội dung nào.
        if (entry.getBooleanExtra("m5ScopedEntry", false)) {
            val valid =
                session.ownerId == entry.getStringExtra("owner") && session.source == entry.getStringExtra(
                    "source"
                ) && session.generation == entry.getLongExtra("generation", -1)
            if (!valid) {
                if (initial || supportFragmentManager.findFragmentByTag("room") == null) finish()
                return
            }
        }
        if (entry.getBooleanExtra("m5Notification", false)) {
            val valid =
                session.ownerId == entry.getStringExtra("owner") && session.source == entry.getStringExtra(
                    "source"
                ) && session.generation == entry.getLongExtra("generation", -1)
            if (!valid) {
                if (initial || supportFragmentManager.findFragmentByTag("room") == null) finish()
                return
            }
            // Chỉ dựng back stack khi native content intent mở task trống. Warm entry giữ caller S08.
            // SystemUI thêm NEW_TASK; không mang flag đó sang phần tử Chat của stack mới.
            if (isTaskRoot && !entry.getBooleanExtra("m5TaskReady", false)) {
                TaskStackBuilder.create(this)
                    .addNextIntent(
                        Intent(
                            this,
                            MainActivity::class.java
                        )
                    )
                    .addNextIntent(
                        Intent(entry).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("m5TaskReady", true)
                    ).startActivities()
                finish()
                return
            }
        }
        openedSession = session
        setIntent(entry)
        binding.chatContainer.visibility =
            View.VISIBLE
            binding.chatListContainer.visibility = listVisibility
        val room = entry.getLongExtra("conversationId", -1)
        val fragment = supportFragmentManager.findFragmentByTag("room") as? ChatRoomFragment
        if (fragment == null) supportFragmentManager.beginTransaction().replace(
            binding.chatContainer.id,
            ChatRoomFragment.newInstance(room, entry.getStringExtra("draft")),
            "room"
        ).commit()
        else if (fragment.currentConversation() != room) fragment.select(room)
        if (supportFragmentManager.findFragmentByTag("list") == null) supportFragmentManager.beginTransaction()
            .add(
                binding.chatListContainer.id,
                ChatFragment()
                    .apply { arguments = Bundle().apply { putBoolean("listOnly", true) } },
                "list"
            ).commit()
        // FragmentManager đã phục hồi fragment khi rotate; không tạo lại nháp cùng phòng.
        supportFragmentManager.executePendingTransactions()
        supportFragmentManager.findFragmentByTag("list")?.let {
            supportFragmentManager.beginTransaction().setMaxLifecycle(
                it,
                if (resources.configuration.smallestScreenWidthDp >= 600) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
            ).commit()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        (openedSession ?: restoredSession)?.let {
            out.putString(
                "chatOwner",
                it.ownerId
            )
            out.putString("chatSource", it.source)
            out.putLong("chatGeneration", it.generation)
        }
        super.onSaveInstanceState(out)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openEntry(intent, false)
    }

    fun select(room: Long) {
        if (room > 0) {
            intent.putExtra(
                "conversationId",
                room
            )
            (supportFragmentManager.findFragmentByTag("room") as? ChatRoomFragment)?.select(room)
        }
    }

    override fun finish() {
        val room = supportFragmentManager.findFragmentByTag("room") as? ChatRoomFragment
        val session = (application as MomentBoxApp).chatRuntime.session.value
        // Kết quả cũng thuộc phiên mở màn hình: không gắn owner mới vào nháp của owner cũ.
        if (room != null && session != null && session == openedSession) setResult(
            RESULT_OK,
            Intent().putExtra("conversationId", room.currentConversation())
                .putExtra("draft", room.currentDraft().take(5000))
                .putExtra("owner", session.ownerId).putExtra("source", session.source)
                .putExtra("generation", session.generation)
        )
        else setResult(RESULT_CANCELED)
        super.finish()
    }

    companion object {
        fun scopedIntent(c: Context, room: Long, session: ChatSession) =
            intent(c, room).putExtra("m5ScopedEntry", true).putExtra("owner", session.ownerId)
                .putExtra("source", session.source).putExtra("generation", session.generation)

        fun intent(c: Context, room: Long, draft: String? = null) =
            Intent(c, ChatActivity::class.java).putExtra("conversationId", room)
                .putExtra("draft", draft?.take(5000)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
