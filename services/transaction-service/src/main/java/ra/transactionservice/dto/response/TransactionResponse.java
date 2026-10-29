package ra.transactionservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"id", "client_id", "source_account", "destination_account", "amount",
        "kind", "status", "journal_id", "error_code"})
public record TransactionResponse(
        String id,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("source_account") String sourceAccount,
        @JsonProperty("destination_account") String destinationAccount,
        long amount,
        String kind,
        String status,
        @JsonProperty("journal_id") String journalId,
        @JsonProperty("error_code") String errorCode) {
}
