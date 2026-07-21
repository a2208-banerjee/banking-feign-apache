package com.banking.payment.client;

import com.banking.payment.config.GlobalFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Feign client for account-service.
 *
 * URL is https (port 8443) — account-service requires mTLS.
 * The mTLS client certificate is transparently presented by Apache HttpClient 5
 * on every call — configured in ApacheHttpClientConfig.
 *
 * Common headers (Content-Type, X-Idempotency-Key etc) are injected
 * by GlobalFeignConfig.commonHeadersInterceptor().
 *
 * HTTP errors are mapped to exceptions by GlobalFeignConfig.bankingErrorDecoder():
 *   401 → UnauthorizedException     (mTLS cert rejected or expired)
 *   404 → ResourceNotFoundException
 *   409 → DuplicatePaymentException (idempotency key already used)
 *   503 → ServiceUnavailableException (triggers circuit breaker)
 */
@FeignClient(
    name          = "account-service",
    url           = "${services.account-service.url}",
    configuration = GlobalFeignConfig.class,
    fallback      = AccountServiceClientFallback.class
)
public interface AccountServiceClient {

    @GetMapping("/api/accounts/{id}/balance-check")
    Map<String, Object> checkBalance(@PathVariable String id,
                                     @RequestParam BigDecimal amount);

    @PostMapping("/api/accounts/{id}/debit")
    Map<String, Object> debit(@PathVariable String id,
                               @RequestBody Map<String, BigDecimal> body);

    @PostMapping("/api/accounts/{id}/credit")
    Map<String, Object> credit(@PathVariable String id,
                                @RequestBody Map<String, BigDecimal> body);
}
