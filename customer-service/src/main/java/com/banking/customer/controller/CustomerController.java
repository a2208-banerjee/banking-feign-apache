package com.banking.customer.controller;
import com.banking.customer.model.Customer;
import com.banking.customer.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.cert.X509Certificate;
@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;
    @GetMapping("/account/{accountId}")
    public ResponseEntity<Customer> getByAccountId(@PathVariable String accountId, HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs != null && certs.length > 0) log.info("[mTLS] Client cert: {}", certs[0].getSubjectX500Principal().getName());
        return customerService.getByAccountId(accountId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
