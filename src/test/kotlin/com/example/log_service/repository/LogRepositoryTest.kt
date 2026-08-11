package com.example.log_service.repository

import com.example.log_service.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LogRepositoryTest {

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun selectOneViaJdbcTemplateSucceeds() {
		val result = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
		assertEquals(1, result)
	}
}
