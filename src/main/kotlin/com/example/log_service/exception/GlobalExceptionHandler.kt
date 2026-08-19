package com.example.log_service.exception

import com.example.log_service.dto.ErrorResponse
import com.example.log_service.service.BadRequestException
import com.example.log_service.service.OverloadedException
import com.example.log_service.service.PayloadTooLargeException
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedJson(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = "malformed request body"))
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = ex.message ?: "invalid request"))
    }

    @ExceptionHandler(PayloadTooLargeException::class)
    fun handlePayloadTooLarge(ex: PayloadTooLargeException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse(error = ex.message ?: "request too large"))
    }

    @ExceptionHandler(OverloadedException::class)
    fun handleOverloaded(ex: OverloadedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(RETRY_AFTER, ex.retryAfterSeconds.toString())
            .body(ErrorResponse(error = ex.message ?: "server temporarily overloaded, retry shortly"))
    }
}
