package com.example.log_service.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(
    private val jdbcTemplate: JdbcTemplate,
) {
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return try {
            jdbcTemplate.execute("SELECT 1")
            ResponseEntity
                .status(HttpStatus.OK)
                .body(mapOf("status" to "UP"))
        } catch (_: Exception) {
            ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "DOWN"))
        }
    }
}