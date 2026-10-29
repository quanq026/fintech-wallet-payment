package ra.transactionservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import ra.transactionservice.dto.request.TransactionRequest;
import ra.transactionservice.dto.response.AccountResponse;
import ra.transactionservice.dto.response.LedgerEntryResponse;
import ra.transactionservice.dto.response.TransactionResponse;
import ra.transactionservice.exception.GrpcExceptionHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DtoContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void renamedRequestPreservesDurableFingerprintBytes() throws Exception {
        var original = new LegacyCommand("alice-wallet", "bob-wallet", 9_000_000_000_000_000L, "TRANSFER");
        var request = new TransactionRequest("alice-wallet", "bob-wallet", 9_000_000_000_000_000L, "TRANSFER");
        assertArrayEquals(json.writeValueAsBytes(original), json.writeValueAsBytes(request));
        assertEquals("{\"sourceAccount\":\"alice-wallet\",\"destinationAccount\":\"bob-wallet\","
                + "\"amount\":9000000000000000,\"kind\":\"TRANSFER\"}", json.writeValueAsString(request));
    }

    @Test
    void transactionResponseRetainsSnakeCaseFieldsAndNulls() throws Exception {
        var response = new TransactionResponse("tx", "alice", "alice-wallet", "bob-wallet",
                10L, "TRANSFER", "NEW", null, null);
        assertEquals("{\"id\":\"tx\",\"client_id\":\"alice\",\"source_account\":\"alice-wallet\","
                        + "\"destination_account\":\"bob-wallet\",\"amount\":10,\"kind\":\"TRANSFER\","
                        + "\"status\":\"NEW\",\"journal_id\":null,\"error_code\":null}",
                json.writeValueAsString(response));
    }

    @Test
    void accountAndEntryResponsesRetainExistingFields() throws Exception {
        var account = new AccountResponse("alice-wallet", "alice", "USER", 100L, "VND");
        assertEquals(json.valueToTree(Map.of("accountId", "alice-wallet", "ownerId", "alice",
                "kind", "USER", "balance", 100L, "currency", "VND")), json.valueToTree(account));
        var entry = new LedgerEntryResponse(1L, "journal", "alice-wallet", -10L, "2026-10-06T00:00:00");
        assertEquals(json.valueToTree(Map.of("entryId", 1L, "journalId", "journal",
                        "accountId", "alice-wallet", "amount", -10L, "createdAt", "2026-10-06T00:00:00")),
                json.valueToTree(entry));
    }

    @Test
    void extractedGrpcHandlerPreservesStatusAndCode() {
        var handler = new GrpcExceptionHandler();
        for (var expected : Map.of(Status.Code.NOT_FOUND, 404, Status.Code.ALREADY_EXISTS, 409,
                Status.Code.INVALID_ARGUMENT, 400, Status.Code.FAILED_PRECONDITION, 422,
                Status.Code.UNAVAILABLE, 503).entrySet()) {
            var response = handler.grpc(Status.fromCode(expected.getKey()).asRuntimeException());
            assertEquals(expected.getValue(), response.getStatusCode().value());
            assertEquals(Map.of("code", expected.getKey().name()), response.getBody());
        }
    }

    private record LegacyCommand(String sourceAccount, String destinationAccount, long amount, String kind) {
    }
}
