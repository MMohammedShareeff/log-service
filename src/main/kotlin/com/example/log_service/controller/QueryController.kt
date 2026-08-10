package com.example.log_service.controller

import com.example.log_service.dto.AggregateResponse
import com.example.log_service.dto.QueryLogsResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class QueryController(
) {

    @GetMapping("/logs")
    fun queryLogs(
        @RequestParam(required = false) service: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) since: String?,
        @RequestParam(required = false) until: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<QueryLogsResponse> {
        throw NotImplementedError("GET /logs not yet implemented")
    }

    @GetMapping("/logs/aggregate")
    fun aggregateLogs(
        @RequestParam(required = false) service: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam since: String,
        @RequestParam until: String,
        @RequestParam bucket: String,
        @RequestParam(required = false, name = "group_by") groupBy: String?,
    ): ResponseEntity<AggregateResponse> {
        throw NotImplementedError("GET /logs/aggregate not yet implemented")
    }
}
