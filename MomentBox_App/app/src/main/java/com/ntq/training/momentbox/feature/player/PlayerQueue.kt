package com.ntq.training.momentbox.feature.player

import java.net.URI
import java.util.ArrayDeque
import kotlin.random.Random

class PlayerQueue {
    var tracks: List<Track> = emptyList()
    private set
    var index: Int = -1
    private set
    var repeatMode: Int = 0
        set(value) {
            require(value in 0..2)
            field = value
        }
    var shuffle: Boolean = false
    private val history = ArrayDeque<String>()
    fun replace(values: List<Track>, start: Int) {
        PlayerProtocol.validate(values, start)
        tracks = values.map { it.copy(waveform = it.waveform.toList()) }
        index =
            if (values.isEmpty()) -1 else start
        history.clear()
    }

    fun next(
        automatic: Boolean,
        choose: (Int) -> Int = { Random.nextInt(it) }
    ): Int? {
        if (index !in tracks.indices) return null
        if (automatic && repeatMode == 2) return index
        if (automatic && !shuffle && index == tracks.lastIndex && repeatMode == 0) return null
        if (tracks.size == 1) return if (!automatic || repeatMode != 0) index else null
        history.addLast(tracks[index].id)
        if (history.size > 100) history.removeFirst()
        index =
            if (shuffle) (tracks.indices.filter { it != index })[choose(tracks.size - 1).coerceIn(
                0,
                tracks.size - 2
            )] else (index + 1) % tracks.size
        return index
    }

    fun previous(): Int? {
        if (index !in tracks.indices) return null
        val previous =
            if (shuffle && history.isNotEmpty()) tracks.indexOfFirst { it.id == history.removeLast() } else (index - 1 + tracks.size) % tracks.size
        if (previous >= 0) index = previous
        return index
    }

    fun favorite(id: String, value: Boolean) {
        tracks = tracks.map { if (it.id == id) it.copy(favorite = value) else it }
    }
}

/** Binder buffer là tài nguyên chung ~1MiB; queue giới hạn50 và tổng metadata dưới192KiB.
 * Không gửi PCM/Bitmap hoặc token. Validate trước IPC ở client và sau IPC ở service.
 * Một queue thuộc một ownerScope để logout/đổi nguồn không trộn thư viện người dùng. */
object PlayerProtocol {
    const val MAX_QUEUE = 50
    fun validate(values: List<Track>, index: Int) {
        require(values.size <= MAX_QUEUE)
        require(if (values.isEmpty()) index in -1..0 else index in values.indices)
        require(values.map { it.id }.toSet().size == values.size)
        require(values.map { it.ownerScope }.toSet().size <= 1)
        var estimated = 0L
        values.forEach { track ->
            require(
                track.id.length in 1..128 && track.title.length <= 500 && track.uri.length in 1..4096 && (track.coverUri?.length
                    ?: 0) <= 4096 && track.ownerScope.length in 1..512
            )
            val scheme = URI(track.uri).scheme
            require(scheme in setOf("file", "content", "http", "https"))
            require(track.source in setOf("journal", "saved"))
            require(track.waveform.size <= 120 && track.waveform.all { it.isFinite() && it in 0f..1f })
            estimated += 2L * (track.id.length + track.title.length + track.uri.length + (track.coverUri?.length
                ?: 0) + track.ownerScope.length) + track.waveform.size * 8 + 256
        }
        require(estimated <= 192 * 1024) { "Player queue exceeds IPC metadata budget" }
    }
}
