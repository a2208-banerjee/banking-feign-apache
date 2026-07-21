package com.banking.payment.client;
import com.banking.payment.model.CustomerDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
@Slf4j
@Component
public class CustomerServiceClientFallback implements CustomerServiceClient {
    @Override
    public CustomerDto getCustomerByAccountId(String accountId) {
        log.warn("Customer service fallback for account {}", accountId);
        return CustomerDto.builder().customerId("UNKNOWN").fullName("Unknown Customer").status("UNKNOWN").build();
    }
}
