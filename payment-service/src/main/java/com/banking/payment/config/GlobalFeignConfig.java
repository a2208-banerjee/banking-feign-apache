package com.banking.payment.config;

import com.banking.payment.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Global Feign configuration — applies to ALL @FeignClient interfaces.
 *
 * ── RequestInterceptor ─────────────────────────────────────────────────────
 * Injects common headers into every Feign request automatically.
 * This is the Feign equivalent of WebClient's ExchangeFilterFunction.
 *
 * ── ErrorDecoder ───────────────────────────────────────────────────────────
 * Converts HTTP error status codes into typed Java exceptions.
 * Called by Feign when the response status is 4xx or 5xx.
 * Without this, Feign throws a generic FeignException for all errors.
 *
 * ── Retryer ────────────────────────────────────────────────────────────────
 * Retries on 503 — but NOT on 409 (duplicate) or 4xx (client errors).
 * Retry is safe here because we use idempotency keys.
 */
@Slf4j
@Configuration
public class GlobalFeignConfig {

    /**
     * Injects common headers into EVERY Feign call.
     * The Feign equivalent of WebClient's ExchangeFilterFunction.ofRequestProcessor().
     */
    @Bean
    public RequestInterceptor commonHeadersInterceptor() {
        return requestTemplate -> {
            requestTemplate.header("Content-Type",    "application/json");
            requestTemplate.header("Accept",           "application/json");
            requestTemplate.header("X-Request-Source", "payment-service");
            requestTemplate.header("X-Idempotency-Key", UUID.randomUUID().toString());

            log.debug("Feign → {} {}", requestTemplate.method(), requestTemplate.url());
        };
    }

    /**
     * Maps HTTP status codes to domain exceptions.
     *
     * This is the Feign equivalent of WebClient's .onStatus() handlers.
     * The key difference: with Feign you define all mappings in one place;
     * with WebClient you define them per-call at the call site.
     */
    @Bean
    public ErrorDecoder bankingErrorDecoder() {
        return new BankingErrorDecoder();
    }

    /**
     * Retry on connection failures only.
     * NOT retrying on 4xx — those are client errors (wrong request).
     * NOT retrying on 409 — duplicate payment must not retry.
     */
    @Bean
    public Retryer feignRetryer() {
        // 100ms initial interval, 1s max interval, 2 attempts
        return new Retryer.Default(100, 1000, 2);
    }

    /**
     * Log level for Feign — shows full request/response in dev.
     * Switch to BASIC in production.
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    // ── ErrorDecoder Implementation ────────────────────────────────────────────

    @Slf4j
    static class BankingErrorDecoder implements ErrorDecoder {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ErrorDecoder defaultDecoder = new Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            String body = readBody(response);
            log.warn("Feign error — method: {}, status: {}, body: {}", methodKey, response.status(), body);

            return switch (response.status()) {
                case 401 -> new UnauthorizedException(
                        "Service rejected our mTLS client certificate or bearer token: " + body);

                case 404 -> new ResourceNotFoundException(
                        extractField(body, "accountId", "Resource") + " not found");

                case 409 -> new DuplicatePaymentException(
                        extractField(body, "idempotencyKey", "unknown"));

                case 422 -> new InsufficientFundsException(
                        extractField(body, "message", "Insufficient funds"));

                case 503 -> {
                    log.error("Downstream service unavailable — circuit breaker may trip");
                    yield new RetryableException(503, "Service unavailable: " + methodKey,
                            null, null, response.request());
                }

                default -> {
                    if (response.status() >= 500) {
                        yield new ServiceUnavailableException(
                                "Server error " + response.status() + ": " + body);
                    }
                    yield defaultDecoder.decode(methodKey, response);
                }
            };
        }

        private String readBody(Response response) {
            try {
                if (response.body() != null) {
                    return new String(response.body().asInputStream().readAllBytes());
                }
            } catch (IOException e) {
                log.warn("Could not read error response body: {}", e.getMessage());
            }
            return "";
        }

        @SuppressWarnings("unchecked")
        private String extractField(String json, String field, String fallback) {
            try {
                Map<String, Object> map = objectMapper.readValue(json, Map.class);
                Object val = map.get(field);
                return val != null ? val.toString() : fallback;
            } catch (Exception e) {
                return fallback;
            }
        }
    }
}
