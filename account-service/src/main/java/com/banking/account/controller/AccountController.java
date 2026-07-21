package com.banking.account.controller;

import com.banking.account.model.Account;
import com.banking.account.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /**
     * Logs the mTLS client certificate subject on every request.
     * This proves the mutual authentication is working —
     * payment-service's client cert is verified and its DN is visible here.
     */
    private void logClientCert(HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[])
                request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs != null && certs.length > 0) {
            log.info("[mTLS] Client cert verified: {}", certs[0].getSubjectX500Principal().getName());
        } else {
            log.warn("[mTLS] No client certificate presented — check mTLS config");
        }
    }

    @GetMapping
    public List<Account> getAll(HttpServletRequest request) {
        logClientCert(request);
        return accountService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getById(@PathVariable String id, HttpServletRequest request) {
        logClientCert(request);
        return accountService.getById(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/balance-check")
    public Map<String, Object> checkBalance(@PathVariable String id,
                                             @RequestParam BigDecimal amount,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String key,
                                             HttpServletRequest request) {
        logClientCert(request);
        log.info("Balance check — account: {}, amount: {}, idempotencyKey: {}", id, amount, key);
        return Map.of("accountId", id, "sufficient", accountService.hasSufficientFunds(id, amount));
    }
}
