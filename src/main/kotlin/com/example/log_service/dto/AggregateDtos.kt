package com.example.log_service.dto

import java.util.Collections.emptyMap

data class AggregateBucket(
    val start: String,
    val group: String?,
    val count: Long,
)

data class AggregateResponse(
    val buckets: List<AggregateBucket>,
)

data class AggregateQueryParams(
    val service: String? = null,
    val level: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val q: String? = null,
    val since: String,
    val until: String,
    val bucket: String,
    val groupBy: String? = null,
)
