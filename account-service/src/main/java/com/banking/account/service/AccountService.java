package com.banking.account.service;
import com.banking.account.model.Account;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class AccountService {
    private final Map<String, Account> store = new ConcurrentHashMap<>();
    public AccountService() {
        store.put("ACC001", Account.builder().accountId("ACC001").accountNumber("12345678").sortCode("20-00-00")
            .accountHolder("Priya Sharma").accountType("CURRENT").balance(new BigDecimal("5420.75")).currency("GBP").status("ACTIVE").build());
        store.put("ACC002", Account.builder().accountId("ACC002").accountNumber("87654321").sortCode("30-00-00")
            .accountHolder("Raj Patel").accountType("SAVINGS").balance(new BigDecimal("12850.00")).currency("GBP").status("ACTIVE").build());
    }
    public List<Account> getAll() { return new ArrayList<>(store.values()); }
    public Optional<Account> getById(String id) { return Optional.ofNullable(store.get(id)); }
    public boolean hasSufficientFunds(String id, BigDecimal amount) {
        return store.containsKey(id) && store.get(id).getBalance().compareTo(amount) >= 0;
    }
}
