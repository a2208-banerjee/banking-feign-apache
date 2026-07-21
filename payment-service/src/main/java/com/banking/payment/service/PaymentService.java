package com.banking.payment.service;

import com.banking.payment.client.AccountServiceClient;
import com.banking.payment.client.CustomerServiceClient;
import com.banking.payment.exception.*;
import com.banking.payment.model.Payment;
import com.banking.payment.model.PaymentRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment orchestration using Feign clients over Apache HttpClient 5.
 *
 * Calls are SYNCHRONOUS/BLOCKING — each line waits for the previous to finish.
 * For parallel calls you would need @Async + CompletableFuture.
 * Compare with the WebClient version (banking-webclient-netty) which does
 * this natively with Mono.zip().
 *
 * @CircuitBreaker — provided by Resilience4j.
 * When account-service returns 503 repeatedly, the circuit opens and
 * fallbackPayment() is called immediately without hitting the service.
 * Config in application.yml under resilience4j.circuitbreaker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountServiceClient  accountServiceClient;
    private final CustomerServiceClient customerServiceClient;

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    @CircuitBreaker(name = "payment-processing", fallbackMethod = "circuitBreakerFallback")
    public Payment processPayment(PaymentRequest request) {
        String paymentId      = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String idempotencyKey = UUID.randomUUID().toString();

        log.info("Processing payment {} via Feign + Apache HttpClient 5 (mTLS)", paymentId);

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .idempotencyKey(idempotencyKey)
                .sourceAccountId(request.getSourceAccountId())
                .destinationAccountId(request.getDestinationAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .reference(request.getReference())
                .status("PROCESSING")
                .httpClientUsed("Apache HttpClient 5 + OpenFeign (mTLS)")
                .createdAt(LocalDateTime.now())
                .build();

        payments.put(paymentId, payment);

        try {
            // ── Step 1: Get customer (SYNCHRONOUS Feign call over mTLS) ──────
            // Apache HC5 presents client.p12 certificate transparently
            var customer = customerServiceClient
                    .getCustomerByAccountId(request.getSourceAccountId());

            payment.setCustomerName(customer.getFullName());
            payment.setCustomerNumber(customer.getCustomerNumber());
            log.info("Customer fetched via mTLS: {}", customer.getFullName());

            // ── Step 2: Balance check (SYNCHRONOUS — waits for previous) ─────
            // Note: this is sequential. In WebClient version, steps 1 and 2
            // run in PARALLEL with Mono.zip() — this is the key trade-off.
            Map<String, Object> balance = accountServiceClient
                    .checkBalance(request.getSourceAccountId(), request.getAmount());

            Boolean sufficient = (Boolean) balance.getOrDefault("sufficient", false);
            if (!Boolean.TRUE.equals(sufficient)) {
                throw new InsufficientFundsException(request.getSourceAccountId());
            }

            // ── Step 3: Debit source (SYNCHRONOUS) ───────────────────────────
            accountServiceClient.debit(
                    request.getSourceAccountId(),
                    Map.of("amount", request.getAmount())
            );

            // ── Step 4: Credit destination ───────────────────────────────────
            accountServiceClient.credit(
                    request.getDestinationAccountId(),
                    Map.of("amount", request.getAmount())
            );

            payment.setStatus("COMPLETED");
            payment.setProcessedAt(LocalDateTime.now());
            log.info("Payment {} completed", paymentId);

        } catch (InsufficientFundsException | DuplicatePaymentException e) {
            failPayment(payment, e.getMessage());
        } catch (UnauthorizedException e) {
            log.error("mTLS auth failure — check client.p12 is valid: {}", e.getMessage());
            failPayment(payment, "mTLS auth failed: " + e.getMessage());
        } catch (ServiceUnavailableException e) {
            log.error("Downstream unavailable: {}", e.getMessage());
            failPayment(payment, e.getMessage());
        }

        return payment;
    }

    /** Called by Resilience4j when circuit is OPEN (service is repeatedly failing) */
    public Payment circuitBreakerFallback(PaymentRequest request, Exception ex) {
        log.warn("Circuit breaker open — payment rejected: {}", ex.getMessage());
        return Payment.builder()
                .paymentId("CB-REJECTED")
                .status("REJECTED")
                .failureReason("Circuit breaker open — downstream service unavailable")
                .httpClientUsed("Apache HttpClient 5 + OpenFeign (mTLS)")
                .createdAt(LocalDateTime.now())
                .build();
    }

    public Optional<Payment> getPaymentById(String id) { return Optional.ofNullable(payments.get(id)); }
    public List<Payment> getAllPayments() { return new ArrayList<>(payments.values()); }

    private void failPayment(Payment payment, String reason) {
        payment.setStatus("FAILED");
        payment.setFailureReason(reason);
        payment.setProcessedAt(LocalDateTime.now());
    }
}
