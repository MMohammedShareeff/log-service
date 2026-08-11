package com.example.log_service.service

import com.example.log_service.dto.IngestRequest
import com.example.log_service.dto.IngestResponse
import com.example.log_service.dto.LogEntryRequest
import com.example.log_service.dto.RejectedEntry
import com.example.log_service.repository.LogRecord
import com.example.log_service.repository.LogRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Service
class IngestService(
	private val logRepository: LogRepository,
	@Value("\${app.ingest.batch-size:1000}")
	private val batchSize: Int,
	private val clock: Clock = Clock.systemUTC(),
) {

	fun ingest(request: IngestRequest): IngestResponse {
		val accepted = mutableListOf<LogRecord>()
		val rejected = mutableListOf<RejectedEntry>()

		request.logs.forEachIndexed { index, entry ->
			when (val result = validate(entry)) {
				is ValidationResult.Accepted -> accepted += result.log
				is ValidationResult.Rejected -> rejected += RejectedEntry(index, result.reason)
			}
		}

		accepted.chunked(effectiveBatchSize()).forEach(logRepository::insertAll)

		return IngestResponse(
			accepted = accepted.size,
			rejected = rejected,
		)
	}

	private fun validate(entry: LogEntryRequest): ValidationResult {
		val timestamp = parseTimestamp(entry.timestamp)
			?: return ValidationResult.Rejected("timestamp must be ISO 8601")

		if (timestamp.toInstant().isAfter(clock.instant().plusSeconds(MAX_FUTURE_SECONDS))) {
			return ValidationResult.Rejected("timestamp cannot be more than 5 minutes in the future")
		}

		val level = entry.level?.trim()
			?: return ValidationResult.Rejected("level is required")
		if (level !in VALID_LEVELS) {
			return ValidationResult.Rejected("level must be one of: debug, info, warn, error")
		}

		val service = entry.service?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return ValidationResult.Rejected("service must not be empty")

		val message = entry.message?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return ValidationResult.Rejected("message must not be empty")

		val attributes = entry.attributes.orEmpty()
		val invalidAttribute = attributes.entries.firstOrNull { (_, value) -> !value.isFlatJsonScalar() }
		if (invalidAttribute != null) {
			return ValidationResult.Rejected(
				"attributes.${invalidAttribute.key} must be a string, number, or boolean",
			)
		}

		return ValidationResult.Accepted(
			LogRecord(
				timestamp = timestamp,
				level = level,
				service = service,
				message = message,
				attributes = attributes.mapValues { (_, value) -> value as Any },
			),
		)
	}

	private fun parseTimestamp(timestamp: String?): OffsetDateTime? {
		if (timestamp.isNullOrBlank()) {
			return null
		}

		return try {
			OffsetDateTime.parse(timestamp)
		} catch (_: DateTimeParseException) {
			null
		}
	}

	private fun Any?.isFlatJsonScalar(): Boolean {
		return when (this) {
			is String,
			is Boolean,
			is Byte,
			is Short,
			is Int,
			is Long,
			is Float,
			is Double,
			is BigInteger,
			is BigDecimal,
				-> true
			else -> false
		}
	}

	private fun effectiveBatchSize(): Int {
		return batchSize.coerceAtLeast(1)
	}

	private sealed interface ValidationResult {
		data class Accepted(val log: LogRecord) : ValidationResult
		data class Rejected(val reason: String) : ValidationResult
	}

	private companion object {
		private val VALID_LEVELS = setOf("debug", "info", "warn", "error")
		private const val MAX_FUTURE_SECONDS = 5 * 60L
	}
}
