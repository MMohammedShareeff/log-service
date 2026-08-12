package com.example.log_service.service

import com.example.log_service.dto.LogEntryResponse
import com.example.log_service.dto.QueryLogsResponse
import com.example.log_service.repository.LogQueryCriteria
import com.example.log_service.repository.LogRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

@Service
class QueryService(
	private val logRepository: LogRepository,
) {

	fun query(parameterMap: Map<String, Array<String>>): QueryLogsResponse {
		val criteria = parse(parameterMap)
		val rows = logRepository.findLogs(criteria.copy(limit = criteria.limit + 1))
		val page = rows.take(criteria.limit)
		val nextCursor = if (rows.size > criteria.limit) {
			page.lastOrNull()?.let { encodeCursor(LogCursor(it.timestamp, it.id)) }
		} else {
			null
		}

		return QueryLogsResponse(
			logs = page.map {
				LogEntryResponse(
					id = it.id.toString(),
					timestamp = it.timestamp.toString(),
					level = it.level,
					service = it.service,
					message = it.message,
					attributes = it.attributes,
				)
			},
			nextCursor = nextCursor,
		)
	}

	private fun parse(parameterMap: Map<String, Array<String>>): LogQueryCriteria {
		val attributes = linkedMapOf<String, String>()
		val seen = mutableSetOf<String>()

		parameterMap.keys.forEach { name ->
			if (name.startsWith(ATTRIBUTE_PREFIX)) {
				val key = name.removePrefix(ATTRIBUTE_PREFIX)
				if (!ATTRIBUTE_KEY_REGEX.matches(key)) {
					throw BadRequestException("attribute filter key is invalid")
				}
				attributes[key] = singleValue(parameterMap, name)
			} else if (name !in ALLOWED_PARAMS) {
				throw BadRequestException("unknown query parameter: $name")
			}
			if (!seen.add(name)) {
				throw BadRequestException("duplicate query parameter: $name")
			}
		}

		val service = optionalNonBlank(parameterMap, "service")
		val level = optionalNonBlank(parameterMap, "level")?.also {
			if (it !in VALID_LEVELS) {
				throw BadRequestException("level must be one of: debug, info, warn, error")
			}
		}
		val since = optionalDateTime(parameterMap, "since")
		val until = optionalDateTime(parameterMap, "until")
		if (since != null && until != null && since.isAfter(until)) {
			throw BadRequestException("since must be before or equal to until")
		}

		val q = optionalNonBlank(parameterMap, "q")
		val limit = optionalLimit(parameterMap)
		val cursor = optionalNonBlank(parameterMap, "cursor")?.let(::decodeCursor)

		return LogQueryCriteria(
			service = service,
			level = level,
			since = since,
			until = until,
			attributes = attributes,
			q = q,
			limit = limit,
			cursor = cursor,
		)
	}

	private fun singleValue(parameterMap: Map<String, Array<String>>, name: String): String {
		val values = parameterMap[name].orEmpty()
		if (values.size != 1) {
			throw BadRequestException("$name must be provided once")
		}
		return values.single()
	}

	private fun optionalNonBlank(parameterMap: Map<String, Array<String>>, name: String): String? {
		if (!parameterMap.containsKey(name)) {
			return null
		}
		val value = singleValue(parameterMap, name)
		if (value.isBlank()) {
			throw BadRequestException("$name must not be empty")
		}
		return value
	}

	private fun optionalDateTime(parameterMap: Map<String, Array<String>>, name: String): OffsetDateTime? {
		val value = optionalNonBlank(parameterMap, name) ?: return null
		return try {
			OffsetDateTime.parse(value)
		} catch (_: DateTimeParseException) {
			throw BadRequestException("$name must be ISO 8601")
		}
	}

	private fun optionalLimit(parameterMap: Map<String, Array<String>>): Int {
		if (!parameterMap.containsKey("limit")) {
			return DEFAULT_LIMIT
		}
		val value = singleValue(parameterMap, "limit")
		val limit = value.toIntOrNull() ?: throw BadRequestException("limit must be an integer")
		if (limit !in 1..MAX_LIMIT) {
			throw BadRequestException("limit must be between 1 and $MAX_LIMIT")
		}
		return limit
	}

	private fun encodeCursor(cursor: LogCursor): String {
		val raw = "${cursor.timestamp}|${cursor.id}"
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
	}

	private fun decodeCursor(cursor: String): LogCursor {
		val decoded = try {
			String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
		} catch (_: IllegalArgumentException) {
			throw BadRequestException("cursor is invalid")
		}
		val parts = decoded.split("|")
		if (parts.size != 2) {
			throw BadRequestException("cursor is invalid")
		}
		val timestamp = try {
			OffsetDateTime.parse(parts[0])
		} catch (_: DateTimeParseException) {
			throw BadRequestException("cursor is invalid")
		}
		val id = parts[1].toLongOrNull() ?: throw BadRequestException("cursor is invalid")
		return LogCursor(timestamp, id)
	}

	private companion object {
		private const val ATTRIBUTE_PREFIX = "attr."
		private const val DEFAULT_LIMIT = 100
		private const val MAX_LIMIT = 1000
		private val VALID_LEVELS = setOf("debug", "info", "warn", "error")
		private val ALLOWED_PARAMS = setOf("service", "level", "since", "until", "q", "limit", "cursor")
		private val ATTRIBUTE_KEY_REGEX = Regex("[A-Za-z0-9_.-]+")
	}
}

data class LogCursor(
	val timestamp: OffsetDateTime,
	val id: Long,
)

class BadRequestException(message: String) : RuntimeException(message)
