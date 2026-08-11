package com.example.log_service.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ConfigLayer {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
