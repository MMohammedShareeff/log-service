package com.example.log_service.service

import com.example.log_service.repository.LogRecord
import com.example.log_service.repository.LogRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


@Component
class IngestCoalescer(
    private val logRepository: LogRepository,
    @Value("\${app.ingest.coalesce-window-ms:5}")
    private val windowMs: Long,
    @Value("\${app.ingest.coalesce-max-rows:2000}") private val maxRows: Int,
) {
    private val lock = ReentrantLock()
    private var pendingLogs = mutableListOf<LogRecord>()
    private var pendingFutures = mutableListOf<CompletableFuture<Unit>>()
    private var scheduledFlush: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ingest-coalescer").apply { isDaemon = true }
    }

    fun submit(logs: List<LogRecord>): CompletableFuture<Unit> {
        if (logs.isEmpty()) {
            return CompletableFuture.completedFuture(Unit)
        }

        val future = CompletableFuture<Unit>()
        var shouldFlushNow: Boolean = false

        lock.withLock {
            pendingLogs.addAll(logs)
            pendingFutures.add(future)

            shouldFlushNow = pendingLogs.size >= maxRows

            if (!shouldFlushNow && scheduledFlush == null) {
                scheduledFlush = scheduler.schedule({ flush() }, windowMs, TimeUnit.MILLISECONDS)
            }
        }

        if (shouldFlushNow) {
            flush()
        }

        return future
    }

    fun submitAndAwait(logs: List<LogRecord>) {
        try {
            submit(logs).get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun flush() {
        val batch = lock.withLock {
            scheduledFlush?.cancel(false)
            scheduledFlush = null

            if (pendingLogs.isEmpty()) {
                return
            }

            val logsSnapshot = pendingLogs
            val futuresSnapshot = pendingFutures
            pendingLogs = mutableListOf()
            pendingFutures = mutableListOf()
            logsSnapshot to futuresSnapshot
        }

        val (logsToWrite, futuresToComplete) = batch

        try {
            logRepository.insertAll(logsToWrite)
            futuresToComplete.forEach { it.complete(Unit) }
        } catch (e: Exception) {
            futuresToComplete.forEach { it.completeExceptionally(e) }
        }
    }
}