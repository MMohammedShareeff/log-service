package com.example.log_service.controller

import com.example.log_service.dto.AggregateResponse
import com.example.log_service.dto.QueryLogsResponse
import com.example.log_service.service.QueryService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class QueryController(
    private val queryService: QueryService,
) {

    @GetMapping("/logs")
    fun queryLogs(request: HttpServletRequest): ResponseEntity<QueryLogsResponse> {
        return ResponseEntity.ok(queryService.query(request.parameterMap))
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
