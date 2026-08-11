package com.example.log_service.controller

import com.example.log_service.dto.IngestRequest
import com.example.log_service.dto.IngestResponse
import com.example.log_service.service.IngestService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class IngestController(
    private val ingestService: IngestService,
) {

    @PostMapping("/logs")
    fun ingest(@RequestBody request: IngestRequest): ResponseEntity<IngestResponse> {
        return ResponseEntity.ok(ingestService.ingest(request))
    }
}
