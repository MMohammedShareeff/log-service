package com.example.log_service.controller

import com.example.log_service.TestcontainersConfiguration
import com.example.log_service.dto.IngestRequest
import com.example.log_service.dto.LogEntryRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class QueryControllerTest {

	@LocalServerPort
	private var port: Int = 0

	private val httpClient = HttpClient.newHttpClient()
	private val objectMapper = ObjectMapper()

	@Test
	fun filtersLogsByServiceLevelTimeTextAndAttributes() {
		val service = "query-filter-${System.nanoTime()}"
		val timestamp = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
		ingest(
			listOf(
				log(timestamp, "info", service, "request completed", mapOf("status" to 200, "cached" to false)),
				log(timestamp, "error", service, "request failed", mapOf("status" to 500, "cached" to false)),
				log(timestamp, "info", "$service-other", "request completed", mapOf("status" to 200)),
			),
		)

		val response = getMap(
			"/logs?service=$service" +
				"&level=info" +
				"&since=${encode(timestamp.minusMinutes(1).toString())}" +
				"&until=${encode(timestamp.plusMinutes(1).toString())}" +
				"&q=completed" +
				"&attr.status=200" +
				"&attr.cached=false" +
				"&limit=10",
		)

		assertEquals(HttpStatus.OK.value(), response.status)
		val logs = response.body["logs"] as List<*>
		assertEquals(1, logs.size)
		val first = logs.single() as Map<*, *>
		assertEquals(service, first["service"])
		assertEquals("request completed", first["message"])
	}

	@Test
	fun invalidInputsReturnBadRequestErrors() {
		listOf(
			"/logs?level=fatal" to "level must be one of: debug, info, warn, error",
			"/logs?since=not-a-date" to "since must be ISO 8601",
			"/logs?until=not-a-date" to "until must be ISO 8601",
			"/logs?since=2026-08-12T10:00:00Z&until=2026-08-12T09:00:00Z" to "since must be before or equal to until",
			"/logs?limit=0" to "limit must be between 1 and 1000",
			"/logs?limit=abc" to "limit must be an integer",
			"/logs?cursor=bad" to "cursor is invalid",
			"/logs?attr.=x" to "attribute filter key is invalid",
			"/logs?unknown=x" to "unknown query parameter: unknown",
		).forEach { (path, expectedError) ->
			val response = getMap(path)

			assertEquals(HttpStatus.BAD_REQUEST.value(), response.status)
			assertEquals(expectedError, response.body["error"])
		}
	}

	@Test
	fun keysetPaginationDoesNotSkipDuplicateTimestamps() {
		val service = "query-page-${System.nanoTime()}"
		val timestamp = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0)
		ingest(
			listOf(
				log(timestamp, "info", service, "first", emptyMap()),
				log(timestamp, "info", service, "second", emptyMap()),
				log(timestamp.minusSeconds(1), "info", service, "third", emptyMap()),
			),
		)

		val firstPage = getMap("/logs?service=$service&limit=2")
		assertEquals(HttpStatus.OK.value(), firstPage.status)
		val firstLogs = firstPage.body["logs"] as List<*>
		assertEquals(2, firstLogs.size)
		val cursor = firstPage.body["next_cursor"] as String?
		assertNotNull(cursor)

		val secondPage = getMap("/logs?service=$service&limit=2&cursor=${encode(checkNotNull(cursor))}")
		assertEquals(HttpStatus.OK.value(), secondPage.status)
		val secondLogs = secondPage.body["logs"] as List<*>
		assertEquals(1, secondLogs.size)

		val messages = (firstLogs + secondLogs)
			.map { (it as Map<*, *>)["message"] }
			.toSet()
		assertEquals(setOf("first", "second", "third"), messages)
		assertEquals(null, secondPage.body["next_cursor"])
	}

	private fun ingest(logs: List<LogEntryRequest>) {
		val request = HttpRequest.newBuilder(uri("/logs"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(IngestRequest(logs))))
			.build()
		val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
		val body = objectMapper.readValue(response.body(), Map::class.java)

		assertEquals(HttpStatus.OK.value(), response.statusCode())
		assertEquals(logs.size, body["accepted"])
		assertTrue((body["rejected"] as List<*>).isEmpty())
	}

	private fun log(
		timestamp: OffsetDateTime,
		level: String,
		service: String,
		message: String,
		attributes: Map<String, Any>,
	): LogEntryRequest {
		return LogEntryRequest(
			timestamp = timestamp.toString(),
			level = level,
			service = service,
			message = message,
			attributes = attributes,
		)
	}

	private fun getMap(path: String): TestHttpResponse {
		val request = HttpRequest.newBuilder(uri(path))
			.GET()
			.build()
		val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
		@Suppress("UNCHECKED_CAST")
		return TestHttpResponse(
			status = response.statusCode(),
			body = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>,
		)
	}

	private fun uri(path: String): URI {
		return URI.create("http://localhost:$port$path")
	}

	private fun encode(value: String): String {
		return URLEncoder.encode(value, StandardCharsets.UTF_8)
	}

	private data class TestHttpResponse(
		val status: Int,
		val body: Map<*, *>,
	)
}
