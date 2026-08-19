package com.example.log_service.dto

import java.util.Collections.emptyMap

data class LogEntryResponse(
    val id: String,
    val timestamp: String,
    val level: String,
    val service: String,
    val message: String,
    val attributes: Map<String, Any?>,
)

data class QueryLogsResponse(
    val logs: List<LogEntryResponse>,
    @get:com.fasterxml.jackson.annotation.JsonProperty("next_cursor")
    val nextCursor: String?,
)

data class ErrorResponse(
    val error: String,
)


data class LogQueryParams(
    val service: String? = null,
    val level: String? = null,
    val since: String? = null,
    val until: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val q: String? = null,
    val limit: Int = 100,
    val cursor: String? = null,
)
