package com.example.log_service.service

import com.example.log_service.TestcontainersConfiguration
import com.example.log_service.dto.IngestRequest
import com.example.log_service.dto.LogEntryRequest
import com.example.log_service.dto.RejectedEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@SpringBootTest(properties = ["app.ingest.batch-size=2"])
@Import(TestcontainersConfiguration::class)
class IngestServiceTest {

	@Autowired
	private lateinit var ingestService: IngestService

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun invalidEntriesAreRejectedWithoutFailingBatch() {
		val now = OffsetDateTime.now(ZoneOffset.UTC)
		val response = ingestService.ingest(
			IngestRequest(
				logs = listOf(
					validEntry(now, "accepted-one"),
					validEntry(now.plusMinutes(6), "future"),
					validEntry(now, "bad-level").copy(level = "fatal"),
					validEntry(now, "blank-service").copy(service = " "),
					validEntry(now, "blank-message").copy(message = ""),
					validEntry(now, "nested-attribute").copy(attributes = mapOf("nested" to mapOf("x" to "y"))),
					validEntry(now, "accepted-two").copy(attributes = mapOf("attempt" to 2, "sampled" to true)),
				),
			),
		)

		assertEquals(2, response.accepted)
		assertEquals(
			listOf(
				RejectedEntry(1, "timestamp cannot be more than 5 minutes in the future"),
				RejectedEntry(2, "level must be one of: debug, info, warn, error"),
				RejectedEntry(3, "service must not be empty"),
				RejectedEntry(4, "message must not be empty"),
				RejectedEntry(5, "attributes.nested must be a string, number, or boolean"),
			),
			response.rejected,
		)

		val inserted = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM logs WHERE service IN ('accepted-one', 'accepted-two')",
			Long::class.java,
		)
		assertEquals(2L, inserted)
	}

	@Test
	fun nullArrayAndObjectAttributesAreRejectedAsNonFlat() {
		val now = OffsetDateTime.now(ZoneOffset.UTC)
		val response = ingestService.ingest(
			IngestRequest(
				logs = listOf(
					validEntry(now, "null-attribute").copy(attributes = mapOf("x" to null)),
					validEntry(now, "array-attribute").copy(attributes = mapOf("x" to listOf("y"))),
				),
			),
		)

		assertEquals(0, response.accepted)
		assertEquals(2, response.rejected.size)
		assertTrue(response.rejected.all { it.reason.startsWith("attributes.x") })
	}

	private fun validEntry(timestamp: OffsetDateTime, service: String): LogEntryRequest {
		return LogEntryRequest(
			timestamp = timestamp.toString(),
			level = "info",
			service = service,
			message = "hello",
			attributes = mapOf("region" to "local"),
		)
	}
}
