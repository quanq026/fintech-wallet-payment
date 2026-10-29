package ra.transactionservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ra.common.security.InternalAuthentication;
import ra.transactionservice.dto.request.CreateAccountRequest;
import ra.transactionservice.dto.request.TransactionRequest;
import ra.transactionservice.dto.response.AccountResponse;
import ra.transactionservice.dto.response.LedgerEntryResponse;
import ra.transactionservice.dto.response.TransactionResponse;
import ra.transactionservice.service.AccountService;
import ra.transactionservice.service.TransactionService;

import java.util.List;
import java.util.Map;

@RestController
public class TransactionController {
    private final TransactionService transactions;
    private final AccountService accounts;
    private final String token;

    public TransactionController(TransactionService transactions, AccountService accounts,
                                 @Value("${internal.token}") String token) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.token = token;
    }

    @PostMapping("/api/accounts")
    public AccountResponse create(@RequestHeader("X-Internal-Token") String providedToken,
                                  @RequestHeader("X-User-Id") String user,
                                  @RequestBody CreateAccountRequest request) {
        internal(providedToken);
        return accounts.create(user, request);
    }

    @GetMapping("/api/accounts/{id}")
    public AccountResponse account(@RequestHeader("X-Internal-Token") String providedToken,
                                   @RequestHeader("X-User-Id") String user, @PathVariable String id) {
        internal(providedToken);
        return accounts.account(id, user);
    }

    @GetMapping("/api/accounts/{id}/entries")
    public List<LedgerEntryResponse> entries(@RequestHeader("X-Internal-Token") String providedToken,
                                             @RequestHeader("X-User-Id") String user, @PathVariable String id) {
        internal(providedToken);
        return accounts.entries(id, user);
    }

    @PostMapping("/api/transfers")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader("X-Internal-Token") String providedToken, @RequestHeader("X-User-Id") String user,
            @RequestHeader("X-Idempotency-Key") String key,
            @RequestHeader(value = "X-Client-Ip", defaultValue = "local") String ip,
            @RequestBody TransactionRequest request) {
        internal(providedToken);
        return result(transactions.submit(user, key, request, ip, false));
    }

    @PostMapping("/internal/postings")
    public ResponseEntity<TransactionResponse> post(
            @RequestHeader("X-Internal-Token") String providedToken, @RequestHeader("X-User-Id") String user,
            @RequestHeader("X-Idempotency-Key") String key,
            @RequestHeader("X-Client-Ip") String ip, @RequestBody TransactionRequest request) {
        internal(providedToken);
        return result(transactions.submit(user, key, request, ip, true));
    }

    @GetMapping("/api/transactions/{id}")
    public TransactionResponse transaction(@RequestHeader("X-Internal-Token") String providedToken,
                                           @RequestHeader("X-User-Id") String user, @PathVariable String id) {
        internal(providedToken);
        return transactions.view(id, user);
    }

    @GetMapping("/api/reconciliation")
    public Map<String, Object> reconciliation(@RequestHeader("X-Internal-Token") String providedToken,
                                              @RequestHeader("X-User-Id") String user) {
        internal(providedToken);
        return accounts.reconciliation(user);
    }

    private void internal(String provided) {
        InternalAuthentication.require(token, provided);
    }

    private ResponseEntity<TransactionResponse> result(TransactionResponse response) {
        String status = response.status();
        return ResponseEntity.status(status.equals("COMPLETED") ? 200 : status.equals("REJECTED") ? 422 : 202)
                .body(response);
    }
}
