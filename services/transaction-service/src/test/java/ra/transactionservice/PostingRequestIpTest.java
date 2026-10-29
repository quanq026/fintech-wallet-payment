package ra.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ra.transactionservice.controller.TransactionController;
import ra.transactionservice.dto.request.TransactionRequest;
import ra.transactionservice.dto.response.TransactionResponse;
import ra.transactionservice.service.AccountService;
import ra.transactionservice.service.TransactionService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostingRequestIpTest {
    @Test
    void postingRequiresInternalAuthAndUsesForwardedIp() {
        TransactionService service = mock(TransactionService.class);
        var controller = new TransactionController(service, mock(AccountService.class), "fixture-internal-token");
        var request = new TransactionRequest("alice-wallet", "bank-clearing", 5_000_001L, "WITHDRAWAL");
        String key = "11111111-1111-1111-1111-111111111111";
        String ip = "127.0.0.1";
        assertThrows(ResponseStatusException.class, () -> controller.post("invalid", "alice", key, ip, request));
        verifyNoInteractions(service);
        when(service.submit("alice", key, request, ip, true)).thenReturn(new TransactionResponse(key, "alice",
                "alice-wallet", "bank-clearing", 5_000_001L, "WITHDRAWAL", "COMPLETED", "journal", null));

        assertEquals(200, controller.post("fixture-internal-token", "alice", key, ip, request).getStatusCode().value());

        verify(service).submit("alice", key, request, ip, true);
    }
}
