package ra.transactionservice.dto.request;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ra.common.validation.InputRules;

import java.util.Set;

@JsonPropertyOrder({"sourceAccount", "destinationAccount", "amount", "kind"})
public record TransactionRequest(String sourceAccount, String destinationAccount, long amount, String kind) {
    public TransactionRequest {
        InputRules.identifier(sourceAccount);
        InputRules.identifier(destinationAccount);
        InputRules.amount(amount);
        if (sourceAccount.equals(destinationAccount)
                || kind == null
                || !Set.of("TRANSFER", "DEPOSIT", "WITHDRAWAL", "PAYMENT").contains(kind)) {
            throw new IllegalArgumentException("Invalid transfer");
        }
    }
}
