package com.ntq.training.momentbox

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ntq.training.momentbox.core.network.SessionManager
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.feed.FeedFragment
import com.ntq.training.momentbox.feature.tabs.ChatFragment
import com.ntq.training.momentbox.feature.tabs.JournalFragment
import com.ntq.training.momentbox.feature.tabs.ProfileFragment
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as MomentBoxApp

    private lateinit var feedFragment: FeedFragment
    private lateinit var journalFragment: JournalFragment
    private lateinit var chatFragment: ChatFragment
    private lateinit var profileFragment: ProfileFragment

    private lateinit var activeFragment: Fragment
    private var routingToAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            binding.bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        setupFragments(savedInstanceState)
        setupBottomNav()
        observeSessionExpiry()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Lưu lại tag của tab đang hiển thị để khôi phục chính xác sau khi xoay màn hình (F02)
        val activeTag = when (activeFragment) {
            feedFragment -> TAG_FEED
            journalFragment -> TAG_JOURNAL
            chatFragment -> TAG_CHAT
            profileFragment -> TAG_PROFILE
            else -> TAG_FEED
        }
        outState.putString(KEY_ACTIVE_TAB_TAG, activeTag)
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            feedFragment = FeedFragment()
            journalFragment = JournalFragment()
            chatFragment = ChatFragment()
            profileFragment = ProfileFragment()

            fm.beginTransaction()
                .add(R.id.fragment_container, feedFragment, TAG_FEED)
                .add(R.id.fragment_container, journalFragment, TAG_JOURNAL).hide(journalFragment)
                .add(R.id.fragment_container, chatFragment, TAG_CHAT).hide(chatFragment)
                .add(R.id.fragment_container, profileFragment, TAG_PROFILE).hide(profileFragment)
                .commit()
            activeFragment = feedFragment
        } else {
            // Khôi phục đúng các instance đã được FragmentManager tái tạo
            feedFragment = fm.findFragmentByTag(TAG_FEED) as? FeedFragment ?: FeedFragment()
            journalFragment = fm.findFragmentByTag(TAG_JOURNAL) as? JournalFragment ?: JournalFragment()
            chatFragment = fm.findFragmentByTag(TAG_CHAT) as? ChatFragment ?: ChatFragment()
            profileFragment = fm.findFragmentByTag(TAG_PROFILE) as? ProfileFragment ?: ProfileFragment()

            val activeTag = savedInstanceState.getString(KEY_ACTIVE_TAB_TAG, TAG_FEED)
            activeFragment = when (activeTag) {
                TAG_JOURNAL -> journalFragment
                TAG_CHAT -> chatFragment
                TAG_PROFILE -> profileFragment
                else -> feedFragment
            }
        }
    }

    private fun setupBottomNav() {
        binding.tabFeed.setOnClickListener {
            if (activeFragment == feedFragment) {
                feedFragment.scrollToTopAndRefresh()
            } else {
                switchTab(feedFragment)
            }
        }

        binding.tabJournal.setOnClickListener {
            switchTab(journalFragment)
        }

        binding.tabChat.setOnClickListener {
            switchTab(chatFragment)
        }

        binding.tabProfile.setOnClickListener {
            switchTab(profileFragment)
        }

        // Nút Tạo mới ở chính giữa (D02 - Milestone M4 contract)
        binding.tabCreate.setOnClickListener {
            Toast.makeText(this, getString(R.string.create_post_coming_soon), Toast.LENGTH_SHORT).show()
        }

        updateTabUi()
    }

    /**
     * Chuyển tab an toàn:
     * 1. Dừng ngay toàn bộ media (video, voice) trên Feed khi rời khỏi Feed (F06).
     * 2. Sử dụng hide/show để giữ nguyên scroll position của các tab.
     */
    private fun switchTab(targetFragment: Fragment) {
        if (activeFragment == targetFragment) return

        // Dừng toàn bộ audio/video trên Feed nếu đang rời khỏi Feed
        if (activeFragment == feedFragment) {
            feedFragment.pauseAllMedia()
        }

        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(targetFragment)
            .commit()

        activeFragment = targetFragment
        updateTabUi()
    }

    companion object {
        private const val TAG_FEED = "FEED"
        private const val TAG_JOURNAL = "JOURNAL"
        private const val TAG_CHAT = "CHAT"
        private const val TAG_PROFILE = "PROFILE"
        private const val KEY_ACTIVE_TAB_TAG = "KEY_ACTIVE_TAB_TAG"
    }

    private fun updateTabUi() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Tab Feed
        val isFeed = activeFragment == feedFragment
        binding.pillFeed.setBackgroundResource(if (isFeed) R.drawable.bg_voice_container else 0)
        binding.ivTabFeed.setColorFilter(if (isFeed) primaryColor else secondaryColor)
        binding.tvTabFeed.setTextColor(if (isFeed) primaryColor else secondaryColor)
        binding.tvTabFeed.setTypeface(null, if (isFeed) Typeface.BOLD else Typeface.NORMAL)

        // Tab Journal
        val isJournal = activeFragment == journalFragment
        binding.pillJournal.setBackgroundResource(if (isJournal) R.drawable.bg_voice_container else 0)
        binding.ivTabJournal.setColorFilter(if (isJournal) primaryColor else secondaryColor)
        binding.tvTabJournal.setTextColor(if (isJournal) primaryColor else secondaryColor)
        binding.tvTabJournal.setTypeface(null, if (isJournal) Typeface.BOLD else Typeface.NORMAL)

        // Tab Chat
        val isChat = activeFragment == chatFragment
        binding.pillChat.setBackgroundResource(if (isChat) R.drawable.bg_voice_container else 0)
        binding.ivTabChat.setColorFilter(if (isChat) primaryColor else secondaryColor)
        binding.tvTabChat.setTextColor(if (isChat) primaryColor else secondaryColor)
        binding.tvTabChat.setTypeface(null, if (isChat) Typeface.BOLD else Typeface.NORMAL)

        // Tab Profile
        val isProfile = activeFragment == profileFragment
        binding.pillProfile.setBackgroundResource(if (isProfile) R.drawable.bg_voice_container else 0)
        binding.ivTabProfile.setColorFilter(if (isProfile) primaryColor else secondaryColor)
        binding.tvTabProfile.setTextColor(if (isProfile) primaryColor else secondaryColor)
        binding.tvTabProfile.setTypeface(null, if (isProfile) Typeface.BOLD else Typeface.NORMAL)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) {
                handleSessionExpired()
            }
        }
    }

    private fun observeSessionExpiry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionManager.expirationRevision.collectLatest {
                    if (!app.authRepository.isLoggedIn()) {
                        handleSessionExpired()
                    }
                }
            }
        }
    }

    private fun handleSessionExpired() {
        if (routingToAuth) return
        routingToAuth = true
        Toast.makeText(this@MainActivity, R.string.main_session_expired, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
