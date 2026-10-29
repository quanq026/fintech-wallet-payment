package ra.transactionservice.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ra.common.validation.InputRules;
import ra.contracts.ledger.Account;
import ra.contracts.ledger.LedgerEntry;
import ra.transactionservice.client.LedgerClient;
import ra.transactionservice.dto.request.CreateAccountRequest;
import ra.transactionservice.dto.response.AccountResponse;
import ra.transactionservice.dto.response.LedgerEntryResponse;
import ra.transactionservice.repository.OutboxRepository;
import ra.transactionservice.repository.TransactionRepository;
import ra.transactionservice.service.AccountService;

import java.util.List;
import java.util.Map;

@Service
public class AccountServiceImpl implements AccountService {
    private final LedgerClient ledger;
    private final TransactionRepository transactions;
    private final OutboxRepository outbox;

    public AccountServiceImpl(LedgerClient ledger, TransactionRepository transactions, OutboxRepository outbox) {
        this.ledger = ledger;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    @Override
    public AccountResponse create(String user, CreateAccountRequest request) {
        InputRules.client(user);
        if (!user.equals("admin") && (!request.ownerId().equals(user) || !request.kind().equals("USER"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        return accountResponse(ledger.create(request.accountId(), request.ownerId(), request.kind()));
    }

    @Override
    public AccountResponse account(String id, String user) {
        return accountResponse(owned(id, user));
    }

    @Override
    public List<LedgerEntryResponse> entries(String id, String user) {
        owned(id, user);
        return ledger.entries(id).getEntriesList().stream().map(this::entryResponse).toList();
    }

    @Override
    public Map<String, Object> reconciliation(String user) {
        if (!user.equals("admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        var result = ledger.reconcile();
        return Map.of("transactions", transactions.countByStatus(), "outboxPending", outbox.countPending(),
                "ledger", Map.of("journalCount", result.getJournalCount(), "entryCount", result.getEntryCount(),
                        "totalAmount", result.getTotalAmount(), "unbalancedJournals", result.getUnbalancedJournals(),
                        "negativeUserAccounts", result.getNegativeUserAccounts()));
    }

    private Account owned(String id, String user) {
        Account account = ledger.account(id);
        if (!user.equals("admin") && !account.getOwnerId().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_WALLET_OWNER");
        }
        return account;
    }

    private AccountResponse accountResponse(Account account) {
        return new AccountResponse(account.getAccountId(), account.getOwnerId(), account.getKind(),
                account.getBalance(), account.getCurrency());
    }

    private LedgerEntryResponse entryResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(entry.getEntryId(), entry.getJournalId(), entry.getAccountId(),
                entry.getAmount(), entry.getCreatedAt());
    }
}
