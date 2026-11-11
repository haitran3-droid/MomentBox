package com.ntq.training.momentbox.core.media

/** Voice chỉ mượn focus tạm thời; video dùng focus cho phiên xem liên tục. */
enum class AudioPlaybackKind { VOICE, VIDEO, RECORDING }

/** Cổng âm thanh để kiểm thử chính sách phát mà không cần Android. */
interface PlaybackAudio {
    fun acquire(
        owner: Any, onInterrupted: () -> Unit, mayInterrupt: Boolean = true,
        kind: AudioPlaybackKind = AudioPlaybackKind.VIDEO
    ): Boolean

    fun release(owner: Any)
}

/** Điểm gom quyền phát; chính sách ngắt nguồn cũ được kiểm tra bằng unit test. */
class AudioOwnership {
    private var owner: Any? = null
    private var callback: (() -> Unit)? = null
    fun acquire(owner: Any, onInterrupted: () -> Unit, mayInterrupt: Boolean = true): Boolean {
        if (this.owner != null && this.owner != owner && !mayInterrupt) return false
        if (this.owner != owner) {
            // Xóa chủ cũ trước callback: release từ player cũ không được xóa chủ mới.
            val previous = callback
            this.owner = null
            callback = null
            previous?.invoke()
        }
        this.owner = owner
        callback = onInterrupted
        return true
    }

    fun isOwner(owner: Any): Boolean = this.owner == owner
    fun release(owner: Any) {
        if (isOwner(owner)) {
            this.owner = null; callback = null
        }
    }

    fun interrupt() {
        val interrupted = callback
        owner = null
        callback = null
        interrupted?.invoke()
    }

    fun hasOwner(): Boolean = owner != null
}

/** Ý định riêng từng bài, độc lập với trạng thái đang buffer của ExoPlayer. */
class VideoPlaybackIntents {
    private val paused = mutableSetOf<Long>()
    fun set(postId: Long, play: Boolean) {
        if (play) paused.remove(postId) else paused.add(postId)
    }

    fun canAutoplay(postId: Long): Boolean = postId !in paused
    fun pausedPostIds(): LongArray = paused.toLongArray()
    fun restorePausedPostIds(ids: LongArray) {
        paused.clear(); paused.addAll(ids.toList())
    }
}