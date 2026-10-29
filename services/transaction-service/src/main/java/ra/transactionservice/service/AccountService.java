package ra.transactionservice.service;

import ra.transactionservice.dto.request.CreateAccountRequest;
import ra.transactionservice.dto.response.AccountResponse;
import ra.transactionservice.dto.response.LedgerEntryResponse;

import java.util.List;
import java.util.Map;

public interface AccountService {
    AccountResponse create(String user, CreateAccountRequest request);

    AccountResponse account(String id, String user);

    List<LedgerEntryResponse> entries(String id, String user);

    Map<String, Object> reconciliation(String user);
}
