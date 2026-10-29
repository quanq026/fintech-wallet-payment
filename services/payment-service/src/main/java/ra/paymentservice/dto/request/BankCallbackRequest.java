package ra.paymentservice.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import ra.common.validation.InputRules;

public record BankCallbackRequest(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("provider_reference") String providerReference,
        long amount,
        String status) {
    public BankCallbackRequest {
        InputRules.uuid(paymentId);
        InputRules.uuid(providerReference);
        InputRules.amount(amount);
        if (!"PAID".equals(status)) {
            throw new IllegalArgumentException("Invalid callback status");
        }
    }
}

