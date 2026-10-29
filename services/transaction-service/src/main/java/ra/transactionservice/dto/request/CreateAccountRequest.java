package ra.transactionservice.dto.request;

import ra.common.validation.InputRules;

import java.util.Set;

public record CreateAccountRequest(String accountId, String ownerId, String kind) {
    public CreateAccountRequest {
        InputRules.identifier(accountId);
        InputRules.client(ownerId);
        if (kind == null || !Set.of("USER", "MERCHANT", "CLEARING").contains(kind)) {
            throw new IllegalArgumentException("Invalid kind");
        }
    }
}
