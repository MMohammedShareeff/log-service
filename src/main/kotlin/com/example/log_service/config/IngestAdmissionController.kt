package com.example.log_service.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class IngestAdmissionController(
    @Value("\${app.backpressure.enabled:false}") private val enabled: Boolean,
    @Value("\${app.backpressure.max-pending-rows:20000}") private val maxPendingRows: Long,
    @Value("\${app.backpressure.max-pending-bytes:26214400}") private val maxPendingBytes: Long,
    @Value("\${app.backpressure.retry-after-seconds:1}") val retryAfterSeconds: Long,
) {
    private val pendingBytes = AtomicLong(0)
    private val pendingRows = AtomicLong(0)

    fun tryAdmitBytes(requestBytes: Long): AdmissionDecision {
        if (!enabled || requestBytes <= 0) return AdmissionDecision.Admitted

        if (requestBytes > maxPendingBytes) {
            return AdmissionDecision.RejectedTooLarge(
                "request body of $requestBytes bytes exceeds the maximum of $maxPendingBytes bytes",
            )
        }

        val newTotal = pendingBytes.addAndGet(requestBytes)
        if (newTotal > maxPendingBytes) {
            pendingBytes.addAndGet(-requestBytes)
            return AdmissionDecision.RejectedOverloaded
        }

        return AdmissionDecision.Admitted
    }

    fun releaseBytes(requestBytes: Long) {
        if (!enabled || requestBytes <= 0) return
        pendingBytes.addAndGet(-requestBytes)
    }

    fun tryAdmitRows(rowCount: Int): AdmissionDecision {
        if (!enabled || rowCount <= 0) return AdmissionDecision.Admitted

        if (rowCount > maxPendingRows) {
            return AdmissionDecision.RejectedTooLarge(
                "batch of $rowCount entries exceeds the maximum of $maxPendingRows",
            )
        }

        val newTotal = pendingRows.addAndGet(rowCount.toLong())
        if (newTotal > maxPendingRows) {
            pendingRows.addAndGet(-rowCount.toLong())
            return AdmissionDecision.RejectedOverloaded
        }

        return AdmissionDecision.Admitted
    }

    fun releaseRows(rowCount: Int) {
        if (!enabled || rowCount <= 0) return
        pendingRows.addAndGet(-rowCount.toLong())
    }
}

sealed interface AdmissionDecision {
    data object Admitted : AdmissionDecision

    data class RejectedTooLarge(val reason: String) : AdmissionDecision

    data object RejectedOverloaded : AdmissionDecision
}