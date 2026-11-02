package com.vik.ums.outbox

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts the pinned event envelope to the consumer endpoint. The envelope already carries the
 * event id, so an at-least-once replay is identifiable. Only the class of failure is retained;
 * the response body is discarded and never logged.
 */
class HttpOutboxTransport(
    private val consumerUri: URI,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
    private val bearerToken: String? = null,
) : OutboxTransport {
    init { require(consumerUri.scheme == "http" || consumerUri.scheme == "https") { "Consumer URI must be HTTP(S)" } }

    override fun publish(event: OutboxEvent) {
        val builder = HttpRequest.newBuilder(consumerUri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(event.payload)).build()
        val status = try {
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (unavailable: IOException) {
            throw OutboxTransportException(OutboxFailureClass.TRANSPORT_UNAVAILABLE, unavailable)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OutboxTransportException(OutboxFailureClass.TRANSPORT_UNAVAILABLE, interrupted)
        }
        failureClassFor(status)?.let { throw OutboxTransportException(it) }
    }

    companion object {
        /** 2xx accepted; a timeout, throttling, or server fault is retryable; anything else is refused. */
        internal fun failureClassFor(status: Int): OutboxFailureClass? = when {
            status in 200..299 -> null
            status == 408 || status == 429 || status >= 500 -> OutboxFailureClass.TRANSPORT_UNAVAILABLE
            else -> OutboxFailureClass.TRANSPORT_REJECTED
        }
    }
}
