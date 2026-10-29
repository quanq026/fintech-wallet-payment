package ra.paymentservice.dto.request;

import ra.common.validation.InputRules;

public record PaymentRequest(String accountId, String destination, long amount) {
    public PaymentRequest {
        InputRules.identifier(accountId);
        InputRules.amount(amount);
        if (destination != null) {
            InputRules.identifier(destination);
        }
    }
}

