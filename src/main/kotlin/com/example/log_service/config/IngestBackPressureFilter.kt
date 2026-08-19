package com.example.log_service.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

@Component
class IngestBackpressureFilter(
    private val admissionController: IngestAdmissionController,
) : Filter {

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        if (!isIngestRequest(httpRequest)) {
            chain.doFilter(request, response)
            return
        }

        val contentLength = httpRequest.contentLengthLong
        val decision = admissionController.tryAdmitBytes(contentLength)

        when (decision) {
            is AdmissionDecision.Admitted -> {
                try {
                    chain.doFilter(request, response)
                } finally {
                    admissionController.releaseBytes(contentLength)
                }
            }

            is AdmissionDecision.RejectedTooLarge -> {
                writeError(httpResponse, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, decision.reason)
            }

            is AdmissionDecision.RejectedOverloaded -> {
                httpResponse.setHeader("Retry-After", admissionController.retryAfterSeconds.toString())
                writeError(
                    httpResponse,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "server temporarily overloaded, retry shortly",
                )
            }
        }
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write("""{"error":"$message"}""")
    }

    private fun isIngestRequest(request: HttpServletRequest): Boolean {
        return request.method == "POST" && request.requestURI == "/logs"
    }
}