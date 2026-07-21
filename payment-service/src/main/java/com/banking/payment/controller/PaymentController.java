package com.banking.payment.controller;
import com.banking.payment.model.*;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;
    @PostMapping
    public ResponseEntity<Payment> processPayment(@Valid @RequestBody PaymentRequest request) {
        Payment p = paymentService.processPayment(request);
        return ResponseEntity.status("COMPLETED".equals(p.getStatus()) ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(p);
    }
    @GetMapping
    public List<Payment> getAllPayments() { return paymentService.getAllPayments(); }
    @GetMapping("/{id}")
    public ResponseEntity<Payment> getById(@PathVariable String id) {
        return paymentService.getPaymentById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
