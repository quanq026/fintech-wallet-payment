package ra.transactionservice.dto.response;

public record AccountResponse(String accountId, String ownerId, String kind, long balance, String currency) {
}
