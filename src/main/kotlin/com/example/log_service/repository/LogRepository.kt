package com.example.log_service.repository

import com.example.log_service.service.LogCursor
import com.example.log_service.service.AggregateBucketSize
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class LogRepository(
	private val jdbcTemplate: JdbcTemplate,
) {
	private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

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

	fun findLogs(criteria: LogQueryCriteria): List<StoredLogRecord> {
		val params = MapSqlParameterSource()
			.addValue("limit", criteria.limit)
		val whereClause = buildWhereClause(criteria, params, includeCursor = true)

		return namedParameterJdbcTemplate.query(
			QUERY_SQL_PREFIX + whereClause + QUERY_SQL_SUFFIX,
			params,
			STORED_LOG_ROW_MAPPER,
		)
	}

	fun aggregate(criteria: AggregateQueryCriteria): List<AggregateBucketRecord> {
		val params = MapSqlParameterSource()
			.addValue("bucketInterval", criteria.bucket.interval)
			.addValue("origin", criteria.filters.since)
		val whereClause = buildWhereClause(criteria.filters, params, includeCursor = false)
		val groupSelect = criteria.groupBy?.sqlExpression ?: "NULL::text"
		val groupByClause = if (criteria.groupBy == null) {
			"bucket_start"
		} else {
			"bucket_start, group_name"
		}

		return namedParameterJdbcTemplate.query(
			"""
			SELECT
				date_bin(CAST(:bucketInterval AS interval), timestamp, :origin) AS bucket_start,
				$groupSelect AS group_name,
				count(*) AS count
			FROM logs
			$whereClause
			GROUP BY $groupByClause
			ORDER BY bucket_start ASC, group_name ASC NULLS FIRST
			""".trimIndent(),
			params,
			AGGREGATE_ROW_MAPPER,
		)
	}

	private fun buildWhereClause(
		criteria: LogQueryCriteria,
		params: MapSqlParameterSource,
		includeCursor: Boolean,
	): String {
		val predicates = mutableListOf<String>()

		criteria.service?.let {
			predicates += "service = :service"
			params.addValue("service", it)
		}

		criteria.level?.let {
			predicates += "level = :level"
			params.addValue("level", it)
		}

		criteria.since?.let {
			predicates += "timestamp >= :since"
			params.addValue("since", it)
		}

		criteria.until?.let {
			predicates += "timestamp <= :until"
			params.addValue("until", it)
		}

		criteria.q?.let {
			predicates += "message ILIKE ('%' || :q || '%')"
			params.addValue("q", it)
		}

		criteria.attributes.entries.forEachIndexed { index, (key, value) ->
			val keyParam = "attrKey$index"
			val valueParam = "attrValue$index"
			predicates += "attributes ->> :$keyParam = :$valueParam"
			params.addValue(keyParam, key)
			params.addValue(valueParam, value)
		}

		if (includeCursor) {
			criteria.cursor?.let {
				predicates += "(timestamp, id) < (:cursorTimestamp, :cursorId)"
				params.addValue("cursorTimestamp", it.timestamp)
				params.addValue("cursorId", it.id)
			}
		}

		return if (predicates.isEmpty()) {
			""
		} else {
			predicates.joinToString(prefix = " WHERE ", separator = " AND ")
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

		private const val QUERY_SQL_PREFIX = """
			SELECT id, timestamp, level, service, message, attributes
			FROM logs
		"""

		private const val QUERY_SQL_SUFFIX = """
			ORDER BY timestamp DESC, id DESC
			LIMIT :limit
		"""

		private val STORED_LOG_ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
			val rawAttributes = objectMapper.readValue(rs.getString("attributes"), Map::class.java)
			StoredLogRecord(
				id = rs.getLong("id"),
				timestamp = rs.getObject("timestamp", OffsetDateTime::class.java),
				level = rs.getString("level"),
				service = rs.getString("service"),
				message = rs.getString("message"),
				attributes = rawAttributes.entries.associate { (key, value) -> key.toString() to value },
			)
		}

		private val AGGREGATE_ROW_MAPPER = RowMapper { rs: ResultSet, _: Int ->
			AggregateBucketRecord(
				start = rs.getObject("bucket_start", OffsetDateTime::class.java),
				group = rs.getString("group_name"),
				count = rs.getLong("count"),
			)
		}
	}
}

data class LogRecord(
	val timestamp: OffsetDateTime,
	val level: String,
	val service: String,
	val message: String,
	val attributes: Map<String, Any>,
)

data class StoredLogRecord(
	val id: Long,
	val timestamp: OffsetDateTime,
	val level: String,
	val service: String,
	val message: String,
	val attributes: Map<String, Any?>,
)

data class LogQueryCriteria(
	val service: String?,
	val level: String?,
	val since: OffsetDateTime?,
	val until: OffsetDateTime?,
	val attributes: Map<String, String>,
	val q: String?,
	val limit: Int,
	val cursor: LogCursor?,
)

data class AggregateQueryCriteria(
	val filters: LogQueryCriteria,
	val bucket: AggregateBucketSize,
	val groupBy: AggregateGroupBy?,
)

data class AggregateBucketRecord(
	val start: OffsetDateTime,
	val group: String?,
	val count: Long,
)

enum class AggregateGroupBy(val sqlExpression: String) {
	SERVICE("service"),
	LEVEL("level"),
}
