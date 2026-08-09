package com.example.log_service

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<LogServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
