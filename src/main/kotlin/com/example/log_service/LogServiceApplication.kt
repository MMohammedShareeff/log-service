package com.example.log_service

import com.example.config.com.example.log_service.KillPortListener
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class LogServiceApplication

fun main(args: Array<String>) {
	val app = SpringApplication(LogServiceApplication::class.java)
    app.addListeners(KillPortListener())
    app.run(*args)
}
