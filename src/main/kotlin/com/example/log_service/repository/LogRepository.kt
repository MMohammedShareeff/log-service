package com.example.log_service.repository

import com.example.log_service.service.AggregateBucketSize
import com.example.log_service.service.LogCursor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@Repository
class LogRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val namedParameterJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

    fun insertAll(logs: List<LogRecord>) {
        if (logs.isEmpty()) return

        jdbcTemplate.execute { connection: Connection ->
            connection.autoCommit = false
            try {
                val pgConnection = connection.unwrap(org.postgresql.core.BaseConnection::class.java)
                val copyManager = org.postgresql.copy.CopyManager(pgConnection)
                val csv = buildCsvPayload(logs)
                copyManager.copyIn(COPY_SQL, java.io.StringReader(csv))

                val rollupCounts = logs
                    .groupingBy { Triple(truncateToMinute(it.timestamp), it.service, it.level) }
                    .eachCount()				
				 val sortedRollups = rollupCounts.entries.sortedWith(
                	compareBy({ it.key.first }, { it.key.second }, { it.key.third })
            	 )

                connection.prepareStatement(ROLLUP_UPSERT_SQL).use { statement ->
                    for ((key, count) in sortedRollups) {
                        val (bucket, service, level) = key
                        statement.setObject(1, bucket)
                        statement.setString(2, service)
                        statement.setString(3, level)
                        statement.setLong(4, count.toLong())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun buildCsvPayload(logs: List<LogRecord>): String {
        val builder = StringBuilder()
        for (log in logs) {
            builder.append(csvField(log.timestamp.toString())).append(',')
            builder.append(csvField(log.level)).append(',')
            builder.append(csvField(log.service)).append(',')
            builder.append(csvField(log.message)).append(',')
            builder.append(csvField(objectMapper.writeValueAsString(log.attributes))).append('\n')
        }
        return builder.toString()
    }

    private fun csvField(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun truncateToMinute(ts: OffsetDateTime): OffsetDateTime {
        return ts.withSecond(0).withNano(0)
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
        val filters = criteria.filters
        val canUseRollups = filters.q == null && filters.attributes.isEmpty()

        return if (canUseRollups) {
            aggregateFromRollups(criteria)
        } else {
            aggregateFromLogs(criteria)
        }
    }

    private fun aggregateFromLogs(criteria: AggregateQueryCriteria): List<AggregateBucketRecord> {
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

    private fun aggregateFromRollups(criteria: AggregateQueryCriteria): List<AggregateBucketRecord> {
        val params = MapSqlParameterSource()
            .addValue("since", criteria.filters.since)
            .addValue("until", criteria.filters.until)
            .addValue("bucketInterval", criteria.bucket.interval)
            .addValue("origin", criteria.filters.since)

        val predicates = mutableListOf<String>()
        predicates += "bucket_start >= :since"
        predicates += "bucket_start < :until"

        criteria.filters.service?.let {
            predicates += "service = :service"
            params.addValue("service", it)
        }
        criteria.filters.level?.let {
            predicates += "level = :level"
            params.addValue("level", it)
        }

        val where = predicates.joinToString(prefix = " WHERE ", separator = " AND ")

        val groupSelect = when (criteria.groupBy) {
            AggregateGroupBy.SERVICE -> "service"
            AggregateGroupBy.LEVEL -> "level"
            null -> "NULL::text"
        }

        val outerGroupByClause = if (criteria.groupBy == null) {
            "bin_start"
        } else {
            "bin_start, group_name"
        }

        val sql = """
            SELECT
                bin_start AS bucket_start,
                group_name,
                SUM(row_count) AS count
            FROM (
                SELECT
                    date_bin(CAST(:bucketInterval AS interval), bucket_start, :origin) AS bin_start,
                    $groupSelect AS group_name,
                    count AS row_count
                FROM log_rollup
                $where
            ) AS binned
            GROUP BY $outerGroupByClause
            ORDER BY bin_start ASC, group_name ASC NULLS FIRST
        """.trimIndent()

        return namedParameterJdbcTemplate.query(sql, params, AGGREGATE_ROW_MAPPER)
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
            predicates += "timestamp < :until"
            params.addValue("until", it)
        }

        criteria.q?.let {
            predicates += "message ILIKE ('%' || :q || '%')"
            params.addValue("q", it)
        }

        criteria.attributes.entries.forEachIndexed { index, (key, value) ->
            val candidates = attributeCandidateValues(value)
            val orPredicates = candidates.mapIndexed { candidateIndex, candidateValue ->
                val paramName = "attr${index}_$candidateIndex"
                params.addValue(paramName, objectMapper.writeValueAsString(mapOf(key to candidateValue)))
                "attributes @> CAST(:$paramName AS jsonb)"
            }
            predicates += orPredicates.joinToString(prefix = "(", separator = " OR ", postfix = ")")
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

    private fun attributeCandidateValues(value: String): List<Any> {
        val candidates = mutableListOf<Any>(value)
        value.toBigDecimalOrNull()?.let { candidates += it }
        when (value.lowercase()) {
            "true" -> candidates += true
            "false" -> candidates += false
        }
        return candidates
    }

    private companion object {
        private val objectMapper = ObjectMapper()

        private const val COPY_SQL = """
            COPY logs (timestamp, level, service, message, attributes)
            FROM STDIN WITH (FORMAT csv)
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

private const val ROLLUP_UPSERT_SQL = """
    INSERT INTO log_rollup (bucket_start, service, level, count)
    VALUES (?, ?, ?, ?)
    ON CONFLICT (bucket_start, service, level)
    DO UPDATE SET count = log_rollup.count + EXCLUDED.count
"""

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