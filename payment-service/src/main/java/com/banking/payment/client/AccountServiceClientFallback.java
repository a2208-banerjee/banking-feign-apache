package com.banking.payment.client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;
@Slf4j
@Component
public class AccountServiceClientFallback implements AccountServiceClient {
    @Override public Map<String, Object> checkBalance(String id, BigDecimal amount) {
        log.warn("Account service fallback — balance check for {}", id);
        return Map.of("sufficient", false, "fallback", true);
    }
    @Override public Map<String, Object> debit(String id, Map<String, BigDecimal> body) {
        throw new RuntimeException("Account service unavailable — debit fallback");
    }
    @Override public Map<String, Object> credit(String id, Map<String, BigDecimal> body) {
        throw new RuntimeException("Account service unavailable — credit fallback");
    }
}
