package com.ntq.training.momentbox.feature.postdetail
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.ActivityPostDetailABinding
import com.ntq.training.momentbox.databinding.ActivityPostDetailBBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PostDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POST_ID = "EXTRA_POST_ID"
        const val EXTRA_LAYOUT_VERSION = "EXTRA_LAYOUT_VERSION" // "A" hoặc "B" (mặc định "B")
    }

    private val app get() = application as MomentBoxApp

    private var postId: Long = 0L
    private var isVersionA: Boolean = false
    private var currentUser: User? = null

    private var bindingB: ActivityPostDetailBBinding? = null
    private var bindingA: ActivityPostDetailABinding? = null

    private val viewModel: PostDetailViewModel by viewModels {
        PostDetailViewModel.Factory(app.feedRepository, postId)
    }

    private lateinit var commentsAdapter: CommentsAdapter
    private lateinit var postHeaderAdapter: PostHeaderAdapter
    private lateinit var commentsFooterAdapter: CommentsFooterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Phân tích Deep Link hoặc Extra Intent
        parsePostIdFromIntent()
        if (postId <= 0L) {
            Toast.makeText(this, "Không tìm thấy bài viết hợp lệ", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 2. Chốt chặn xác thực (Auth Gate) & Lấy thông tin người dùng
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) {
                Toast.makeText(this@PostDetailActivity, "Vui lòng đăng nhập để xem chi tiết bài viết", Toast.LENGTH_SHORT).show()
                val authIntent = Intent(this@PostDetailActivity, AuthActivity::class.java).apply {
                    putExtra("EXTRA_REDIRECT_POST_ID", postId)
                }
                startActivity(authIntent)
                finish()
                return@launch
            }

            currentUser = app.authRepository.getSavedUser()
            currentUser?.avatarUrl?.let { url ->
                bindingB?.let { b ->
                    Glide.with(this@PostDetailActivity)
                        .load(url)
                        .placeholder(R.color.surface_variant)
                        .circleCrop()
                        .into(b.ivCurrentUserAvatar)
                }
            }
        }

        // 3. Khởi tạo Layout theo phiên bản A hoặc B
        isVersionA = intent.getStringExtra(EXTRA_LAYOUT_VERSION) == "A"
        if (isVersionA) {
            bindingA = ActivityPostDetailABinding.inflate(layoutInflater)
            setContentView(bindingA!!.root)
            setupWindowInsets(bindingA!!.postDetailRootA, bindingA!!.etCommentInputA)
            setupVersionA()
        } else {
            bindingB = ActivityPostDetailBBinding.inflate(layoutInflater)
            setContentView(bindingB!!.root)
            setupWindowInsets(bindingB!!.postDetailRoot, bindingB!!.etCommentInput)
            setupVersionB()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        observeViewModel()
    }

    private fun parsePostIdFromIntent() {
        val data: Uri? = intent.data
        if (data != null) {
            // Định dạng: momentbox://post/{id} hoặc https://momentbox.dev/post/{id}
            val idSegment = data.lastPathSegment
            postId = idSegment?.toLongOrNull() ?: 0L
        } else {
            postId = intent.getLongExtra(EXTRA_POST_ID, 0L)
        }
    }

    private fun setupWindowInsets(rootView: View, editText: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
    }

    // ==========================================
    // PHIÊN BẢN B (CHUẨN CONCAT ADAPTER)
    // ==========================================

    private fun setupVersionB() {
        val b = bindingB ?: return

        b.btnBack.setOnClickListener { handleBackNavigation() }
        b.btnMoreDetail.setOnClickListener { showPostMoreBottomSheet() }

        // Khởi tạo các adapter cho ConcatAdapter
        postHeaderAdapter = PostHeaderAdapter(
            onLikeClick = { viewModel.toggleLikePost() },
            onBookmarkClick = { viewModel.toggleBookmarkPost() },
            onShareClick = { shareCurrentPost() },
            onFollowClick = {
                val currentPost = viewModel.post.value ?: return@PostHeaderAdapter
                lifecycleScope.launch {
                    val res = app.feedRepository.toggleFollowUser(currentPost.author.id, false)
                    if (res.isFailure) {
                        Toast.makeText(this@PostDetailActivity, "Không thể cập nhật theo dõi", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSortClick = { showSortPopupMenu(b.root) }
        )

        commentsAdapter = CommentsAdapter(
            onLikeClick = { comment -> viewModel.toggleLikeComment(comment) },
            onReplyClick = { comment -> viewModel.setReplyingTo(comment) },
            onExpandRepliesClick = { comment -> viewModel.expandReplies(comment) },
            onRetryClick = { comment -> viewModel.retryComment(comment) }
        )

        commentsFooterAdapter = CommentsFooterAdapter {
            viewModel.loadComments()
        }

        // Bật setIsolateViewTypes(true) kết hợp với các hằng số VIEW_TYPE độc lập (1001, 1002, 1003, 1004)
        // để loại bỏ hoàn toàn nguy cơ ClassCastException khi tái chế ViewHolder giữa các adapter con (D01, F01).
        val concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            postHeaderAdapter,
            commentsAdapter,
            commentsFooterAdapter
        )

        val layoutManager = LinearLayoutManager(this)
        b.rvPostDetail.layoutManager = layoutManager
        b.rvPostDetail.adapter = concatAdapter

        // Lắng nghe cuộn tải thêm bình luận
        b.rvPostDetail.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val total = layoutManager.itemCount
                val last = layoutManager.findLastVisibleItemPosition()
                if (total > 0 && last >= total - 3) {
                    viewModel.loadComments(refresh = false)
                }
            }
        })

        // Xử lý bộ đếm ký tự bình luận
        b.etCommentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val len = s?.length ?: 0
                if (len >= 450) {
                    b.tvCharCounter.visibility = View.VISIBLE
                    b.tvCharCounter.text = "$len/500"
                    if (len == 500) {
                        b.tvCharCounter.setTextColor(ContextCompat.getColor(this@PostDetailActivity, R.color.error))
                    } else {
                        b.tvCharCounter.setTextColor(ContextCompat.getColor(this@PostDetailActivity, R.color.text_tertiary))
                    }
                } else {
                    b.tvCharCounter.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Nút gửi bình luận
        b.btnSendComment.setOnClickListener {
            val content = b.etCommentInput.text.toString()
            if (content.isNotBlank()) {
                val currentAuthor = Author(
                    id = currentUser?.id ?: 1L,
                    username = currentUser?.username ?: "me",
                    displayName = currentUser?.displayName ?: "Bạn",
                    avatarUrl = currentUser?.avatarUrl,
                    verified = currentUser?.verified ?: false
                )
                viewModel.sendComment(content, currentAuthor)
                b.etCommentInput.setText("")
                hideKeyboard(b.etCommentInput)
            }
        }

        b.btnCancelReply.setOnClickListener {
            viewModel.setReplyingTo(null)
        }

        // Avatar người dùng hiện tại
        currentUser?.avatarUrl?.let { url ->
            Glide.with(this)
                .load(url)
                .placeholder(R.drawable.bg_avatar_placeholder)
                .error(R.drawable.bg_avatar_placeholder)
                .circleCrop()
                .into(b.ivCurrentUserAvatar)
        }
    }

    // ==========================================
    // PHIÊN BẢN A (NESTED SCROLL VIEW - DÙNG CHO A/B TEST)
    // ==========================================

    private fun setupVersionA() {
        val a = bindingA ?: return

        a.btnBackA.setOnClickListener { finish() }

        commentsAdapter = CommentsAdapter(
            onLikeClick = { comment -> viewModel.toggleLikeComment(comment) },
            onReplyClick = { comment -> viewModel.setReplyingTo(comment) },
            onExpandRepliesClick = { comment -> viewModel.expandReplies(comment) }
        )

        a.rvCommentsA.layoutManager = LinearLayoutManager(this)
        a.rvCommentsA.adapter = commentsAdapter

        a.btnSendCommentA.setOnClickListener {
            val content = a.etCommentInputA.text.toString()
            if (content.isNotBlank()) {
                val currentAuthor = Author(
                    id = currentUser?.id ?: 1L,
                    username = currentUser?.username ?: "me",
                    displayName = currentUser?.displayName ?: "Bạn",
                    avatarUrl = currentUser?.avatarUrl,
                    verified = currentUser?.verified ?: false
                )
                viewModel.sendComment(content, currentAuthor)
                a.etCommentInputA.setText("")
            }
        }
    }

    // ==========================================
    // QUAN SÁT DỮ LIỆU TỪ VIEWMODEL
    // ==========================================

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Quan sát dữ liệu bài viết
                launch {
                    viewModel.post.collectLatest { post ->
                        if (post != null) {
                            if (!isVersionA) {
                                postHeaderAdapter.setPost(post)
                                // Giữ tổng số bình luận chuẩn từ dữ liệu server thay vì đếm theo số root comments đã nạp
                                postHeaderAdapter.setCommentCount(post.commentCount)
                            }
                        }
                    }
                }

                // Quan sát danh sách bình luận
                launch {
                    viewModel.comments.collectLatest { commentsList ->
                        commentsAdapter.submitCommentsList(commentsList)
                        // Chỉ dùng kích thước danh sách tạm thời nếu bài viết chưa kịp nạp xong từ máy chủ
                        if (!isVersionA && viewModel.post.value == null) {
                            postHeaderAdapter.setCommentCount(commentsList.size)
                        }
                    }
                }

                // Quan sát trạng thái đang tải thêm
                launch {
                    viewModel.isLoadingComments.collectLatest { loading ->
                        if (!isVersionA) {
                            commentsFooterAdapter.setLoading(loading)
                        }
                    }
                }

                // Quan sát trạng thái đang trả lời bình luận
                launch {
                    viewModel.replyingTo.collectLatest { replyingComment ->
                        val b = bindingB
                        if (b != null) {
                            if (replyingComment != null) {
                                b.layoutReplyingBanner.visibility = View.VISIBLE
                                b.tvReplyingTo.text = "Đang trả lời ${replyingComment.author.displayName}"
                                b.etCommentInput.requestFocus()
                                showKeyboard(b.etCommentInput)
                            } else {
                                b.layoutReplyingBanner.visibility = View.GONE
                            }
                        }
                    }
                }

                // Quan sát chế độ sắp xếp
                launch {
                    viewModel.sortMode.collectLatest { mode ->
                        if (!isVersionA) {
                            postHeaderAdapter.setSortLabel(
                                if (mode == "newest") "Mới nhất" else "Phù hợp nhất"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Điều hướng quay lại chuẩn theo BA và hợp đồng navigation (F18):
     * Nếu màn hình được mở trực tiếp từ ngoài ứng dụng qua Deep Link ([isTaskRoot] = true),
     * khi người dùng bấm nút Back, mở [MainActivity] để người dùng không bị thoát văng khỏi app.
     */
    private fun handleBackNavigation() {
        if (isTaskRoot) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
        finish()
    }

    /**
     * Hiển thị Bottom Sheet tùy chọn bài viết thực tế thay vì Toast giả (D01).
     */
    private fun showPostMoreBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetBinding = com.ntq.training.momentbox.databinding.DialogPostMenuBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val currentPost = viewModel.post.value

        // 1. Sao chép liên kết bài viết
        sheetBinding.menuCopyLink.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Post Link", "momentbox://post/$postId")
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép liên kết", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 2. Báo cáo vi phạm
        sheetBinding.menuReport.setOnClickListener {
            Toast.makeText(this, "Đã gửi báo cáo bài viết", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 3. Xóa bài viết (nếu người dùng là tác giả bài viết)
        val isMyPost = currentPost != null && currentUser != null && currentPost.author.id == currentUser?.id
        if (isMyPost) {
            sheetBinding.menuDelete.visibility = View.VISIBLE
            sheetBinding.menuDelete.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    val res = app.feedRepository.deletePost(postId)
                    if (res.isSuccess) {
                        Toast.makeText(this@PostDetailActivity, "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                        handleBackNavigation()
                    } else {
                        Toast.makeText(this@PostDetailActivity, "Không thể xóa bài viết", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            sheetBinding.menuDelete.visibility = View.GONE
        }

        dialog.show()
    }

    private fun showSortPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Phù hợp nhất")
        popup.menu.add(0, 2, 1, "Mới nhất")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> viewModel.setSortMode("top")
                2 -> viewModel.setSortMode("newest")
            }
            true
        }
        popup.show()
    }

    private fun shareCurrentPost() {
        val p = viewModel.post.value ?: return
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Xem bài viết của ${p.author.displayName} trên MomentBox: momentbox://post/${p.id}")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Chia sẻ bài viết qua"))
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * Phương thức công khai trả về số lần gọi onBindViewHolder để phục vụ A/B Testing Benchmark.
     */
    fun getCommentsBindCount(): Int = commentsAdapter.bindCount

    fun resetCommentsBindCount() {
        commentsAdapter.resetBindCount()
    }
}
