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

	@Test
	fun aggregateReturnsAscendingBucketsWithNullGroupWhenGroupByIsOmitted() {
		val service = "aggregate-buckets-${System.nanoTime()}"
		val since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).withSecond(0).withNano(0)
		val until = since.plusMinutes(10)
		ingest(
			listOf(
				log(since.plusSeconds(10), "info", service, "first aggregate row", mapOf("status" to 200)),
				log(since.plusSeconds(30), "warn", service, "second aggregate row", mapOf("status" to 200)),
				log(since.plusMinutes(1).plusSeconds(5), "info", service, "third aggregate row", mapOf("status" to 500)),
			),
		)

		val response = getMap(
			"/logs/aggregate?service=$service" +
				"&since=${encode(since.toString())}" +
				"&until=${encode(until.toString())}" +
				"&bucket=1m" +
				"&attr.status=200",
		)

		assertEquals(HttpStatus.OK.value(), response.status)
		val buckets = response.body["buckets"] as List<*>
		assertEquals(1, buckets.size)
		val bucket = buckets.single() as Map<*, *>
		assertEquals(since.toString(), bucket["start"])
		assertEquals(null, bucket["group"])
		assertEquals(2, bucket["count"])
	}

	@Test
	fun aggregateCanGroupByLevelAndReuseTextFilter() {
		val service = "aggregate-group-${System.nanoTime()}"
		val since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).withSecond(0).withNano(0)
		val until = since.plusMinutes(10)
		ingest(
			listOf(
				log(since.plusSeconds(10), "info", service, "checkout completed", emptyMap()),
				log(since.plusSeconds(20), "info", service, "checkout completed again", emptyMap()),
				log(since.plusSeconds(30), "error", service, "checkout failed", emptyMap()),
				log(since.plusMinutes(1), "info", service, "not matched", emptyMap()),
			),
		)

		val response = getMap(
			"/logs/aggregate?service=$service" +
				"&since=${encode(since.toString())}" +
				"&until=${encode(until.toString())}" +
				"&bucket=5m" +
				"&group_by=level" +
				"&q=checkout",
		)

		assertEquals(HttpStatus.OK.value(), response.status)
		val buckets = response.body["buckets"] as List<*>
		assertEquals(listOf("error", "info"), buckets.map { (it as Map<*, *>)["group"] })
		assertEquals(listOf(1, 2), buckets.map { (it as Map<*, *>)["count"] })
	}

	@Test
	fun invalidAggregateInputsReturnBadRequestErrors() {
		listOf(
			"/logs/aggregate?until=2026-08-12T10:00:00Z&bucket=1m" to "since is required",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&bucket=1m" to "until is required",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&until=2026-08-12T10:00:00Z" to "bucket is required",
			"/logs/aggregate?since=bad&until=2026-08-12T10:00:00Z&bucket=1m" to "since must be ISO 8601",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&until=bad&bucket=1m" to "until must be ISO 8601",
			"/logs/aggregate?since=2026-08-12T10:00:00Z&until=2026-08-12T09:00:00Z&bucket=1m" to "since must be before or equal to until",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&until=2026-08-12T10:00:00Z&bucket=10m" to "bucket must be one of: 1m, 5m, 1h, 1d",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&until=2026-08-12T10:00:00Z&bucket=1m&group_by=message" to "group_by must be one of: service, level",
			"/logs/aggregate?since=2026-08-12T09:00:00Z&until=2026-08-12T10:00:00Z&bucket=1m&limit=10" to "unknown query parameter: limit",
		).forEach { (path, expectedError) ->
			val response = getMap(path)

			assertEquals(HttpStatus.BAD_REQUEST.value(), response.status)
			assertEquals(expectedError, response.body["error"])
		}
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
