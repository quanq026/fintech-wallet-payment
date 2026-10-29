package ra.ledgerservice.service;

import ra.contracts.ledger.Account;
import ra.contracts.ledger.CreateAccountRequest;
import ra.contracts.ledger.ListEntriesResponse;
import ra.contracts.ledger.Operation;
import ra.contracts.ledger.PostTransferRequest;
import ra.contracts.ledger.Reconciliation;

public interface LedgerService {
    Account createAccount(CreateAccountRequest request);

    Account getAccount(String accountId);

    Operation getOperation(String operationId);

    ListEntriesResponse listEntries(String accountId);

    Operation postTransfer(PostTransferRequest request);

    Reconciliation reconcile();
}
