package com.example.log_service.config

import com.example.log_service.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@SpringBootTest(properties = ["app.retention.enabled=false"])
@Import(TestcontainersConfiguration::class)
class RetentionSchedulerTest {

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun createsUpcomingDailyPartitions() {
		val today = LocalDate.now(ZoneOffset.UTC)
		val scheduler = scheduler(today, retentionDays = 30, partitionDaysAhead = 3)

		scheduler.maintainPartitions()

		(0L..3L).forEach { offset ->
			assertTrue(partitionExists(partitionName(today.plusDays(offset))))
		}
	}

	@Test
	fun dropsExpiredDailyPartitions() {
		val today = LocalDate.now(ZoneOffset.UTC)
		val expiredDate = today.minusDays(10)
		val expiredPartition = partitionName(expiredDate)
		createPartition(expiredDate)
		assertTrue(partitionExists(expiredPartition))

		val scheduler = scheduler(today, retentionDays = 5, partitionDaysAhead = 1)

		scheduler.maintainPartitions()

		assertFalse(partitionExists(expiredPartition))
	}

	private fun scheduler(
		today: LocalDate,
		retentionDays: Long,
		partitionDaysAhead: Long,
	): RetentionScheduler {
		return RetentionScheduler(
			jdbcTemplate = jdbcTemplate,
			clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
			retentionDays = retentionDays,
			partitionDaysAhead = partitionDaysAhead,
			lockTimeout = "2s",
		)
	}

	private fun createPartition(date: LocalDate) {
		jdbcTemplate.execute(
			"""
			CREATE TABLE IF NOT EXISTS ${partitionName(date)}
			PARTITION OF logs
			FOR VALUES FROM ('$date') TO ('${date.plusDays(1)}')
			""".trimIndent(),
		)
	}

	private fun partitionExists(partitionName: String): Boolean {
		return jdbcTemplate.queryForObject("SELECT to_regclass('public.$partitionName') IS NOT NULL", Boolean::class.java)
			?: false
	}

	private fun partitionName(date: LocalDate): String {
		return "logs_${date.toString().replace('-', '_')}"
	}
}
