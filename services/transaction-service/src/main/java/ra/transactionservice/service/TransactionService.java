package ra.transactionservice.service;

import ra.transactionservice.dto.request.TransactionRequest;
import ra.transactionservice.dto.response.TransactionResponse;

public interface TransactionService {
    TransactionResponse submit(String client, String key, TransactionRequest request, String ip, boolean internal);

    TransactionResponse view(String id, String user);
}
