package ra.paymentservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ra.common.utils.RequestSignature;
import ra.common.validation.InputRules;
import ra.paymentservice.client.BankClient;
import ra.paymentservice.client.TransactionClient;
import ra.paymentservice.dto.request.BankCallbackRequest;
import ra.paymentservice.dto.request.PaymentRequest;
import ra.paymentservice.dto.response.PaymentResponse;
import ra.paymentservice.entity.Payment;
import ra.paymentservice.repository.PaymentRepository;
import ra.paymentservice.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository repository;
    private final TransactionClient transactions;
    private final BankClient bank;
    private final ObjectMapper json;
    private final String bankSecret;

    public PaymentServiceImpl(PaymentRepository repository, TransactionClient transactions,
                              BankClient bank, ObjectMapper json,
                              @Value("${bank.secret}") String bankSecret) {
        this.repository = repository;
        this.transactions = transactions;
        this.bank = bank;
        this.json = json;
        this.bankSecret = bankSecret;
    }

    @Override
    public PaymentResponse create(String kind, String user, String key, PaymentRequest body, String ip) {
        InputRules.uuid(key);
        InputRules.client(user);
        if (ip == null || !ip.matches("[A-Za-z0-9_.:-]{1,80}")) {
            throw fail(400, "INVALID_REQUEST_IP");
        }
        if (body.amount() <= 0 || body.amount() > InputRules.MAX_AMOUNT
                || body.accountId() == null || body.accountId().length() > 100) {
            throw fail(400, "INVALID_AMOUNT_OR_ACCOUNT");
        }
        var account = transactions.getAccount(body.accountId(), user);
        if (!account.path("kind").asText().equals("USER") && !user.equals("admin")) {
            throw fail(403, "USER_WALLET_REQUIRED");
        }
        String destination = kind.equals("PAYMENT") ? body.destination() : "bank-clearing";
        if (!kind.equals("PAYMENT") && body.destination() != null
                && !body.destination().equals("bank-clearing")) {
            throw fail(400, "DESTINATION_NOT_ALLOWED");
        }
        if (destination == null || destination.length() > 100 || destination.equals(body.accountId())) {
            throw fail(400, "INVALID_DESTINATION");
        }
        if (kind.equals("PAYMENT")) {
            var merchant = transactions.getAccount(destination, "admin");
            if (!merchant.path("kind").asText().equals("MERCHANT")) {
                throw fail(400, "MERCHANT_REQUIRED");
            }
        }
        String fingerprint = RequestSignature.hash(encode(new TreeMap<>(Map.of(
                "account", body.accountId(), "destination", destination,
                "amount", body.amount(), "kind", kind))).getBytes(StandardCharsets.UTF_8));
        String id = UUID.randomUUID().toString();
        repository.insert(id, user, key, fingerprint, body.accountId(), destination, body.amount(), kind, ip);
        Payment payment = repository.findByUserAndKey(user, key);
        if (!fingerprint.equals(payment.fingerprint())) {
            throw fail(409, "IDEMPOTENCY_PAYLOAD_MISMATCH");
        }
        id = payment.id();
        if (kind.equals("DEPOSIT")) {
            var result = bank.createPayment(id, body.amount(), user);
            repository.updateProviderReference(id, result.path("provider_reference").asText());
        } else {
            finish(payment, user);
        }
        return PaymentResponse.from(owned(id, user));
    }

    @Override
    public PaymentResponse get(String id, String user) {
        Payment payment = owned(id, user);
        if (payment.transactionId() != null) {
            var result = transactions.getTransaction(payment.transactionId());
            repository.updateStatus(id, result.path("status").asText());
        }
        return PaymentResponse.from(owned(id, user));
    }

    @Override
    public JsonNode settle(String id, String user) {
        Payment payment = owned(id, user);
        if (!payment.kind().equals("DEPOSIT")) {
            throw fail(400, "DEPOSIT_REQUIRED");
        }
        return bank.settle(id, user);
    }

    @Override
    public PaymentResponse webhook(String signature, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) {
            throw fail(413, "PAYLOAD_TOO_LARGE");
        }
        if (!RequestSignature.verify(bankSecret, "POST", "/internal/webhook", "0", "bank", bytes, signature)) {
            throw fail(401, "INVALID_BANK_SIGNATURE");
        }
        try {
            var callback = json.readValue(body, BankCallbackRequest.class);
            Payment payment = owned(callback.paymentId(), "admin");
            if (!payment.kind().equals("DEPOSIT")
                    || !Objects.equals(payment.providerReference(), callback.providerReference())
                    || payment.amount() != callback.amount()) {
                throw fail(400, "CALLBACK_MISMATCH");
            }
            finish(payment, "admin");
            return PaymentResponse.from(owned(payment.id(), "admin"));
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw fail(400, "INVALID_CALLBACK");
        }
    }

    private Payment owned(String id, String user) {
        InputRules.uuid(id);
        InputRules.client(user);
        Payment payment = repository.findById(id).orElseThrow(() -> fail(404, "PAYMENT_NOT_FOUND"));
        if (!user.equals("admin") && !user.equals(payment.userId())) {
            throw fail(403, "NOT_PAYMENT_OWNER");
        }
        return payment;
    }

    private void finish(Payment payment, String user) {
        if (payment.status().equals("COMPLETED") || payment.status().equals("REJECTED")) {
            return;
        }
        boolean deposit = payment.kind().equals("DEPOSIT");
        Map<String, Object> command = Map.of(
                "sourceAccount", deposit ? "bank-clearing" : payment.accountId(),
                "destinationAccount", deposit ? payment.accountId() : payment.destination(),
                "amount", payment.amount(), "kind", payment.kind());
        // Legacy rows have no original IP; preserve their former posting context.
        String ip = payment.requestIp() == null ? "bank-sandbox" : payment.requestIp();
        var result = transactions.post(command, user, payment.id(), ip);
        repository.updateTransaction(payment.id(), result.path("id").asText(), result.path("status").asText());
    }

    @Scheduled(fixedDelay = 5000)
    public void recover() {
        for (Payment payment : repository.findRecoverable()) {
            try {
                finish(payment, payment.userId());
            } catch (Exception ignored) {
                // Pending requests remain durable for the next recovery pass.
            }
        }
    }

    private String encode(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception error) {
            throw fail(400, "BAD_PAYLOAD");
        }
    }

    private ResponseStatusException fail(int status, String code) {
        return new ResponseStatusException(HttpStatus.valueOf(status), code);
    }
}
