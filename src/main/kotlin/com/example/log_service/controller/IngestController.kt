package com.example.log_service.controller

import com.example.log_service.dto.IngestRequest
import com.example.log_service.dto.IngestResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class IngestController() {

    @PostMapping("/logs")
    fun ingest(@RequestBody request: IngestRequest): ResponseEntity<IngestResponse> {
        throw NotImplementedError("POST /logs not yet implemented")
    }
}
