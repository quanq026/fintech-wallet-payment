package ra.ledgerservice.service.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ra.contracts.ledger.Account;
import ra.contracts.ledger.CreateAccountRequest;
import ra.contracts.ledger.ListEntriesResponse;
import ra.contracts.ledger.Operation;
import ra.contracts.ledger.PostTransferRequest;
import ra.contracts.ledger.Reconciliation;
import ra.ledgerservice.repository.LedgerRepository;
import ra.ledgerservice.service.LedgerService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class LedgerServiceImpl implements LedgerService {
    private final LedgerRepository ledgerRepository;

    public LedgerServiceImpl(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public Account createAccount(CreateAccountRequest request) {
        identifier(request.getAccountId());
        identifier(request.getOwnerId());
        if (!Set.of("USER", "MERCHANT", "CLEARING").contains(request.getKind())) {
            throw new IllegalArgumentException("Invalid account kind");
        }
        try {
            ledgerRepository.createAccount(request);
        } catch (DataAccessException error) {
            throw translate(error);
        }
        return getAccount(request.getAccountId());
    }

    @Override
    public Account getAccount(String accountId) {
        identifier(accountId);
        return ledgerRepository.findAccount(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found"));
    }

    @Override
    public Operation getOperation(String operationId) {
        identifier(operationId);
        operationId(operationId);
        return ledgerRepository.findOperation(operationId)
                .orElseThrow(() -> new NoSuchElementException("Operation not found"));
    }

    @Override
    public ListEntriesResponse listEntries(String accountId) {
        getAccount(accountId);
        return ListEntriesResponse.newBuilder()
                .addAllEntries(ledgerRepository.findEntries(accountId))
                .build();
    }

    @Override
    public Operation postTransfer(PostTransferRequest request) {
        identifier(request.getOperationId());
        identifier(request.getSourceAccountId());
        identifier(request.getDestinationAccountId());
        operationId(request.getOperationId());
        if (request.getAmount() <= 0 || !request.getCurrency().equals("VND")
                || request.getSourceAccountId().equals(request.getDestinationAccountId())) {
            throw new IllegalArgumentException("Invalid transfer");
        }
        String fingerprint = fingerprint(request);
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                ledgerRepository.postTransfer(request, fingerprint);
                return getOperation(request.getOperationId());
            } catch (DataAccessException error) {
                if (!retryable(error) || attempt == 99) {
                    throw translate(error);
                }
                try {
                    Thread.sleep(Math.min(50, attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted transaction", interrupted);
                }
            }
        }
        throw new IllegalStateException("Transaction retry exhausted");
    }

    @Override
    public Reconciliation reconcile() {
        return ledgerRepository.reconcile();
    }

    private static RuntimeException translate(DataAccessException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if ("45001".equals(sql.getSQLState())) {
                    return new IllegalStateException("Insufficient funds");
                }
                if ("45002".equals(sql.getSQLState())) {
                    return new IllegalArgumentException(sql.getMessage());
                }
                if ("45004".equals(sql.getSQLState())) {
                    return new NoSuchElementException("Account not found");
                }
            }
        }
        return error;
    }

    private static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (Set.of(1213, 1205, 1062).contains(sql.getErrorCode())
                    || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(PostTransferRequest request) {
        String payload = request.getSourceAccountId() + "\n" + request.getDestinationAccountId()
                + "\n" + request.getAmount() + "\n" + request.getCurrency();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void identifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid identifier");
        }
    }

    private static void operationId(String operationId) {
        if (operationId == null
                || !operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Operation ID must be canonical lowercase UUID");
        }
    }
}
