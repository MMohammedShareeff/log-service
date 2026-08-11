package com.example.log_service.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.OffsetDateTime

@Repository
class LogRepository(
	private val jdbcTemplate: JdbcTemplate,
) {

	fun insertAll(logs: List<LogRecord>) {
		if (logs.isEmpty()) {
			return
		}

		jdbcTemplate.execute { connection: Connection ->
			connection.prepareStatement(INSERT_SQL).use { statement ->
				val timestamps = connection.createArrayOf(
					"timestamptz",
					logs.map { it.timestamp }.toTypedArray(),
				)
				val levels = connection.createArrayOf("text", logs.map { it.level }.toTypedArray())
				val services = connection.createArrayOf("text", logs.map { it.service }.toTypedArray())
				val messages = connection.createArrayOf("text", logs.map { it.message }.toTypedArray())
				val attributes = connection.createArrayOf(
					"jsonb",
					logs.map { objectMapper.writeValueAsString(it.attributes) }.toTypedArray(),
				)

				statement.setArray(1, timestamps)
				statement.setArray(2, levels)
				statement.setArray(3, services)
				statement.setArray(4, messages)
				statement.setArray(5, attributes)

				statement.executeUpdate()
			}
		}
	}

	private companion object {
		private val objectMapper = ObjectMapper()

		private const val INSERT_SQL = """
			INSERT INTO logs (timestamp, level, service, message, attributes)
			SELECT *
			FROM unnest(
				?::timestamptz[],
				?::text[],
				?::text[],
				?::text[],
				?::jsonb[]
			)
		"""
	}
}

data class LogRecord(
	val timestamp: OffsetDateTime,
	val level: String,
	val service: String,
	val message: String,
	val attributes: Map<String, Any>,
)
