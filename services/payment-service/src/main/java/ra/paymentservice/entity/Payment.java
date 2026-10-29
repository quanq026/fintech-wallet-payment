package ra.paymentservice.entity;

import java.time.Instant;

public record Payment(
        String id,
        String userId,
        String idempotencyKey,
        String fingerprint,
        String accountId,
        String destination,
        long amount,
        String kind,
        String status,
        String transactionId,
        String providerReference,
        String requestIp,
        Instant createdAt) {
}
