package com.banking.payment.client;
import com.banking.payment.config.GlobalFeignConfig;
import com.banking.payment.model.CustomerDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="customer-service", url="${services.customer-service.url}",
             configuration=GlobalFeignConfig.class, fallback=CustomerServiceClientFallback.class)
public interface CustomerServiceClient {
    @GetMapping("/api/customers/account/{accountId}")
    CustomerDto getCustomerByAccountId(@PathVariable String accountId);
}
