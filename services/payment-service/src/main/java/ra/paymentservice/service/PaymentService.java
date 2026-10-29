package ra.paymentservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import ra.paymentservice.dto.request.PaymentRequest;
import ra.paymentservice.dto.response.PaymentResponse;

public interface PaymentService {
    PaymentResponse create(String kind, String user, String key, PaymentRequest body, String ip);

    PaymentResponse get(String id, String user);

    JsonNode settle(String id, String user);

    PaymentResponse webhook(String signature, String body);
}
