package ra.paymentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import ra.paymentservice.entity.Payment;

import java.sql.Timestamp;

public record PaymentResponse(
        String id,
        @JsonProperty("user_id") String userId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String fingerprint,
        @JsonProperty("account_id") String accountId,
        String destination,
        long amount,
        String kind,
        String status,
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("provider_reference") String providerReference,
        @JsonProperty("created_at") Timestamp createdAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.id(), payment.userId(), payment.idempotencyKey(), payment.fingerprint(),
                payment.accountId(), payment.destination(), payment.amount(), payment.kind(),
                payment.status(), payment.transactionId(), payment.providerReference(),
                Timestamp.from(payment.createdAt()));
    }
}

