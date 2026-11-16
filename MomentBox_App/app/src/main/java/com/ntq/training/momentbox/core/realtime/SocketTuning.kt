package com.ntq.training.momentbox.core.realtime

data class SocketTuning(
    val timeoutMs: Long = 20000, val attempts: Int = 5, val delayMinMs: Long = 1000,
    val delayMaxMs: Long = 5000, val randomization: Double = 0.5
) {
    init {
        require(timeoutMs in 1000..120000)
        require(attempts in 0..10)
        require(delayMinMs in 100..30000)
        require(delayMaxMs in delayMinMs..60000)
        require(randomization.isFinite() && randomization in 0.0..1.0)
    }
}
