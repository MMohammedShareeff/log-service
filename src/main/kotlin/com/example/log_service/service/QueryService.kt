package com.example.log_service.service

import com.example.log_service.dto.AggregateBucket
import com.example.log_service.dto.AggregateResponse
import com.example.log_service.dto.LogEntryResponse
import com.example.log_service.dto.QueryLogsResponse
import com.example.log_service.repository.AggregateGroupBy
import com.example.log_service.repository.AggregateQueryCriteria
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

    fun aggregate(parameterMap: Map<String, Array<String>>): AggregateResponse {
        val criteria = parseAggregate(parameterMap)
        val buckets = logRepository.aggregate(criteria)

        return AggregateResponse(
            buckets = buckets.map {
                AggregateBucket(
                    start = it.start.toString(),
                    group = it.group,
                    count = it.count,
                )
            },
        )
    }

    private fun parse(parameterMap: Map<String, Array<String>>): LogQueryCriteria {
        validateParameterNames(parameterMap, ALLOWED_QUERY_PARAMS)
        return parseFilters(
            parameterMap = parameterMap,
            requireSinceUntil = false,
            parseLimit = true,
            parseCursor = true,
        )
    }

    private fun parseAggregate(parameterMap: Map<String, Array<String>>): AggregateQueryCriteria {
        validateParameterNames(parameterMap, ALLOWED_AGGREGATE_PARAMS)

        val filters = parseFilters(
            parameterMap = parameterMap,
            requireSinceUntil = true,
            parseLimit = false,
            parseCursor = false,
        )

        val bucket = requiredNonBlank(parameterMap, "bucket").let { value ->
            AggregateBucketSize.fromValue(value) ?: throw BadRequestException("bucket must be one of: 1m, 5m, 1h, 1d")
        }

        val groupBy = optionalNonBlank(parameterMap, "group_by")?.let { value ->
            when (value) {
                "service" -> AggregateGroupBy.SERVICE
                "level" -> AggregateGroupBy.LEVEL
                else -> throw BadRequestException("group_by must be one of: service, level")
            }
        }

        return AggregateQueryCriteria(
            filters = filters,
            bucket = bucket,
            groupBy = groupBy,
        )
    }

    private fun validateParameterNames(
        parameterMap: Map<String, Array<String>>,
        allowedParams: Set<String>,
    ) {
        val attributes = linkedMapOf<String, String>()
        val seen = mutableSetOf<String>()

        parameterMap.keys.forEach { name ->
            if (name.startsWith(ATTRIBUTE_PREFIX)) {
                val key = name.removePrefix(ATTRIBUTE_PREFIX)
                if (!ATTRIBUTE_KEY_REGEX.matches(key)) {
                    throw BadRequestException("attribute filter key is invalid")
                }
                singleValue(parameterMap, name)
            } else if (name !in allowedParams) {
                throw BadRequestException("unknown query parameter: $name")
            }
            if (!seen.add(name)) {
                throw BadRequestException("duplicate query parameter: $name")
            }
        }
    }

    private fun parseFilters(
        parameterMap: Map<String, Array<String>>,
        requireSinceUntil: Boolean,
        parseLimit: Boolean,
        parseCursor: Boolean,
    ): LogQueryCriteria {
        val attributes = linkedMapOf<String, String>()
        parameterMap.keys.filter { it.startsWith(ATTRIBUTE_PREFIX) }.forEach { name ->
                attributes[name.removePrefix(ATTRIBUTE_PREFIX)] = singleValue(parameterMap, name)
            }

        val service = optionalNonBlank(parameterMap, "service")
        val level = optionalNonBlank(parameterMap, "level")?.also {
            if (it !in VALID_LEVELS) {
                throw BadRequestException("level must be one of: debug, info, warn, error")
            }
        }
        val since = if (requireSinceUntil) {
            requiredDateTime(parameterMap, "since")
        } else {
            optionalDateTime(parameterMap, "since")
        }
        val until = if (requireSinceUntil) {
            requiredDateTime(parameterMap, "until")
        } else {
            optionalDateTime(parameterMap, "until")
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw BadRequestException("since must be before or equal to until")
        }

        val q = optionalNonBlank(parameterMap, "q")
        val limit = if (parseLimit) optionalLimit(parameterMap) else DEFAULT_LIMIT
        val cursor = if (parseCursor) {
            optionalNonBlank(parameterMap, "cursor")?.let(::decodeCursor)
        } else {
            null
        }

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

    private fun requiredNonBlank(parameterMap: Map<String, Array<String>>, name: String): String {
        if (!parameterMap.containsKey(name)) {
            throw BadRequestException("$name is required")
        }
        return optionalNonBlank(parameterMap, name) ?: throw BadRequestException("$name is required")
    }

    private fun optionalDateTime(parameterMap: Map<String, Array<String>>, name: String): OffsetDateTime? {
        val value = optionalNonBlank(parameterMap, name) ?: return null
        return parseDateTime(value, name)
    }

    private fun requiredDateTime(parameterMap: Map<String, Array<String>>, name: String): OffsetDateTime {
        val value = requiredNonBlank(parameterMap, name)
        return parseDateTime(value, name)
    }

    private fun parseDateTime(value: String, name: String): OffsetDateTime {
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): LogCursor {
    try {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val parts = decoded.split("|")
        if (parts.size != 2) {
            throw BadRequestException("cursor is invalid")
        }
        val timestamp = OffsetDateTime.parse(parts[0])
        val id = parts[1].toLongOrNull() ?: throw BadRequestException("cursor is invalid")
        return LogCursor(timestamp, id)
    } catch (e: BadRequestException) {
        throw e
    } catch (e: Exception) {
        throw BadRequestException("cursor is invalid")
    }
}

    private companion object {
        private const val ATTRIBUTE_PREFIX = "attr."
        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 1000
        private val VALID_LEVELS = setOf("debug", "info", "warn", "error")
        private val ALLOWED_QUERY_PARAMS = setOf("service", "level", "since", "until", "q", "limit", "cursor")
        private val ALLOWED_AGGREGATE_PARAMS = setOf("service", "level", "since", "until", "q", "bucket", "group_by")
        private val ATTRIBUTE_KEY_REGEX = Regex("[A-Za-z0-9_.-]+")
    }
}

data class LogCursor(
    val timestamp: OffsetDateTime,
    val id: Long,
)

class BadRequestException(message: String) : RuntimeException(message)

enum class AggregateBucketSize(val wireValue: String, val interval: String) {
    ONE_MINUTE("1m", "1 minute"), FIVE_MINUTES("5m", "5 minutes"), ONE_HOUR("1h", "1 hour"), ONE_DAY("1d", "1 day");

    companion object {
        fun fromValue(value: String): AggregateBucketSize? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}
