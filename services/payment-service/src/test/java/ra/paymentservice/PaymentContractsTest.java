package ra.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import ra.common.config.JsonValidationConfig;
import ra.paymentservice.dto.request.BankCallbackRequest;
import ra.paymentservice.dto.request.PaymentRequest;
import ra.paymentservice.dto.response.PaymentResponse;
import ra.paymentservice.entity.Payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaymentContractsTest {
    private final ObjectMapper json = strictJson();

    private static ObjectMapper strictJson() {
        var builder = Jackson2ObjectMapperBuilder.json();
        new JsonValidationConfig().strictJson().customize(builder);
        return builder.build();
    }

    @Test
    void paymentRequestKeepsStrictIntegerAndFieldValidation() throws Exception {
        assertEquals(new PaymentRequest("alice-wallet", null, 123),
                json.readValue("{\"accountId\":\"alice-wallet\",\"amount\":123}", PaymentRequest.class));
        for (String payload : new String[]{
                "{\"accountId\":\"alice-wallet\",\"amount\":\"123\"}",
                "{\"accountId\":\"alice-wallet\",\"amount\":123.0}",
                "{\"accountId\":\"alice-wallet\",\"amount\":true}",
                "{\"accountId\":\"alice-wallet\",\"amount\":null}",
                "{\"accountId\":\"alice-wallet\",\"amount\":123,\"extra\":true}",
                "{\"accountId\":\"alice-wallet\",\"amount\":123,\"amount\":456}",
                "{\"accountId\":\"invalid?account\",\"amount\":123}",
                "{\"accountId\":\"alice-wallet\",\"amount\":0}"}) {
            assertThrows(Exception.class, () -> json.readValue(payload, PaymentRequest.class), payload);
        }
    }

    @Test
    void callbackKeepsSnakeCaseFieldsAndStrictValidation() throws Exception {
        String payload = "{\"payment_id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"provider_reference\":\"22222222-2222-2222-2222-222222222222\","
                + "\"amount\":123,\"status\":\"PAID\"}";
        BankCallbackRequest callback = json.readValue(payload, BankCallbackRequest.class);
        assertEquals("11111111-1111-1111-1111-111111111111", callback.paymentId());
        assertEquals(json.readTree(payload), json.readTree(json.writeValueAsString(callback)));
        for (String invalid : new String[]{
                payload.replace("\"amount\":123", "\"amount\":123.0"),
                payload.replace("\"PAID\"", "\"PENDING\""),
                payload.replace("11111111-1111-1111-1111-111111111111", "invalid"),
                payload.replace("\"amount\":123", "\"amount\":123,\"extra\":true"),
                payload.replace("\"payment_id\"", "\"paymentId\"")}) {
            assertThrows(Exception.class, () -> json.readValue(invalid, BankCallbackRequest.class), invalid);
        }
    }

    @Test
    void responseMatchesFormerJdbcMapIncludingTimestampAndNulls() {
        Instant createdAt = Instant.parse("2026-10-06T00:00:00.123456Z");
        Payment payment = new Payment("id", "alice", "key", "fingerprint", "wallet",
                "bank-clearing", 123, "DEPOSIT", "PENDING", null, null, "127.0.0.1", createdAt);
        Map<String, Object> formerRow = new LinkedHashMap<>();
        formerRow.put("id", payment.id());
        formerRow.put("user_id", payment.userId());
        formerRow.put("idempotency_key", payment.idempotencyKey());
        formerRow.put("fingerprint", payment.fingerprint());
        formerRow.put("account_id", payment.accountId());
        formerRow.put("destination", payment.destination());
        formerRow.put("amount", payment.amount());
        formerRow.put("kind", payment.kind());
        formerRow.put("status", payment.status());
        formerRow.put("transaction_id", null);
        formerRow.put("provider_reference", null);
        formerRow.put("created_at", Timestamp.from(createdAt));

        assertEquals(json.valueToTree(formerRow), json.valueToTree(PaymentResponse.from(payment)));
    }
}
