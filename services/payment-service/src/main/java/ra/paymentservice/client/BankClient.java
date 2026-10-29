package ra.paymentservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BankClient {
    private final ServiceHttpClient http;
    private final String bankUrl;

    public BankClient(ServiceHttpClient http, @Value("${bank.url}") String bankUrl) {
        this.http = http;
        this.bankUrl = bankUrl;
    }

    public JsonNode createPayment(String paymentId, long amount, String user) {
        return http.request(bankUrl + "/payments", "POST",
                Map.of("payment_id", paymentId, "amount", amount), user, null);
    }

    public JsonNode settle(String paymentId, String user) {
        return http.request(bankUrl + "/settle", "POST", Map.of("payment_id", paymentId), user, null);
    }
}

