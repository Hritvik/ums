package com.vik.ums.foundation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse

/** Writes the contract's non-enumerating error envelope. Never echoes input or authority details. */
object SafeErrors {
    const val CORRELATION_HEADER = "X-Correlation-Id"
    private val CORRELATION = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun correlation(response: HttpServletResponse, request: jakarta.servlet.http.HttpServletRequest): String {
        val supplied = request.getHeader(CORRELATION_HEADER)?.takeIf { it.matches(CORRELATION) }
        val value = supplied ?: java.util.UUID.randomUUID().toString()
        response.setHeader(CORRELATION_HEADER, value)
        return value
    }

    fun body(code: String, message: String, correlationId: String): Map<String, Any> = mapOf(
        "error" to mapOf("code" to code, "message" to message),
        "correlationId" to correlationId,
    )

    fun write(mapper: ObjectMapper, response: HttpServletResponse, status: Int, code: String,
              message: String, correlationId: String) {
        response.status = status
        response.contentType = "application/json"
        mapper.writeValue(response.outputStream, body(code, message, correlationId))
    }
}
