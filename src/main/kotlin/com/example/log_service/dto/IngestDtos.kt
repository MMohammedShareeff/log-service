package com.example.log_service.dto

data class LogEntryRequest(
    val timestamp: String? = null,
    val level: String? = null,
    val service: String? = null,
    val message: String? = null,
    val attributes: Map<String, Any?>? = null,
)

data class IngestRequest(
    val logs: List<LogEntryRequest> = emptyList(),
)

data class RejectedEntry(
    val index: Int,
    val reason: String,
)

data class IngestResponse(
    val accepted: Int,
    val rejected: List<RejectedEntry>,
)
