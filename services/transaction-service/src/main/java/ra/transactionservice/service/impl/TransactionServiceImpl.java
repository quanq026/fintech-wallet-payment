package ra.transactionservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ra.common.utils.RequestSignature;
import ra.common.validation.InputRules;
import ra.contracts.ledger.Operation;
import ra.transactionservice.client.LedgerClient;
import ra.transactionservice.client.RiskClient;
import ra.transactionservice.dto.request.TransactionRequest;
import ra.transactionservice.dto.response.TransactionResponse;
import ra.transactionservice.entity.Transaction;
import ra.transactionservice.repository.OutboxRepository;
import ra.transactionservice.repository.TransactionRepository;
import ra.transactionservice.service.TransactionService;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TransactionServiceImpl implements TransactionService {
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",
            Long.class);

    private final TransactionRepository transactions;
    private final OutboxRepository outbox;
    private final LedgerClient ledger;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final RiskClient risk;

    public TransactionServiceImpl(TransactionRepository transactions, OutboxRepository outbox,
                                  LedgerClient ledger, StringRedisTemplate redis, ObjectMapper json,
                                  KafkaTemplate<String, String> kafka, PlatformTransactionManager manager,
                                  RiskClient risk) {
        this.transactions = transactions;
        this.outbox = outbox;
        this.ledger = ledger;
        this.redis = redis;
        this.json = json;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(manager);
        this.risk = risk;
    }

    @Override
    public TransactionResponse submit(String client, String key, TransactionRequest request,
                                      String ip, boolean internal) {
        InputRules.uuid(key);
        InputRules.client(client);
        if (ip == null || ip.isBlank() || ip.length() > 80) {
            throw new IllegalArgumentException("Invalid request IP");
        }
        if (request.sourceAccount() == null || request.destinationAccount() == null
                || request.sourceAccount().length() > 100 || request.destinationAccount().length() > 100
                || request.sourceAccount().equals(request.destinationAccount())
                || request.amount() <= 0 || request.amount() > 9_000_000_000_000_000L
                || request.kind() == null
                || !Set.of("TRANSFER", "DEPOSIT", "WITHDRAWAL", "PAYMENT").contains(request.kind())) {
            throw fail(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER");
        }
        if (!internal && !request.kind().equals("TRANSFER")) {
            throw fail(HttpStatus.FORBIDDEN, "USE_PAYMENT_API");
        }
        var source = ledger.account(request.sourceAccount());
        if (!client.equals("admin") && !source.getOwnerId().equals(client)) {
            throw fail(HttpStatus.FORBIDDEN, "NOT_WALLET_OWNER");
        }
        ledger.account(request.destinationAccount());

        String fingerprint = RequestSignature.hash(bytes(request));
        String id = UUID.randomUUID().toString();
        Transaction transaction = new Transaction(id, client, key, fingerprint, request.sourceAccount(),
                request.destinationAccount(), request.amount(), request.kind(), "NEW", null, null, ip);
        try {
            transactions.insert(transaction);
        } catch (DuplicateKeyException duplicate) {
            // Existing durable key is checked below.
        }
        Transaction existing = transactions.findByClientAndKey(client, key);
        if (!fingerprint.equals(existing.getFingerprint())) {
            throw fail(HttpStatus.CONFLICT, "IDEMPOTENCY_PAYLOAD_MISMATCH");
        }
        String operationId = existing.getId();
        if (existing.getStatus().equals("NEW")) {
            process(operationId);
        }
        return view(operationId, client);
    }

    @Override
    public TransactionResponse view(String id, String user) {
        InputRules.uuid(id);
        InputRules.client(user);
        Transaction transaction = transactions.findById(id)
                .orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND"));
        if (!user.equals("admin") && !user.equals(transaction.getClientId())) {
            throw fail(HttpStatus.FORBIDDEN, "NOT_TRANSACTION_OWNER");
        }
        return new TransactionResponse(transaction.getId(), transaction.getClientId(),
                transaction.getSourceAccount(), transaction.getDestinationAccount(), transaction.getAmount(),
                transaction.getKind(), transaction.getStatus(), transaction.getJournalId(), transaction.getErrorCode());
    }

    private void process(String id) {
        if (!transactions.startProcessing(id)) {
            return;
        }
        Transaction transaction = transactions.findById(id)
                .orElseThrow(() -> new IllegalStateException("Transaction not found"));
        String from = transaction.getSourceAccount();
        String to = transaction.getDestinationAccount();
        String kind = transaction.getKind();
        String user = transaction.getClientId();
        long amount = transaction.getAmount();
        String owner = UUID.randomUUID().toString();
        List<String> locks = new ArrayList<>();
        try {
            try {
                complete(transaction, ledger.operation(id));
                return;
            } catch (StatusRuntimeException exception) {
                if (exception.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    throw exception;
                }
            }
            if (!kind.equals("DEPOSIT") && !risk.accepted(id, user, amount, transaction.getRequestIp())) {
                transactions.markRejected(id, "RISK_REJECTED");
                return;
            }
            for (String account : new TreeSet<>(List.of(from, to))) {
                String lock = "account-lock:" + account;
                if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, owner, Duration.ofSeconds(30)))) {
                    transactions.markUnknown(id, "ACCOUNT_BUSY");
                    return;
                }
                locks.add(lock);
            }
            complete(transaction, ledger.post(id, from, to, amount));
        } catch (StatusRuntimeException exception) {
            Status.Code code = exception.getStatus().getCode();
            if (Set.of(Status.Code.INVALID_ARGUMENT, Status.Code.FAILED_PRECONDITION,
                    Status.Code.ALREADY_EXISTS, Status.Code.NOT_FOUND).contains(code)) {
                transactions.markRejected(id, code.name());
            } else {
                transactions.markUnknown(id, "LEDGER_UNAVAILABLE");
            }
        } catch (Exception exception) {
            transactions.markUnknown(id, "DEPENDENCY_UNAVAILABLE");
        } finally {
            for (String lock : locks) {
                try {
                    redis.execute(UNLOCK, List.of(lock), owner);
                } catch (Exception ignored) {
                    // TTL releases the lock; Ledger is the durable authority.
                }
            }
        }
    }

    private void complete(Transaction transaction, Operation operation) {
        String sourceOwner = ledger.account(transaction.getSourceAccount()).getOwnerId();
        String destinationOwner = ledger.account(transaction.getDestinationAccount()).getOwnerId();
        tx.executeWithoutResult(status -> {
            transactions.markCompleted(transaction.getId(), operation.getJournalId());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_id", transaction.getId());
            event.put("transaction_id", transaction.getId());
            event.put("user_id", transaction.getClientId());
            event.put("source", transaction.getSourceAccount());
            event.put("destination", transaction.getDestinationAccount());
            event.put("amount", transaction.getAmount());
            event.put("kind", transaction.getKind());
            event.put("journal_id", operation.getJournalId());
            event.put("ip", transaction.getRequestIp());
            event.put("source_owner", sourceOwner);
            event.put("destination_owner", destinationOwner);
            outbox.insert(transaction.getId(), new String(bytes(event), StandardCharsets.UTF_8));
        });
    }

    @Scheduled(fixedDelay = 2000)
    public void recover() {
        transactions.markStaleProcessingUnknown();
        for (String id : transactions.findRecoverableIds()) {
            process(id);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        for (var event : outbox.findPending()) {
            try {
                kafka.send("financial-transactions", event.getTransactionId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                outbox.markPublished(event.getEventId());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }

    private byte[] bytes(Object data) {
        try {
            return json.writeValueAsBytes(data);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private static ResponseStatusException fail(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}

