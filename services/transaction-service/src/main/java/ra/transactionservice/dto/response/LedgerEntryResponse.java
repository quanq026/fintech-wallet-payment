package ra.transactionservice.dto.response;

public record LedgerEntryResponse(long entryId, String journalId, String accountId, long amount, String createdAt) {
}
