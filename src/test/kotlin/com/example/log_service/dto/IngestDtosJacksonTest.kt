package com.example.log_service.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class IngestDtosJacksonTest {

	private val objectMapper = ObjectMapper()

	@Test
	fun attributesUseJacksonThreeRuntimeShapes() {
		val request = objectMapper.readValue(
			"""
			{
			  "logs": [
			    {
			      "timestamp": "2026-08-11T12:00:00Z",
			      "level": "info",
			      "service": "api",
			      "message": "hello",
			      "attributes": {
			        "small": 1,
			        "large": 2147483648,
			        "decimal": 1.5,
			        "flag": true,
			        "nested": {"x": "y"},
			        "array": ["x"]
			      }
			    }
			  ]
			}
			""".trimIndent(),
			IngestRequest::class.java,
		)

		val attributes = request.logs.single().attributes.orEmpty()

		assertInstanceOf(Int::class.javaObjectType, attributes["small"])
		assertInstanceOf(Long::class.javaObjectType, attributes["large"])
		assertInstanceOf(Double::class.javaObjectType, attributes["decimal"])
		assertInstanceOf(Boolean::class.javaObjectType, attributes["flag"])
		assertEquals("java.util.LinkedHashMap", attributes["nested"]!!::class.java.name)
		assertEquals("java.util.ArrayList", attributes["array"]!!::class.java.name)
	}
}
