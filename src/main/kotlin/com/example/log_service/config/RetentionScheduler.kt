package com.example.log_service.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(prefix = "app.retention", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RetentionScheduler(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    @Value("\${app.retention.retention-days:30}")
    private val retentionDays: Long,
    @Value("\${app.retention.partition-days-ahead:7}")
    private val partitionDaysAhead: Long,
    @Value("\${app.retention.lock-timeout:2s}")
    private val lockTimeout: String,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        maintainPartitions()
    }

    @Scheduled(cron = "\${app.retention.cron:0 0 0 * * *}", zone = "\${app.retention.zone:UTC}")
    fun maintainPartitions() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        createUpcomingPartitions(today)
        dropExpiredPartitions(today)

        pruneExpiredRollups(today)
    }

    private fun pruneExpiredRollups(today: LocalDate) {
        val cutoff = today.minusDays(retentionDays.coerceAtLeast(1)).atStartOfDay().atOffset(ZoneOffset.UTC)

        jdbcTemplate.update(
            "DELETE FROM log_rollup WHERE bucket_start < ?",
            cutoff,
        )
    }

    private fun createUpcomingPartitions(today: LocalDate) {
        val daysAhead = partitionDaysAhead.coerceAtLeast(0)
        for (offset in 0..daysAhead) {
            createPartition(today.plusDays(offset))
        }
    }

    private fun createPartition(date: LocalDate) {
        val partitionName = partitionName(date)
        jdbcTemplate.execute(
            """
			CREATE TABLE IF NOT EXISTS $partitionName
			PARTITION OF logs
			FOR VALUES FROM ('$date') TO ('${date.plusDays(1)}')
			""".trimIndent(),
        )
    }

    private fun dropExpiredPartitions(today: LocalDate) {
        val cutoff = today.minusDays(retentionDays.coerceAtLeast(1))
        findDailyPartitionsBefore(cutoff).forEach { partition ->
            dropPartition(partition)
        }
    }

    private fun findDailyPartitionsBefore(cutoff: LocalDate): List<String> {
        return jdbcTemplate.queryForList(
            """
			SELECT child.relname
			FROM pg_inherits
			JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
			JOIN pg_class child ON child.oid = pg_inherits.inhrelid
			JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
			WHERE parent.relname = 'logs'
			  AND namespace.nspname = 'public'
			  AND child.relname ~ '^logs_[0-9]{4}_[0-9]{2}_[0-9]{2}${'$'}'
			ORDER BY child.relname
			""".trimIndent(),
            String::class.java,
        ).filterNotNull().filter { name ->
            val date = partitionDate(name)
            date != null && date.isBefore(cutoff)
        }
    }

    private fun dropPartition(partitionName: String) {
        if (!PARTITION_NAME_REGEX.matches(partitionName)) {
            logger.warn("Skipping unexpected partition name {}", partitionName)
            return
        }

        val boundedLockTimeout = validatedLockTimeout()
        jdbcTemplate.execute("SET lock_timeout = '$boundedLockTimeout'")
        try {
            jdbcTemplate.execute("ALTER TABLE logs DETACH PARTITION $partitionName")
            jdbcTemplate.execute("DROP TABLE IF EXISTS $partitionName")
        } finally {
            jdbcTemplate.execute("RESET lock_timeout")
        }
    }

    private fun partitionName(date: LocalDate): String {
        return "logs_${date.format(PARTITION_DATE_FORMATTER).replace('-', '_')}"
    }

    private fun partitionDate(partitionName: String): LocalDate? {
        return runCatching {
            LocalDate.parse(partitionName.removePrefix("logs_").replace('_', '-'))
        }.getOrNull()
    }

    private fun validatedLockTimeout(): String {
        require(LOCK_TIMEOUT_REGEX.matches(lockTimeout)) {
            "app.retention.lock-timeout must be a PostgreSQL duration like 500ms, 2s, or 1min"
        }
        return lockTimeout
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RetentionScheduler::class.java)
        private val PARTITION_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val PARTITION_NAME_REGEX = Regex("logs_[0-9]{4}_[0-9]{2}_[0-9]{2}")
        private val LOCK_TIMEOUT_REGEX = Regex("[0-9]+(ms|s|min)")
    }
}
