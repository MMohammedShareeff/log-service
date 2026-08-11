package com.example.log_service.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class LogRepository(
	private val jdbcTemplate: JdbcTemplate,
)
