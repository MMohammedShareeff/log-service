package com.example.config.com.example.log_service

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener

class KillPortListener : ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val port = event.environment.getProperty("server.port", "8080").toInt()
        val os = System.getProperty("os.name").lowercase()

        try {
            if (os.contains("win")) {
                val command = "for /f \"tokens=5\" %a in ('netstat -aon ^| findstr LISTENING ^| findstr :$port') do taskkill /f /pid %a"
                ProcessBuilder("cmd.exe", "/c", command).start().waitFor()
            } else {
                ProcessBuilder("bash", "-c", "fuser -k -9 $port/tcp").start().waitFor()
            }
        } catch (_: Exception) {
            // Socket was already free or command not permitted
        }
    }
}